package app.tendereval.backend.api;

import app.tendereval.backend.api.model.ExtractionCoverage;
import app.tendereval.backend.api.model.ExtractionRequest;
import app.tendereval.backend.api.model.Job;
import app.tendereval.backend.common.Idempotency;
import app.tendereval.backend.common.RateLimiter;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.extraction.ExtractionService;
import app.tendereval.backend.jobs.JobDispatcher;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Requirement extraction (UX-EXT-02..07): start a job for a selection and show the coverage. */
@RestController
public class ExtractionController implements ExtractionApi {

  private final RequestTransaction tx;
  private final ExtractionService service;
  private final JobDispatcher dispatcher;
  private final Idempotency idempotency;
  private final RateLimiter limiter;

  public ExtractionController(
      RequestTransaction tx,
      ExtractionService service,
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
  public Mono<ResponseEntity<Job>> startExtraction(
      UUID tenderId,
      Mono<ExtractionRequest> extractionRequest,
      String idempotencyKey,
      ServerWebExchange exchange) {
    String key = Idempotency.requireKey(idempotencyKey);
    return extractionRequest
        .flatMap(
            in ->
                tx.asUser(
                    (db, user) -> {
                      limiter.check(user.id(), RateLimiter.Bucket.JOB_STARTS);
                      return idempotency.run(
                          db,
                          user.id(),
                          key,
                          "POST /tenders/{id}/extractions:" + tenderId,
                          Idempotency.hash(
                              "startExtraction", tenderId.toString(), String.valueOf(in)),
                          Job.class,
                          () ->
                              service
                                  .start(db, user, tenderId, in)
                                  .map(
                                      j ->
                                          ResponseEntity.status(HttpStatus.ACCEPTED)
                                              .location(URI.create("/api/v1/jobs/" + j.getId()))
                                              .body(j)));
                    }))
        // The transaction has committed here: only now is the command published (rule 4).
        .flatMap(
            r ->
                r.getBody() != null && !"true".equals(r.getHeaders().getFirst("Idempotent-Replay"))
                    ? dispatcher.publishExtraction(r.getBody().getId()).thenReturn(r)
                    : Mono.just(r));
  }

  @Override
  public Mono<ResponseEntity<ExtractionCoverage>> getExtractionCoverage(
      UUID tenderId, UUID documentVersionId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.coverage(db, tenderId, documentVersionId))
        .map(ResponseEntity::ok);
  }
}
