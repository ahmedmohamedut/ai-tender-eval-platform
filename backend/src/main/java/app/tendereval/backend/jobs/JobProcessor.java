package app.tendereval.backend.jobs;

import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.messaging.PushPublisher;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Applies status messages to the jobs table (PI 5.5, 5.7). It runs as {@code tender_ai_ingest}.
 * Delivery is at least once and unordered: a message for a finished job is dropped, a lower
 * sequence number never replaces a higher one, and a completion waits until the parsed rows are
 * stored and their counts match.
 */
@Service
public class JobProcessor {

  private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

  static final Set<String> TERMINAL =
      Set.of("completed", "completed_with_warnings", "failed", "cancelled");
  private static final Set<String> VERSION_REASONS =
      Set.of(
          "password_protected",
          "no_selectable_text",
          "unsupported_format",
          "source_unavailable",
          "index_unavailable",
          "timeout");
  private static final Set<String> JOB_CODES =
      Set.of(
          "password_protected",
          "no_selectable_text",
          "unsupported_format",
          "source_unavailable",
          "index_unavailable",
          "llm_unavailable",
          "model_output_unusable",
          "no_content_in_scope",
          "timeout",
          "other");
  static final Duration COUNTS_RETRY = Duration.ofSeconds(5);

  /** The locked job row. */
  record JobRow(
      UUID id,
      UUID tenderId,
      String jobType,
      String targetType,
      UUID targetId,
      String status,
      long lastSeq) {}

  private final IngestDb db;
  private final AuditService audit;
  private final PushPublisher push;

  public JobProcessor(IngestDb db, AuditService audit, PushPublisher push) {
    this.db = db;
    this.audit = audit;
    this.push = push;
  }

  /** Marks an open job failed, for a message that could not be stored after every delivery. */
  public Mono<Void> failById(UUID jobId, String code, String message) {
    return db.inTransaction(
            c ->
                c.sql(
                        "SELECT id, tender_id, job_type, target_type, target_id, status,"
                            + " last_status_seq FROM jobs WHERE id = :id FOR UPDATE")
                    .bind("id", jobId)
                    .map(
                        (r, md) ->
                            new JobRow(
                                r.get("id", UUID.class),
                                r.get("tender_id", UUID.class),
                                r.get("job_type", String.class),
                                r.get("target_type", String.class),
                                r.get("target_id", UUID.class),
                                r.get("status", String.class),
                                r.get("last_status_seq", Long.class)))
                    .one()
                    .filter(job -> !TERMINAL.contains(job.status()))
                    .flatMap(job -> failOpenJob(c, job, code, message, null).thenReturn(job)))
        .flatMap(job -> pushAfterTimer(job, "failed"));
  }

  /**
   * A version still processing whose job has failed or been cancelled (a cancel is written by the
   * request role, which cannot change a version) is failed here.
   */
  public Mono<Long> reconcileVersions() {
    return db.inTransaction(
        c ->
            c.sql(
                    "UPDATE document_versions v SET status = 'failed', failure_reason = coalesce("
                        + "(SELECT CASE WHEN j.failure_code IN ('password_protected',"
                        + " 'no_selectable_text', 'unsupported_format', 'source_unavailable',"
                        + " 'index_unavailable', 'timeout') THEN j.failure_code ELSE 'other' END"
                        + " FROM jobs j WHERE j.target_id = v.id AND j.job_type = 'document_processing'"
                        + " ORDER BY j.queued_at DESC LIMIT 1), 'other')"
                        + " WHERE v.status = 'processing' AND v.deleted_at IS NULL"
                        + " AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.target_id = v.id"
                        + " AND j.job_type = 'document_processing' AND j.status IN ('queued',"
                        + " 'running', 'completed', 'completed_with_warnings'))"
                        + " AND EXISTS (SELECT 1 FROM jobs j WHERE j.target_id = v.id"
                        + " AND j.job_type = 'document_processing'"
                        + " AND j.status IN ('failed', 'cancelled'))")
                .fetch()
                .rowsUpdated()
                // A completed job whose version was still uploading when the status arrived (the
                // worker is faster than the upload's last write): ready now.
                .flatMap(
                    failed ->
                        c.sql(
                                "UPDATE document_versions v SET status = 'ready', failure_reason = NULL,"
                                    + " page_count = (SELECT count(*) FROM document_pages p"
                                    + " WHERE p.document_version_id = v.id)"
                                    + " WHERE v.status = 'processing' AND v.deleted_at IS NULL"
                                    + " AND EXISTS (SELECT 1 FROM jobs j WHERE j.target_id = v.id"
                                    + " AND j.job_type = 'document_processing'"
                                    + " AND j.status IN ('completed', 'completed_with_warnings'))")
                            .fetch()
                            .rowsUpdated()
                            .map(ready -> failed + ready)));
  }

