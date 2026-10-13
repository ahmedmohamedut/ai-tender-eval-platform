package app.tendereval.backend.documents;

import app.tendereval.backend.api.DocumentsApi;
import app.tendereval.backend.api.model.DocType;
import app.tendereval.backend.api.model.Document;
import app.tendereval.backend.api.model.DocumentImpact;
import app.tendereval.backend.api.model.DocumentPage;
import app.tendereval.backend.api.model.DocumentVersion;
import app.tendereval.backend.api.model.ImpactDecision;
import app.tendereval.backend.db.RequestTransaction;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** UX-DOC-02..04. The two uploads are in {@link DocumentUploadController} (streaming). */
@RestController
public class DocumentsController implements DocumentsApi {

  private final RequestTransaction tx;
  private final DocumentService service;
  private final app.tendereval.backend.jobs.DocumentRetryService retries;
  private final app.tendereval.backend.jobs.JobDispatcher dispatcher;

  public DocumentsController(
      RequestTransaction tx,
      DocumentService service,
      app.tendereval.backend.jobs.DocumentRetryService retries,
      app.tendereval.backend.jobs.JobDispatcher dispatcher) {
    this.tx = tx;
    this.service = service;
    this.retries = retries;
    this.dispatcher = dispatcher;
  }

  @Override
  public Mono<ResponseEntity<app.tendereval.backend.api.model.Job>> retryDocumentProcessing(
      UUID documentVersionId, ServerWebExchange exchange) {
    return tx.asUser((db, user) -> retries.retryVersion(db, user, documentVersionId))
        // The transaction has committed: only now is the command published (rule 4).
        .flatMap(job -> dispatcher.publish(job.getId()).thenReturn(job))
        .map(
            job ->
                ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED)
                    .location(java.net.URI.create("/api/v1/jobs/" + job.getId()))
                    .body(job));
  }

  @Override
  public Mono<ResponseEntity<DocumentPage>> listDocuments(
      UUID tenderId, DocType docType, ServerWebExchange exchange) {
    var q = exchange.getRequest().getQueryParams();
    Integer limit = q.getFirst("limit") == null ? null : Integer.valueOf(q.getFirst("limit"));
    return tx.inRequestTransaction(
            db ->
                service.list(
                    db, tenderId, docType, limit, q.getFirst("cursor"), q.getFirst("sort")))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Document>> getDocument(UUID documentId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.get(db, documentId)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<DocumentVersion>> getDocumentVersion(
      UUID documentVersionId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.getVersion(db, documentVersionId))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<DocumentImpact>> getDocumentImpact(
      UUID documentVersionId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.impact(db, documentVersionId))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Void>> decideDocumentImpact(
      UUID documentVersionId, Mono<ImpactDecision> impactDecision, ServerWebExchange exchange) {
    return impactDecision
        .flatMap(
            body ->
                tx.asUser(
                    (db, user) ->
                        service.decideImpact(
                            db,
                            user,
                            documentVersionId,
                            body.getChoice().getValue(),
                            body.getNote())))
        .thenReturn(ResponseEntity.noContent().<Void>build());
  }
}
