package app.tendereval.backend.api;

import app.tendereval.backend.api.model.Job;
import app.tendereval.backend.api.model.JobPage;
import app.tendereval.backend.api.model.JobStatus;
import app.tendereval.backend.api.model.JobType;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.jobs.JobProcessor;
import app.tendereval.backend.jobs.JobsService;
import app.tendereval.backend.messaging.JobControl;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The Jobs group (UX-JOB-01..07). Retry of document processing lives with the document; retry of
 * the AI job types arrives with Phases 3 to 5 and answers 501 until then.
 */
@RestController
public class JobsController implements JobsApi {

  private final RequestTransaction tx;
  private final JobsService service;
  private final JobControl control;
  private final JobProcessor processor;

  public JobsController(
      RequestTransaction tx, JobsService service, JobControl control, JobProcessor processor) {
    this.tx = tx;
    this.service = service;
    this.control = control;
    this.processor = processor;
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
}