  // ---- status messages
  // ---------------------------------------------------------------------------

  public Mono<Outcome> applyStatus(StatusMessage m) {
    return db.inTransaction(c -> locate(c, m).flatMap(row -> applyLocked(c, row, m)))
        .defaultIfEmpty(Outcome.deadLetter("no job for this status message"))
        .flatMap(
            outcome ->
                outcome instanceof Outcome.Ack ack && "applied".equals(ack.note())
                    ? pushJob(m.tenderId(), m.jobId(), m.targetId(), m.jobType(), m.state())
                        .thenReturn(outcome)
                    : Mono.just(outcome));
  }

  private Mono<JobRow> locate(DatabaseClient c, StatusMessage m) {
    String where =
        m.jobId() != null
            ? "id = :id"
            : "target_id = :target AND job_type = :jt AND status IN ('queued', 'running')";
    DatabaseClient.GenericExecuteSpec spec =
        c.sql(
            "SELECT id, tender_id, job_type, target_type, target_id, status, last_status_seq"
                + " FROM jobs WHERE "
                + where
                + " ORDER BY queued_at DESC LIMIT 1 FOR UPDATE");
    spec =
        m.jobId() != null
            ? spec.bind("id", m.jobId())
            : spec.bind("target", m.targetId()).bind("jt", m.jobType());
    return spec.map(
            (r, md) ->
                new JobRow(
                    r.get("id", UUID.class),
                    r.get("tender_id", UUID.class),
                    r.get("job_type", String.class),
                    r.get("target_type", String.class),
                    r.get("target_id", UUID.class),
                    r.get("status", String.class),
                    r.get("last_status_seq", Long.class)))
        .one();
  }

  private Mono<Outcome> applyLocked(DatabaseClient c, JobRow job, StatusMessage m) {
    if (!job.tenderId().equals(m.tenderId())
        || !job.jobType().equals(m.jobType())
        || !job.targetId().equals(m.targetId())) {
      return Mono.just(Outcome.deadLetter("status does not match the job"));
    }
    if (TERMINAL.contains(job.status())) {
      return Mono.just(Outcome.ack("job already finished"));
    }
    if (!m.terminal() && m.seq() <= job.lastSeq()) {
      return Mono.just(Outcome.ack("older status"));
    }
    return switch (m.state()) {
      case "running" -> running(c, job, m);
      case "failed" -> failed(c, job, m);
      case "cancelled" -> Mono.just(Outcome.ack("cancel is recorded by the cancel request"));
      default -> completed(c, job, m);
    };
  }

  private Mono<Outcome> running(DatabaseClient c, JobRow job, StatusMessage m) {
    Mono<Void> start =
        "queued".equals(job.status())
            ? c.sql(
                    "UPDATE jobs SET status = 'running', started_at = coalesce(started_at, now())"
                        + " WHERE id = :id AND status = 'queued'")
                .bind("id", job.id())
                .fetch()
                .rowsUpdated()
                .then()
            : Mono.empty();
    return start
        .then(ensureSteps(c, job.id(), m.steps()))
        .then(ensureRun(c, job, m))
        .then(markSteps(c, job.id(), m.step(), false))
        .then(updateProgress(c, job.id(), m))
        .then(
            "queued".equals(job.status())
                ? event(c, job.id(), "info", null, "The job started", "state:running")
                : Mono.empty())
        .then(
            m.step() != null
                ? event(
                    c, job.id(), "info", m.step(), "Started step " + m.step(), "step:" + m.step())
                : Mono.empty())
        .then(
            "queued".equals(job.status())
                ? audit
                    .recordSystem(
                        c,
                        AuditEntry.of("job_status_changed", "job", job.id())
                            .tender(job.tenderId())
                            .changed(Map.of("status", "queued"), Map.of("status", "running"))
                            .build())
                    .thenReturn(Outcome.ack("applied"))
                : Mono.just(Outcome.ack("applied")));
  }

