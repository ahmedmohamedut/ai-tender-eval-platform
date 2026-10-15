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
 * The Audit group. The export of the filtered trail (UX-AUD-03) is built; the search arrives with
 * the audit screen and answers 501 until then.
 */
@RestController
public class AuditController implements AuditApi {

  private final ExportService exports;

  public AuditController(ExportService exports) {
    this.exports = exports;
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
