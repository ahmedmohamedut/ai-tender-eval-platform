package app.tendereval.backend.api;

import app.tendereval.backend.api.model.Job;
import app.tendereval.backend.api.model.RerunRequest;
import app.tendereval.backend.common.Idempotency;
import app.tendereval.backend.common.RateLimiter;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.evaluation.EvaluationService;
import app.tendereval.backend.jobs.JobDispatcher;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The Evaluation group (UX-EVA). The re-run is here; the member's own rows arrive with the member
 * evaluation milestone and answer 501 until then.
 */
@RestController
public class EvaluationController implements EvaluationApi {

  private final RequestTransaction tx;
  private final EvaluationService service;
  private final JobDispatcher dispatcher;
  private final Idempotency idempotency;
  private final RateLimiter limiter;

  public EvaluationController(
      RequestTransaction tx,
      EvaluationService service,
      JobDispatcher dispatcher,
      Idempotency idempotency,
      RateLimiter limiter) {
    this.tx = tx;
    this.service = service;
    this.dispatcher = dispatcher;
    this.idempotency = idempotency;
    this.limiter = limiter;
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
}
