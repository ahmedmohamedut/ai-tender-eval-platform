package app.tendereval.backend.api;

import app.tendereval.backend.api.model.AuditExportRequest;
import app.tendereval.backend.api.model.Export;
import app.tendereval.backend.common.Idempotency;
import app.tendereval.backend.exports.ExportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The Audit group: search of the trail and of the document access log (UX-AUD-01, UX-NFR-07) and
 * the export of the filtered trail (UX-AUD-03).
 */
@RestController
public class AuditController implements AuditApi {

  private final ExportService exports;
  private final app.tendereval.backend.db.RequestTransaction tx;
  private final app.tendereval.backend.audit.AuditSearch search;
  private final app.tendereval.backend.audit.AccessSearch accessSearch;

  public AuditController(
      ExportService exports,
      app.tendereval.backend.db.RequestTransaction tx,
      app.tendereval.backend.audit.AuditSearch search,
      app.tendereval.backend.audit.AccessSearch accessSearch) {
    this.exports = exports;
    this.tx = tx;
    this.search = search;
    this.accessSearch = accessSearch;
  }

  @Override
  public Mono<ResponseEntity<app.tendereval.backend.api.model.AccessEventPage>>
      listDocumentAccessEvents(
          Integer limit,
          String cursor,
          java.util.UUID tenderId,
          java.util.UUID userId,
          java.time.OffsetDateTime from,
          java.time.OffsetDateTime to,
          ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db -> accessSearch.search(db, limit, cursor, tenderId, userId, from, to))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<app.tendereval.backend.api.model.AuditEventPage>> listAuditEvents(
      Integer limit,
      String cursor,
      java.util.UUID tenderId,
      java.util.UUID tenderVendorId,
      java.util.UUID userId,
      app.tendereval.backend.api.model.AuditAction action,
      app.tendereval.backend.api.model.AuditSource source,
      java.time.OffsetDateTime from,
      java.time.OffsetDateTime to,
      ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db ->
                search.search(
                    db, limit, cursor, tenderId, tenderVendorId, userId, action, source, from, to))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Export>> exportAuditEvents(
      Mono<AuditExportRequest> auditExportRequest,
      String idempotencyKey,
      ServerWebExchange exchange) {
    String key = Idempotency.requireKey(idempotencyKey);
    return auditExportRequest.flatMap(
        in ->
            exports.start(
                key,
                "POST /audit-events/export",
                Idempotency.hash("exportAuditEvents", String.valueOf(in)),
                (db, user) -> exports.auditSpec(user, in)));
  }
}
