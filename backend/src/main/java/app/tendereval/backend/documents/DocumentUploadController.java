package app.tendereval.backend.documents;

import app.tendereval.backend.api.model.DocType;
import app.tendereval.backend.api.model.Document;
import app.tendereval.backend.api.model.DocumentVersion;
import app.tendereval.backend.common.Idempotency;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.web.ApiException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePartEvent;
import org.springframework.http.codec.multipart.FormPartEvent;
import org.springframework.http.codec.multipart.PartEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code uploadDocument} and {@code uploadDocumentVersion}. They are defined in {@code
 * api/openapi.yaml} and implemented here rather than in the generated interface, because the
 * generated {@code @RequestPart} binding reads the whole body before the method runs; this handler
 * receives {@link PartEvent}s and streams the file as it arrives (PI 4.4).
 *
 * <p>Fields (type, title, parent) must precede the file in the multipart body.
 */
@RestController
public class DocumentUploadController {

  private final UploadService uploads;
  private final RequestTransaction tx;
  private final Idempotency idempotency;

  public DocumentUploadController(
      UploadService uploads, RequestTransaction tx, Idempotency idempotency) {
    this.uploads = uploads;
    this.tx = tx;
    this.idempotency = idempotency;
  }

  @PostMapping(
      path = "/tenders/{tenderId}/documents",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
  public Mono<ResponseEntity<Document>> uploadDocument(
      @PathVariable UUID tenderId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody Flux<PartEvent> parts,
      ServerWebExchange exchange) {
    long length = exchange.getRequest().getHeaders().getContentLength();
    return handle(
        parts,
        length,
        idempotencyKey,
        "POST /tenders/{id}/documents:" + tenderId,
        fields -> {
          String type = fields.get("doc_type");
          if (type == null) {
            throw ApiException.invalid("doc_type", "required", "Choose the document type");
          }
          DocType docType;
          try {
            docType = DocType.fromValue(type);
          } catch (IllegalArgumentException e) {
            throw ApiException.invalid("doc_type", "invalid", "Choose a valid document type");
          }
          UUID parent = uuidOrNull(fields.get("parent_document_id"), "parent_document_id");
          return new UploadService.Request(
              tenderId, null, docType, fields.get("title"), parent, null);
        },
        true);
  }

  @PostMapping(
      path = "/documents/{documentId}/versions",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
  public Mono<ResponseEntity<DocumentVersion>> uploadDocumentVersion(
      @PathVariable UUID documentId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody Flux<PartEvent> parts,
      ServerWebExchange exchange) {
    long length = exchange.getRequest().getHeaders().getContentLength();
    return handle(
            parts,
            length,
            idempotencyKey,
            "POST /documents/{id}/versions:" + documentId,
            fields -> {
              // The tender is resolved from the document inside the upload transaction.
              return new UploadService.Request(
                  null, documentId, null, null, null, fields.get("note"));
            },
            false)
        .map(
            r ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .headers(r.getHeaders())
                    .body(r.getBody().getCurrentVersion()));
  }

  private Mono<ResponseEntity<Document>> handle(
      Flux<PartEvent> events,
      long length,
      String idempotencyKey,
      String operation,
      java.util.function.Function<Map<String, String>, UploadService.Request> toRequest,
      boolean newDocument) {
    Map<String, String> fields = new HashMap<>();
    Mono<ResponseEntity<Document>> work =
        events
            .windowUntil(PartEvent::isLast)
            .concatMap(
                window ->
                    window.switchOnFirst(
                        (signal, part) -> {
                          PartEvent first = signal.get();
                          if (first == null) {
                            return Flux.<Document>empty();
                          }
                          if (first instanceof FilePartEvent file && "file".equals(file.name())) {
                            UploadService.Request req = toRequest.apply(fields);
                            return uploads
                                .upload(req, file.filename(), part.map(PartEvent::content), length)
                                .flux();
                          }
                          if (first instanceof FormPartEvent) {
                            return part.map(
                                    e -> {
                                      FormPartEvent f = (FormPartEvent) e;
                                      fields.merge(f.name(), f.value(), String::concat);
                                      return f;
                                    })
                                .then(Mono.<Document>empty())
                                .flux();
                          }
                          // An unknown part: discard its bytes.
                          return part.doOnNext(e -> DataBufferUtils.release(e.content()))
                              .then(Mono.<Document>empty())
                              .flux();
                        }))
            .next()
            .switchIfEmpty(
                Mono.error(
                    ApiException.invalid(
                        "file",
                        "required",
                        "Send the file in the field named file, after the other fields")))
            .map(
                doc ->
                    ResponseEntity.status(HttpStatus.CREATED)
                        .location(URI.create("/api/v1/documents/" + doc.getId()))
                        .body(doc));
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return work;
    }
    String key = Idempotency.requireKey(idempotencyKey);
    String hash = Idempotency.hash(operation, String.valueOf(length));
    return tx.asUser(
        (db, user) ->
            idempotency
                .lookup(db, user.id(), key, operation, hash, Document.class)
                .switchIfEmpty(
                    Mono.defer(
                        () ->
                            work.flatMap(
                                response ->
                                    tx.inRequestTransaction(
                                            d ->
                                                idempotency.remember(
                                                    d, user.id(), key, operation, hash, response))
                                        .thenReturn(response)))));
  }

  private static UUID uuidOrNull(String v, String field) {
    if (v == null || v.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(v.trim());
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid(field, "invalid", "Not a valid identifier");
    }
  }
}
