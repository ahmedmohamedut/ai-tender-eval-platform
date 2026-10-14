package app.tendereval.backend.api;

import app.tendereval.backend.api.model.BulkResult;
import app.tendereval.backend.api.model.BulkRows;
import app.tendereval.backend.api.model.Clarification;
import app.tendereval.backend.api.model.CrossVendorView;
import app.tendereval.backend.api.model.EvaluationProgress;
import app.tendereval.backend.api.model.EvaluationRow;
import app.tendereval.backend.api.model.EvaluationRowPage;
import app.tendereval.backend.api.model.ExcludeMember;
import app.tendereval.backend.api.model.FeedbackInput;
import app.tendereval.backend.api.model.Grade;
import app.tendereval.backend.api.model.History;
import app.tendereval.backend.api.model.Importance;
import app.tendereval.backend.api.model.Job;
import app.tendereval.backend.api.model.MemberEvaluation;
import app.tendereval.backend.api.model.PeerRowPage;
import app.tendereval.backend.api.model.ReopenEvaluation;
import app.tendereval.backend.api.model.RerunRequest;
import app.tendereval.backend.api.model.RowReviewState;
import app.tendereval.backend.api.model.RowUpdate;
import app.tendereval.backend.common.ETags;
import app.tendereval.backend.common.Idempotency;
import app.tendereval.backend.common.RateLimiter;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.evaluation.EvaluationService;
import app.tendereval.backend.evaluation.MemberEvaluationService;
import app.tendereval.backend.jobs.JobDispatcher;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The Evaluation group (UX-EVA): the system's re-run and the member's own evaluation. The
 * clarification export arrives with the export machinery (Phase 5) and answers 501.
 */
@RestController
public class EvaluationController implements EvaluationApi {

  private final RequestTransaction tx;
  private final EvaluationService service;
  private final JobDispatcher dispatcher;
  private final Idempotency idempotency;
  private final RateLimiter limiter;
  private final MemberEvaluationService member;

  public EvaluationController(
      RequestTransaction tx,
      EvaluationService service,
      JobDispatcher dispatcher,
      Idempotency idempotency,
      RateLimiter limiter,
      MemberEvaluationService member) {
    this.tx = tx;
    this.service = service;
    this.dispatcher = dispatcher;
    this.idempotency = idempotency;
    this.limiter = limiter;
    this.member = member;
  }

  @Override
  public Mono<ResponseEntity<Job>> rerunEvaluation(
      UUID tenderVendorId,
      Mono<RerunRequest> rerunRequest,
      String idempotencyKey,
      ServerWebExchange exchange) {
    String key = Idempotency.requireKey(idempotencyKey);
    return rerunRequest
        .flatMap(
            in ->
                tx.asUser(
                    (db, user) -> {
                      limiter.check(user.id(), RateLimiter.Bucket.JOB_STARTS);
                      return idempotency.run(
                          db,
                          user.id(),
                          key,
                          "POST /tender-vendors/{id}/evaluation/rerun:" + tenderVendorId,
                          Idempotency.hash(
                              "rerunEvaluation", tenderVendorId.toString(), String.valueOf(in)),
                          Job.class,
                          () ->
                              service
                                  .rerun(db, user, tenderVendorId)
                                  .map(
                                      j ->
                                          ResponseEntity.status(HttpStatus.ACCEPTED)
                                              .location(URI.create("/api/v1/jobs/" + j.getId()))
                                              .body(j)));
                    }))
        // After the commit (rule 4): only now is the command published.
        .flatMap(
            r ->
                r.getBody() != null && !"true".equals(r.getHeaders().getFirst("Idempotent-Replay"))
                    ? dispatcher.publishEvaluation(r.getBody().getId()).thenReturn(r)
                    : Mono.just(r));
  }

  // ---- the member's own evaluation (UX-EVA-01..18) ---------------------------------------------

  private static ResponseEntity<EvaluationRow> tagged(EvaluationRow r) {
    return ResponseEntity.ok().eTag(ETags.of(r.getRowVersion())).body(r);
  }

