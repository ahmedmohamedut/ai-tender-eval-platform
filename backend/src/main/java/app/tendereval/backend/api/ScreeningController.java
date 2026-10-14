package app.tendereval.backend.api;

import app.tendereval.backend.api.model.ChecklistItem;
import app.tendereval.backend.api.model.ChecklistUpdate;
import app.tendereval.backend.api.model.ChecksInput;
import app.tendereval.backend.api.model.Job;
import app.tendereval.backend.api.model.RecommendationInput;
import app.tendereval.backend.api.model.ReinstateInput;
import app.tendereval.backend.api.model.ScreeningDecisionInput;
import app.tendereval.backend.api.model.ScreeningState;
import app.tendereval.backend.common.Idempotency;
import app.tendereval.backend.common.RateLimiter;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.jobs.JobDispatcher;
import app.tendereval.backend.screening.ScreeningService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Screening of proposals (UX-SCR-01..07). The pre-check job start is in the same group; the
 * screening outcomes of the report arrive with Phase 5 and answer 501.
 */
@RestController
public class ScreeningController implements ScreeningApi {

  private final RequestTransaction tx;
  private final ScreeningService service;
  private final Idempotency idempotency;
  private final RateLimiter limiter;
  private final JobDispatcher dispatcher;

  public ScreeningController(
      RequestTransaction tx,
      ScreeningService service,
      Idempotency idempotency,
      RateLimiter limiter,
      JobDispatcher dispatcher) {
    this.tx = tx;
    this.service = service;
    this.idempotency = idempotency;
    this.limiter = limiter;
    this.dispatcher = dispatcher;
  }

  @Override
  public Mono<ResponseEntity<Job>> startScreeningPrecheck(
      UUID tenderVendorId, String idempotencyKey, ServerWebExchange exchange) {
    String key = Idempotency.requireKey(idempotencyKey);
    return tx.asUser(
            (db, user) -> {
              limiter.check(user.id(), RateLimiter.Bucket.JOB_STARTS);
              return idempotency.run(
                  db,
                  user.id(),
                  key,
                  "POST /tender-vendors/{id}/screening/precheck:" + tenderVendorId,
                  Idempotency.hash("startScreeningPrecheck", tenderVendorId.toString()),
                  Job.class,
                  () ->
                      service
                          .startPrecheck(db, user, tenderVendorId)
                          .map(
                              j ->
                                  ResponseEntity.status(HttpStatus.ACCEPTED)
                                      .location(URI.create("/api/v1/jobs/" + j.getId()))
                                      .body(j)));
            })
        // After the commit (rule 4): only now is the command published.
        .flatMap(
            r ->
                r.getBody() != null && !"true".equals(r.getHeaders().getFirst("Idempotent-Replay"))
                    ? dispatcher.publishPrecheck(r.getBody().getId()).thenReturn(r)
                    : Mono.just(r));
  }

  @Override
  public Mono<ResponseEntity<ScreeningState>> getScreening(
      UUID tenderVendorId, ServerWebExchange exchange) {
    return tx.asUser((db, user) -> service.state(db, user, tenderVendorId)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<ScreeningState>> saveScreeningChecks(
      UUID tenderVendorId, Mono<ChecksInput> checksInput, ServerWebExchange exchange) {
    return checksInput
        .flatMap(in -> tx.asUser((db, user) -> service.saveChecks(db, user, tenderVendorId, in)))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<ScreeningState>> saveRecommendation(
      UUID tenderVendorId,
      Mono<RecommendationInput> recommendationInput,
      ServerWebExchange exchange) {
    return recommendationInput
        .flatMap(
            in -> tx.asUser((db, user) -> service.saveRecommendation(db, user, tenderVendorId, in)))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<ScreeningState>> confirmScreeningDecision(
      UUID tenderVendorId,
      Mono<ScreeningDecisionInput> screeningDecisionInput,
      String idempotencyKey,
      ServerWebExchange exchange) {
    String key = Idempotency.requireKey(idempotencyKey);
    return screeningDecisionInput.flatMap(
        in ->
            tx.asUser(
                (db, user) ->
                    idempotency.run(
                        db,
                        user.id(),
                        key,
                        "POST /tender-vendors/{id}/screening/decision:" + tenderVendorId,
                        Idempotency.hash(
                            "confirmScreeningDecision",
                            tenderVendorId.toString(),
                            String.valueOf(in)),
                        ScreeningState.class,
                        () ->
                            service.decide(db, user, tenderVendorId, in).map(ResponseEntity::ok))));
  }

  @Override
  public Mono<ResponseEntity<ScreeningState>> reinstateVendor(
      UUID tenderVendorId, Mono<ReinstateInput> reinstateInput, ServerWebExchange exchange) {
    return reinstateInput
        .flatMap(in -> tx.asUser((db, user) -> service.reinstate(db, user, tenderVendorId, in)))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Flux<ChecklistItem>>> getScreeningChecklist(
      UUID tenderId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.checklistOf(db, tenderId))
        .map(l -> ResponseEntity.ok(Flux.fromIterable(l)));
  }

  @Override
  public Mono<ResponseEntity<Flux<ChecklistItem>>> setScreeningChecklist(
      UUID tenderId, Mono<ChecklistUpdate> checklistUpdate, ServerWebExchange exchange) {
    return checklistUpdate
        .flatMap(in -> tx.asUser((db, user) -> service.setChecklist(db, user, tenderId, in)))
        .map(l -> ResponseEntity.ok(Flux.fromIterable(l)));
  }
}
