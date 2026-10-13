package app.tendereval.backend.jobs;

import app.tendereval.backend.config.AppProperties;
import app.tendereval.backend.jobs.JobProcessor.JobRow;
import app.tendereval.backend.messaging.JobControl;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The three timers of PI 5.8 and a reconciliation. Several backend instances run them at once;
 * {@code FOR UPDATE SKIP LOCKED} makes each job belong to one instance for one pass.
 */
@Service
public class JobTimers {

  private static final Logger log = LoggerFactory.getLogger(JobTimers.class);
  private static final int BATCH = 20;

  private final IngestDb db;
  private final JobProcessor processor;
  private final JobDispatcher dispatcher;
  private final JobControl control;
  private final AppProperties props;

  public JobTimers(
      IngestDb db,
      JobProcessor processor,
      JobDispatcher dispatcher,
      JobControl control,
      AppProperties props) {
    this.db = db;
    this.processor = processor;
    this.dispatcher = dispatcher;
    this.control = control;
    this.props = props;
  }

  /** One pass of every timer. */
  public Mono<Void> sweep() {
    return republishQueued()
        .then(failSilent())
        .then(failOverCeiling())
        .then(
            processor
                .reconcileVersions()
                .doOnNext(
                    n -> {
                      if (n > 0) {
                        log.info("versions reconciled count={}", n);
                      }
                    }))
        .then();
  }

  /** A queued job whose upload event produced no status within the timeout gets its command. */
  Mono<Void> republishQueued() {
    return db.inTransaction(
            c ->
                c.sql(
                        "SELECT j.id FROM jobs j JOIN document_versions v ON v.id = j.target_id"
                            + " WHERE j.job_type = 'document_processing' AND j.status = 'queued'"
                            + " AND v.status = 'processing'"
                            + " AND greatest(j.queued_at, coalesce(j.command_published_at,"
                            + " j.queued_at)) < now() - (CAST(:s AS double precision) * interval '1 second')"
                            + " ORDER BY j.queued_at LIMIT "
                            + BATCH
                            + " FOR UPDATE OF j SKIP LOCKED")
                    .bind("s", (double) props.watchdogQueuedTimeoutSeconds())
                    .map((r, m) -> r.get("id", UUID.class))
                    .all()
                    .collectList())
        .flatMapMany(Flux::fromIterable)
        // publish() moves command_published_at, so the same job is not picked again at once
        .concatMap(dispatcher::publish)
        .then();
  }

  Mono<Void> failSilent() {
    return failMatching(
        "j.status = 'running' AND coalesce(j.last_status_at, j.started_at, j.queued_at)"
            + " < now() - (CAST(:s AS double precision) * interval '1 second')",
        (double) props.watchdogSilenceTimeoutSeconds(),
        "The job stopped reporting progress");
  }

  Mono<Void> failOverCeiling() {
    Mono<Void> all = Mono.empty();
    for (String type :
        List.of(
            "document_processing", "requirement_extraction", "screening_precheck", "evaluation")) {
      all =
          all.then(
              failMatching(
                  "j.job_type = '"
                      + type
                      + "' AND j.status IN ('queued', 'running') AND j.queued_at"
                      + " < now() - (CAST(:s AS double precision) * interval '1 minute')",
                  (double) props.jobMaxMinutes(type),
                  "The job ran longer than allowed"));
    }
    return all;
  }

  private Mono<Void> failMatching(String predicate, double seconds, String message) {
    return db.inTransaction(
            c ->
                c.sql(
                        "SELECT j.id, j.tender_id, j.job_type, j.target_type, j.target_id, j.status,"
                            + " j.last_status_seq FROM jobs j WHERE "
                            + predicate
                            + " ORDER BY j.queued_at LIMIT "
                            + BATCH
                            + " FOR UPDATE OF j SKIP LOCKED")
                    .bind("s", seconds)
                    .map(
                        (r, m) ->
                            new JobRow(
                                r.get("id", UUID.class),
                                r.get("tender_id", UUID.class),
                                r.get("job_type", String.class),
                                r.get("target_type", String.class),
                                r.get("target_id", UUID.class),
                                r.get("status", String.class),
                                r.get("last_status_seq", Long.class)))
                    .all()
                    .collectList()
                    .flatMap(
                        rows ->
                            Flux.fromIterable(rows)
                                .concatMap(
                                    row -> processor.failOpenJob(c, row, "timeout", message, null))
                                .then(Mono.just(rows))))
        .flatMapMany(Flux::fromIterable)
        // after the commit: stop the worker and tell the browsers
        .concatMap(
            row ->
                control
                    .requestCancel(row.id())
                    .then(processor.pushAfterTimer(row, "failed"))
                    .doOnSuccess(
                        v -> log.warn("job timed out id={} type={}", row.id(), row.jobType())))
        .then();
  }

  /**
   * Keeps the audit partitions ahead (UX-AUD-02): called by the request pool at start and daily.
   */
  static DatabaseClient.GenericExecuteSpec partitions(DatabaseClient db) {
    return db.sql("SELECT ensure_monthly_partitions(24) AS created");
  }
}
