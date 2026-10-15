package app.tendereval.backend.api;

import app.tendereval.backend.api.model.DisagreementPage;
import app.tendereval.backend.api.model.FinalGradeInput;
import app.tendereval.backend.api.model.Grade;
import app.tendereval.backend.api.model.Importance;
import app.tendereval.backend.api.model.Matrix;
import app.tendereval.backend.api.model.MatrixCellDetail;
import app.tendereval.backend.api.model.ProgressMatrix;
import app.tendereval.backend.api.model.ReasonBody;
import app.tendereval.backend.api.model.ReportAppendix;
import app.tendereval.backend.api.model.ReportHeader;
import app.tendereval.backend.api.model.ReportOverview;
import app.tendereval.backend.api.model.ReportVersion;
import app.tendereval.backend.api.model.ScreeningOutcome;
import app.tendereval.backend.common.Idempotency;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.report.ReportService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The Report group (UX-RPT): the consolidated report, the Chair's final grades, finalizing and
 * reopening. Exports and reminders arrive with their slices and answer 501 until then.
 */
@RestController
public class ReportController implements ReportApi {

  private final RequestTransaction tx;
  private final ReportService service;
  private final Idempotency idempotency;
  private final app.tendereval.backend.exports.ExportService exports;

  public ReportController(
      RequestTransaction tx,
      ReportService service,
      Idempotency idempotency,
      app.tendereval.backend.exports.ExportService exports) {
    this.tx = tx;
    this.service = service;
    this.idempotency = idempotency;
    this.exports = exports;
  }

  @Override
  public Mono<ResponseEntity<app.tendereval.backend.api.model.Export>> exportReport(
      UUID tenderId,
      Mono<app.tendereval.backend.api.model.ReportExportRequest> request,
      String idempotencyKey,
      ServerWebExchange exchange) {
    String key = Idempotency.requireKey(idempotencyKey);
    return request.flatMap(
        in ->
            exports.start(
                key,
                "POST /tenders/{id}/report/exports:" + tenderId,
                Idempotency.hash("exportReport", tenderId.toString(), String.valueOf(in)),
                (db, user) ->
                    exports.reportSpec(
                        db, tenderId, in.getFormat().getValue(), in.getVersionNo())));
  }

  @Override
  public Mono<ResponseEntity<ReportHeader>> getReport(
      UUID tenderId, Integer version, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.header(db, tenderId, version))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<ReportAppendix>> getReportAppendix(
      UUID tenderId, Integer version, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.appendix(db, tenderId, version))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<DisagreementPage>> listDisagreements(
      UUID tenderId, Integer version, Integer limit, String cursor, ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db -> service.disagreements(db, tenderId, version, limit, cursor))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Matrix>> getReportMatrix(
      UUID tenderId,
      Integer version,
      Integer limit,
      String cursor,
      UUID categoryId,
      Importance importance,
      Grade grade,
      UUID tenderVendorId,
      Boolean disagreement,
      String sort,
      ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db ->
                service.matrix(
                    db,
                    tenderId,
                    version,
                    limit,
                    cursor,
                    categoryId,
                    importance,
                    grade,
                    tenderVendorId,
                    disagreement))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<MatrixCellDetail>> getReportMatrixCell(
      UUID tenderId,
      UUID requirementId,
      UUID tenderVendorId,
      Integer version,
      ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db -> service.cellDetail(db, tenderId, requirementId, tenderVendorId, version))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<ReportOverview>> getReportOverview(
      UUID tenderId, Integer version, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.overview(db, tenderId, version))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<ProgressMatrix>> getReportProgress(
      UUID tenderId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.progress(db, tenderId)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Flux<ScreeningOutcome>>> listScreeningOutcomes(
      UUID tenderId, Integer version, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.screening(db, tenderId, version))
        .map(l -> ResponseEntity.ok(Flux.fromIterable(l)));
  }

  @Override
  public Mono<ResponseEntity<Flux<ReportVersion>>> listReportVersions(
      UUID tenderId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.listVersions(db, tenderId))
        .map(l -> ResponseEntity.ok(Flux.fromIterable(l)));
  }

  @Override
  public Mono<ResponseEntity<MatrixCellDetail>> recordFinalGrade(
      UUID tenderId,
      UUID tenderVendorId,
      UUID requirementId,
      Mono<FinalGradeInput> finalGradeInput,
      ServerWebExchange exchange) {
    return finalGradeInput
        .flatMap(
            in ->
                tx.asUser(
                    (db, user) ->
                        service.recordFinalGrade(
                            db, user, tenderId, tenderVendorId, requirementId, in)))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<ReportVersion>> finalizeReport(
      UUID tenderId, String idempotencyKey, ServerWebExchange exchange) {
    String key = Idempotency.requireKey(idempotencyKey);
    return tx.asUser(
        (db, user) ->
            idempotency.run(
                db,
                user.id(),
                key,
                "POST /tenders/{id}/report/finalize:" + tenderId,
                Idempotency.hash("finalizeReport", tenderId.toString()),
                ReportVersion.class,
                () -> service.finalizeReport(db, user, tenderId).map(ResponseEntity::ok)));
  }

  @Override
  public Mono<ResponseEntity<ReportVersion>> reopenReport(
      UUID tenderId, Mono<ReasonBody> reasonBody, ServerWebExchange exchange) {
    return reasonBody
        .flatMap(in -> tx.asUser((db, user) -> service.reopen(db, user, tenderId, in.getReason())))
        .map(v -> ResponseEntity.status(HttpStatus.CREATED).body(v));
  }
}
