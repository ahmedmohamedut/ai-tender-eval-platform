package app.tendereval.backend.api;

import app.tendereval.backend.api.model.Job;
import app.tendereval.backend.api.model.JobPage;
import app.tendereval.backend.api.model.JobStatus;
import app.tendereval.backend.api.model.JobType;
import app.tendereval.backend.common.Idempotency;
import app.tendereval.backend.common.RateLimiter;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.extraction.ExtractionService;
import app.tendereval.backend.jobs.JobDispatcher;
import app.tendereval.backend.jobs.JobProcessor;
import app.tendereval.backend.jobs.JobsService;
import app.tendereval.backend.messaging.JobControl;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The Jobs group (UX-JOB-01..07). Retry of document processing lives with the document; extraction
 * retry is here, the other AI job types arrive with Phases 4 and 5 and answer 409.
 */
@RestController
public class JobsController implements JobsApi {

  private final RequestTransaction tx;
  private final JobsService service;
  private final JobControl control;
  private final JobProcessor processor;
  private final ExtractionService extraction;
  private final JobDispatcher dispatcher;
  private final Idempotency idempotency;
  private final RateLimiter limiter;

  public JobsController(
      RequestTransaction tx,
      JobsService service,
      JobControl control,
      JobProcessor processor,
      ExtractionService extraction,
      JobDispatcher dispatcher,
      Idempotency idempotency,
      RateLimiter limiter) {
    this.tx = tx;
    this.service = service;
    this.control = control;
    this.processor = processor;
    this.extraction = extraction;
    this.dispatcher = dispatcher;
    this.idempotency = idempotency;
    this.limiter = limiter;
  }

  @Override
  public Mono<ResponseEntity<JobPage>> listJobs(
      Integer limit,
      String cursor,
      UUID tenderId,
      JobType jobType,
      JobStatus status,
      Boolean active,
      ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db -> service.list(db, limit, cursor, tenderId, jobType, status, active))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Job>> getJob(UUID jobId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.get(db, jobId)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Job>> cancelJob(UUID jobId, ServerWebExchange exchange) {
    return tx.asUser((db, user) -> service.cancel(db, user, jobId))
        // After the commit: the worker is told to stop, and a processing version is settled.
        .flatMap(
            job -> control.requestCancel(jobId).then(processor.reconcileVersions()).thenReturn(job))
        .map(ResponseEntity::ok);
  }

  /** Retry of an extraction job; document processing is retried from the document. */
  @Override
  public Mono<ResponseEntity<Job>> retryJob(
      UUID jobId, String idempotencyKey, ServerWebExchange exchange) {
    String key = Idempotency.requireKey(idempotencyKey);
    return tx.asUser(
            (db, user) -> {
              limiter.check(user.id(), RateLimiter.Bucket.JOB_STARTS);
              return idempotency.run(
                  db,
                  user.id(),
                  key,
                  "POST /jobs/{id}/retry:" + jobId,
                  Idempotency.hash("retryJob", jobId.toString()),
                  Job.class,
                  () ->
                      extraction
                          .retry(db, user, jobId)
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
                    ? dispatcher.publishExtraction(r.getBody().getId()).thenReturn(r)
                    : Mono.just(r));
  }
}