  @Override
  public Mono<ResponseEntity<MemberEvaluation>> getMyEvaluation(
      UUID tenderVendorId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> member.mine(db, tenderVendorId)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<MemberEvaluation>> submitEvaluation(
      UUID tenderVendorId, String idempotencyKey, ServerWebExchange exchange) {
    String key = Idempotency.requireKey(idempotencyKey);
    return tx.asUser(
        (db, user) ->
            idempotency.run(
                db,
                user.id(),
                key,
                "POST /tender-vendors/{id}/evaluation/submit:" + tenderVendorId,
                Idempotency.hash("submitEvaluation", tenderVendorId.toString()),
                MemberEvaluation.class,
                () -> member.submit(db, user, tenderVendorId).map(ResponseEntity::ok)));
  }

  @Override
  public Mono<ResponseEntity<EvaluationRowPage>> listEvaluationRows(
      UUID tenderVendorId,
      Integer limit,
      String cursor,
      String q,
      String sort,
      Grade grade,
      Importance importance,
      UUID categoryId,
      RowReviewState reviewState,
      Boolean overridden,
      Boolean flagged,
      Boolean lowConfidence,
      Boolean outdated,
      Boolean notEvaluated,
      ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db ->
                member.rows(
                    db,
                    tenderVendorId,
                    limit,
                    cursor,
                    q,
                    grade,
                    importance,
                    categoryId,
                    reviewState,
                    overridden,
                    flagged,
                    lowConfidence,
                    outdated,
                    notEvaluated))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<EvaluationRow>> getEvaluationRow(
      UUID tenderVendorId, UUID requirementId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> member.row(db, tenderVendorId, requirementId))
        .map(EvaluationController::tagged);
  }

  @Override
  public Mono<ResponseEntity<EvaluationRow>> updateEvaluationRow(
      UUID tenderVendorId,
      UUID requirementId,
      String ifMatch,
      Mono<RowUpdate> rowUpdate,
      ServerWebExchange exchange) {
    int version = ETags.requireIfMatch(exchange.getRequest());
    return rowUpdate
        .flatMap(
            in ->
                tx.asUser(
                    (db, user) ->
                        member.update(db, user, tenderVendorId, requirementId, version, in)))
        .map(EvaluationController::tagged);
  }

  @Override
  public Mono<ResponseEntity<BulkResult>> bulkUpdateEvaluationRows(
      UUID tenderVendorId, Mono<BulkRows> bulkRows, ServerWebExchange exchange) {
    return bulkRows
        .flatMap(in -> tx.asUser((db, user) -> member.bulkAgree(db, user, tenderVendorId, in)))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Void>> sendAiFeedback(
      UUID tenderVendorId,
      UUID requirementId,
      Mono<FeedbackInput> feedbackInput,
      ServerWebExchange exchange) {
    return feedbackInput
        .flatMap(
            in ->
                tx.asUser(
                    (db, user) -> member.feedback(db, user, tenderVendorId, requirementId, in)))
        .then(Mono.just(ResponseEntity.noContent().<Void>build()));
  }

  @Override
  public Mono<ResponseEntity<History>> getEvaluationRowHistory(
      UUID tenderVendorId, UUID requirementId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> member.history(db, tenderVendorId, requirementId))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<PeerRowPage>> listPeerRows(
      UUID tenderVendorId,
      Integer limit,
      String cursor,
      UUID requirementId,
      ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db -> member.peers(db, tenderVendorId, limit, cursor, requirementId))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Flux<Clarification>>> listClarifications(
      UUID tenderVendorId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> member.clarifications(db, tenderVendorId))
        .map(l -> ResponseEntity.ok(Flux.fromIterable(l)));
  }

  @Override
  public Mono<ResponseEntity<EvaluationProgress>> listMyEvaluations(
      UUID tenderId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> member.progress(db, tenderId)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<CrossVendorView>> getCrossVendorView(
      UUID requirementId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> member.crossVendor(db, requirementId))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<MemberEvaluation>> reopenMemberEvaluation(
      UUID memberEvaluationId,
      Mono<ReopenEvaluation> reopenEvaluation,
      ServerWebExchange exchange) {
    return reopenEvaluation
        .flatMap(in -> tx.asUser((db, user) -> member.reopen(db, user, memberEvaluationId, in)))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<MemberEvaluation>> excludeMemberEvaluation(
      UUID memberEvaluationId, Mono<ExcludeMember> excludeMember, ServerWebExchange exchange) {
    return excludeMember
        .flatMap(in -> tx.asUser((db, user) -> member.exclude(db, user, memberEvaluationId, in)))
        .map(ResponseEntity::ok);
  }
}