  /** The first message of an AI job carries the run record (PI 5.5): one ai_runs row per job. */
  private Mono<Void> ensureRun(DatabaseClient c, JobRow job, StatusMessage m) {
    if (m.modelName() == null || m.promptVersion() == null) {
      return Mono.empty();
    }
    String type =
        switch (job.jobType()) {
          case "requirement_extraction" -> "extraction";
          case "screening_precheck" -> "precheck";
          default -> "evaluation";
        };
    DatabaseClient.GenericExecuteSpec spec =
        c.sql(
                "INSERT INTO ai_runs (job_id, run_type, model_name, model_version, prompt_version,"
                    + " input_scope) VALUES (:j, :t, :m, :v, :p, CAST(:s AS jsonb))"
                    + " ON CONFLICT (job_id) DO NOTHING")
            .bind("j", job.id())
            .bind("t", type)
            .bind("m", m.modelName())
            .bind("p", m.promptVersion())
            .bind("s", m.inputScopeJson() == null ? "{}" : m.inputScopeJson());
    spec =
        m.modelVersion() == null
            ? spec.bindNull("v", String.class)
            : spec.bind("v", m.modelVersion());
    return spec.fetch().rowsUpdated().then();
  }

  private Mono<Void> finishRun(DatabaseClient c, UUID jobId, String outcome, int warnings) {
    return c.sql(
            "UPDATE ai_runs SET finished_at = now(), outcome = :o, warning_count = :w"
                + " WHERE job_id = :j AND finished_at IS NULL")
        .bind("o", outcome)
        .bind("w", warnings)
        .bind("j", jobId)
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<Void> updateProgress(DatabaseClient c, UUID id, StatusMessage m) {
    DatabaseClient.GenericExecuteSpec spec =
        c.sql(
                "UPDATE jobs SET current_step = coalesce(:step, current_step),"
                    + " progress_percent = coalesce(:progress, progress_percent),"
                    + " last_status_seq = greatest(last_status_seq, :seq), last_status_at = now(),"
                    + " warnings = coalesce(CAST(:warnings AS jsonb), warnings) WHERE id = :id")
            .bind("id", id)
            .bind("seq", m.seq());
    spec = nullable(spec, "step", m.step(), String.class);
    spec = nullable(spec, "progress", m.progressPercent(), Integer.class);
    spec = nullable(spec, "warnings", m.warningsJson(), String.class);
    return spec.fetch().rowsUpdated().then();
  }

  private Mono<Void> ensureSteps(DatabaseClient c, UUID jobId, List<String> steps) {
    if (steps == null || steps.isEmpty()) {
      return Mono.empty();
    }
    return c.sql("SELECT count(*) AS n FROM job_steps WHERE job_id = :id")
        .bind("id", jobId)
        .map((r, md) -> r.get("n", Long.class))
        .one()
        .flatMap(
            n -> {
              if (n > 0) {
                return Mono.<Void>empty();
              }
              Mono<Void> all = Mono.empty();
              for (int i = 0; i < steps.size(); i++) {
                final int no = i + 1;
                final String name = steps.get(i);
                all =
                    all.then(
                        c.sql(
                                "INSERT INTO job_steps (job_id, step_no, name) VALUES (:j, :n, :name)"
                                    + " ON CONFLICT (job_id, step_no) DO NOTHING")
                            .bind("j", jobId)
                            .bind("n", no)
                            .bind("name", name)
                            .fetch()
                            .rowsUpdated()
                            .then());
              }
              return all;
            });
  }

  /** Steps before the current one are completed, the current one runs (or all complete). */
  private Mono<Void> markSteps(DatabaseClient c, UUID jobId, String current, boolean all) {
    if (!all && current == null) {
      return Mono.empty();
    }
    String sql =
        all
            ? "UPDATE job_steps SET status = 'completed', started_at = coalesce(started_at, now()),"
                + " finished_at = coalesce(finished_at, now()) WHERE job_id = :id"
                + " AND status <> 'completed'"
            : "UPDATE job_steps s SET status = CASE WHEN s.step_no < cur.step_no THEN 'completed'"
                + " ELSE 'running' END, started_at = coalesce(s.started_at, now()),"
                + " finished_at = CASE WHEN s.step_no < cur.step_no THEN coalesce(s.finished_at, now())"
                + " ELSE s.finished_at END"
                + " FROM (SELECT step_no FROM job_steps WHERE job_id = :id AND name = :cur) cur"
                + " WHERE s.job_id = :id AND s.step_no <= cur.step_no AND s.status <> 'completed'";
    DatabaseClient.GenericExecuteSpec spec = c.sql(sql).bind("id", jobId);
    if (!all) {
      spec = spec.bind("cur", current);
    }
    return spec.fetch().rowsUpdated().then();
  }

  private Mono<Outcome> completed(DatabaseClient c, JobRow job, StatusMessage m) {
    Mono<Boolean> stored =
        "document_processing".equals(job.jobType())
            ? parsedRowsMatch(c, job.targetId(), m)
            : "requirement_extraction".equals(job.jobType())
                ? requirementsStored(c, job.id(), m)
                : "screening_precheck".equals(job.jobType())
                    ? prechecksStored(c, job.id(), m)
                    : "evaluation".equals(job.jobType())
                        ? gradesStored(c, job.id(), m)
                        : Mono.just(true);
    return stored.flatMap(
        ok -> {
          if (!ok) {
            return Mono.just(Outcome.retry(COUNTS_RETRY, "result batches not stored yet"));
          }
          return toRunning(c, job)
              .then(markSteps(c, job.id(), null, true))
              .then(finishJob(c, job.id(), m.state(), null, null, m))
              .then(
                  event(
                      c,
                      job.id(),
                      "completed_with_warnings".equals(m.state()) ? "warning" : "info",
                      null,
                      finishedText(m),
                      "state:" + m.state()))
              .then(
                  "document_processing".equals(job.jobType()) ? versionReady(c, job) : Mono.empty())
              .then(
                  "evaluation".equals(job.jobType()) ? syncMemberEvaluations(c, job) : Mono.empty())
              .then(
                  "completed_with_warnings".equals(m.state())
                      ? finishRun(c, job.id(), "partial", warningCount(m))
                      : finishRun(c, job.id(), "succeeded", 0))
              .then(
                  audit.recordSystem(
                      c,
                      AuditEntry.of("job_status_changed", "job", job.id())
                          .tender(job.tenderId())
                          .changed(Map.of("status", "running"), Map.of("status", m.state()))
                          .build()))
              .thenReturn(Outcome.ack("applied"));
        });
  }

  private static int warningCount(StatusMessage m) {
    return m.warningsJson() == null ? 0 : m.warningsJson().split("\\{").length - 1;
  }

  /** Extraction completes when the stored requirements of the run equal what the worker counted. */
  private Mono<Boolean> requirementsStored(DatabaseClient c, UUID jobId, StatusMessage m) {
    if (m.resultParts() == null || m.counts() == null || !m.counts().containsKey("requirements")) {
      return Mono.just(false);
    }
    if (m.counts().get("requirements") == 0) {
      return Mono.just(true);
    }
    return c.sql(
            "SELECT count(*) AS n FROM requirements r JOIN ai_runs a ON a.id = r.extraction_ai_run_id"
                + " WHERE a.job_id = :j")
        .bind("j", jobId)
        .map((r, md) -> r.get("n", Long.class) == m.counts().get("requirements").longValue())
        .one();
  }

  /** A completed evaluation creates (or re-points) every member's evaluation and rows. */
  private Mono<Void> syncMemberEvaluations(DatabaseClient c, JobRow job) {
    // not for a version that a newer upload has already replaced
    return c.sql(
            "SELECT (ingest_sync_member_evaluations(p.id)).* FROM proposal_versions p"
                + " WHERE p.id = :pv AND p.status <> 'superseded'")
        .bind("pv", job.targetId())
        .fetch()
        .all()
        .then();
  }

  /** An evaluation completes when the stored grades of the run equal what the worker counted. */
  private Mono<Boolean> gradesStored(DatabaseClient c, UUID jobId, StatusMessage m) {
    if (m.resultParts() == null || m.counts() == null || !m.counts().containsKey("grades")) {
      return Mono.just(false);
    }
    if (m.counts().get("grades") == 0) {
      return Mono.just(true);
    }
    return c.sql(
            "SELECT count(*) AS n FROM ai_grades g JOIN ai_runs a ON a.id = g.ai_run_id"
                + " WHERE a.job_id = :j")
        .bind("j", jobId)
        .map((r, md) -> r.get("n", Long.class) == m.counts().get("grades").longValue())
        .one();
  }

  /** A pre-check completes when the stored findings of the run equal what the worker counted. */
  private Mono<Boolean> prechecksStored(DatabaseClient c, UUID jobId, StatusMessage m) {
    if (m.resultParts() == null || m.counts() == null || !m.counts().containsKey("prechecks")) {
      return Mono.just(false);
    }
    if (m.counts().get("prechecks") == 0) {
      return Mono.just(true);
    }
    return c.sql(
            "SELECT count(*) AS n FROM screening_prechecks p JOIN ai_runs a ON a.id = p.ai_run_id"
                + " WHERE a.job_id = :j")
        .bind("j", jobId)
        .map((r, md) -> r.get("n", Long.class) == m.counts().get("prechecks").longValue())
        .one();
  }

  private Mono<Boolean> parsedRowsMatch(DatabaseClient c, UUID versionId, StatusMessage m) {
    if (m.resultParts() == null
        || m.resultParts() < 1
        || m.counts() == null
        || !m.counts().containsKey("pages")
        || !m.counts().containsKey("passages")) {
      return Mono.just(false);
    }
    return c.sql(
            "SELECT (SELECT count(*) FROM document_pages WHERE document_version_id = :v) AS pages,"
                + " (SELECT count(*) FROM document_sections WHERE document_version_id = :v) AS sections,"
                + " (SELECT count(*) FROM passages WHERE document_version_id = :v) AS passages")
        .bind("v", versionId)
        .map(
            (r, md) ->
                r.get("pages", Long.class) == m.counts().get("pages").longValue()
                    && r.get("passages", Long.class) == m.counts().get("passages").longValue()
                    && r.get("sections", Long.class)
                        == m.counts().getOrDefault("sections", 0).longValue())
        .one();
  }

  private Mono<Void> versionReady(DatabaseClient c, JobRow job) {
    return c.sql(
            "UPDATE document_versions SET status = 'ready', failure_reason = NULL,"
                + " page_count = (SELECT count(*) FROM document_pages WHERE document_version_id = :v)"
                + " WHERE id = :v AND status = 'processing'")
        .bind("v", job.targetId())
        .fetch()
        .rowsUpdated()
        .then(
            c.sql(
                    "UPDATE document_versions o SET superseded_at = now()"
                        + " FROM document_versions n WHERE n.id = :v AND o.document_id = n.document_id"
                        + " AND o.version_no < n.version_no AND o.superseded_at IS NULL"
                        + " AND n.status = 'ready'")
                .bind("v", job.targetId())
                .fetch()
                .rowsUpdated()
                .then());
  }

  private Mono<Outcome> failed(DatabaseClient c, JobRow job, StatusMessage m) {
    String code =
        m.failureCode() != null && JOB_CODES.contains(m.failureCode()) ? m.failureCode() : "other";
    return failOpenJob(c, job, code, m.failureMessage(), m).thenReturn(Outcome.ack("applied"));
  }

  /** Fails a queued or running job, and its document version while it is still processing. */
  Mono<Void> failOpenJob(
      DatabaseClient c, JobRow job, String code, String message, StatusMessage m) {
    return toRunning(c, job)
        .then(failedEvent(c, job.id(), code))
        .then(markFailedStep(c, job.id()))
        .then(finishRun(c, job.id(), "failed", 0))
        .then(finishJob(c, job.id(), "failed", code, message, m))
        .then(
            "document_processing".equals(job.jobType())
                ? c.sql(
                        "UPDATE document_versions SET status = 'failed', failure_reason = :r"
                            + " WHERE id = :v AND status = 'processing'")
                    .bind("v", job.targetId())
                    .bind("r", VERSION_REASONS.contains(code) ? code : "other")
                    .fetch()
                    .rowsUpdated()
                    .then()
                : Mono.empty())
        .then(
            audit.recordSystem(
                c,
                AuditEntry.of("job_status_changed", "job", job.id())
                    .tender(job.tenderId())
                    .changed(
                        Map.of("status", job.status()),
                        new LinkedHashMap<>(Map.of("status", "failed", "failure_code", code)))
                    .build()));
  }

  /** One line of the job's activity log; the same fact is stored once (delivery is repeated). */
  private Mono<Void> event(
      DatabaseClient c, UUID jobId, String level, String step, String message, String dedupKey) {
    DatabaseClient.GenericExecuteSpec spec =
        c.sql(
                "INSERT INTO job_events (job_id, level, step, message, dedup_key)"
                    + " VALUES (:j, :l, :s, :m, :k) ON CONFLICT (job_id, dedup_key) DO NOTHING")
            .bind("j", jobId)
            .bind("l", level)
            .bind("m", message)
            .bind("k", dedupKey);
    spec = nullable(spec, "s", step, String.class);
    return spec.fetch().rowsUpdated().then();
  }

  /** The failure line names the step that was running, and uses the plain text of the code. */
  private Mono<Void> failedEvent(DatabaseClient c, UUID jobId, String code) {
    return c.sql(
            "INSERT INTO job_events (job_id, level, step, message, dedup_key)"
                + " VALUES (:j, 'error', (SELECT name FROM job_steps WHERE job_id = :j"
                + " AND status = 'running' ORDER BY step_no LIMIT 1), :m, 'state:failed')"
                + " ON CONFLICT (job_id, dedup_key) DO NOTHING")
        .bind("j", jobId)
        .bind("m", "The job failed: " + JobsService.message(code, null))
        .fetch()
        .rowsUpdated()
        .then();
  }

  /** Counts have fixed names and are numbers; nothing from a document goes in. */
  private static String finishedText(StatusMessage m) {
    int warnings = warningCount(m);
    StringBuilder t =
        new StringBuilder(
            warnings > 0
                ? "The job finished with " + warnings + (warnings == 1 ? " warning" : " warnings")
                : "The job finished");
    if (m.counts() != null && !m.counts().isEmpty()) {
      t.append(". Result: ");
      t.append(
          new java.util.TreeMap<>(m.counts())
              .entrySet().stream()
                  .map(e -> e.getKey() + " " + e.getValue())
                  .collect(java.util.stream.Collectors.joining(", ")));
    }
    return t.toString();
  }

  private Mono<Void> markFailedStep(DatabaseClient c, UUID jobId) {
    return c.sql(
            "UPDATE job_steps SET status = 'failed', finished_at = now()"
                + " WHERE job_id = :id AND status = 'running'")
        .bind("id", jobId)
        .fetch()
        .rowsUpdated()
        .then();
  }

  /** A queued job passes through running, because queued to a finished state is not allowed. */
  private Mono<Void> toRunning(DatabaseClient c, JobRow job) {
    if (!"queued".equals(job.status())) {
      return Mono.empty();
    }
    return c.sql(
            "UPDATE jobs SET status = 'running', started_at = coalesce(started_at, now())"
                + " WHERE id = :id AND status = 'queued'")
        .bind("id", job.id())
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<Void> finishJob(
      DatabaseClient c, UUID id, String status, String code, String message, StatusMessage m) {
    DatabaseClient.GenericExecuteSpec spec =
        c.sql(
                "UPDATE jobs SET status = :status, finished_at = now(),"
                    + " progress_percent = CASE WHEN :status = 'failed' THEN progress_percent ELSE 100 END,"
                    + " failure_code = :code, failure_message = :message,"
                    + " last_status_seq = greatest(last_status_seq, :seq), last_status_at = now(),"
                    + " result_parts = coalesce(:parts, result_parts),"
                    + " counts = coalesce(CAST(:counts AS jsonb), counts),"
                    + " warnings = coalesce(CAST(:warnings AS jsonb), warnings) WHERE id = :id")
            .bind("id", id)
            .bind("status", status)
            .bind("seq", m == null ? 0L : m.seq());
    spec = nullable(spec, "code", code, String.class);
    spec = nullable(spec, "message", message, String.class);
    spec = nullable(spec, "parts", m == null ? null : m.resultParts(), Integer.class);
    spec = nullable(spec, "counts", m == null ? null : countsJson(m.counts()), String.class);
    spec = nullable(spec, "warnings", m == null ? null : m.warningsJson(), String.class);
    return spec.fetch().rowsUpdated().then();
  }

  private static String countsJson(Map<String, Integer> counts) {
    if (counts == null) {
      return null;
    }
    StringBuilder b = new StringBuilder("{");
    boolean first = true;
    for (var e : counts.entrySet()) {
      if (!first) {
        b.append(',');
      }
      first = false;
      b.append('"').append(e.getKey().replace("\"", "")).append("\":").append(e.getValue());
    }
    return b.append('}').toString();
  }

  private Mono<Void> pushJob(
      UUID tenderId, UUID jobId, UUID targetId, String jobType, String state) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("state", state);
    Mono<Void> job = push.tender(tenderId, "job.updated", "job", jobId, data);
    if (!"document_processing".equals(jobType) || "running".equals(state)) {
      return job;
    }
    return job.then(push.tender(tenderId, "document.updated", "document_version", targetId, data));
  }

  /** Used by the timers to push the change they made after their transaction committed. */
  Mono<Void> pushAfterTimer(JobRow job, String state) {
    return pushJob(job.tenderId(), job.id(), job.targetId(), job.jobType(), state);
  }

  static <T> DatabaseClient.GenericExecuteSpec nullable(
      DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
    return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
  }

  Logger log() {
    return log;
  }
}
