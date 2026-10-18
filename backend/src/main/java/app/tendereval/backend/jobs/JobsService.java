package app.tendereval.backend.jobs;

import app.tendereval.backend.api.model.Job;
import app.tendereval.backend.api.model.JobEvent;
import app.tendereval.backend.api.model.JobFailure;
import app.tendereval.backend.api.model.JobPage;
import app.tendereval.backend.api.model.JobStatus;
import app.tendereval.backend.api.model.JobStep;
import app.tendereval.backend.api.model.JobType;
import app.tendereval.backend.api.model.StepStatus;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.common.Cursor;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.ApiException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** The job center (UX-JOB-01..07): reads, cancel and retry as the request role (RLS applies). */
@Service
public class JobsService {

  private static final Set<String> FILE_CAUSES =
      Set.of("password_protected", "no_selectable_text", "unsupported_format");

  private static final String SELECT =
      "SELECT j.id, j.tender_id, j.job_type, j.target_type, j.target_id, j.status, j.started_by,"
          + " u.display_name AS starter, j.current_step, j.progress_percent, j.queued_at,"
          + " j.started_at, j.finished_at, greatest(j.updated_at, j.last_status_at) AS last_update, j.failure_code, j.failure_message, j.retry_of_job_id,"
          + " (j.started_by = app_user_id() OR app_is_chair_of(j.tender_id)) AS own_or_chair,"
          + " (app_role() = 'admin') AS is_admin,"
          + " (SELECT d.title || ' (version ' || v.version_no || ')' FROM document_versions v"
          + "  JOIN documents d ON d.id = v.document_id WHERE v.id = j.target_id"
          + "  AND j.target_type = 'document_version') AS target_label"
          + " FROM jobs j LEFT JOIN users u ON u.id = j.started_by";

  private final AuditService audit;

  public JobsService(AuditService audit) {
    this.audit = audit;
  }

  // ---- reads
  // ----------------------------------------------------------------------------------------

  public Mono<Job> get(DatabaseClient db, UUID id) {
    return db.sql(SELECT + " WHERE j.id = :id")
        .bind("id", id)
        .map((r, m) -> row(r))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("job")))
        .flatMap(j -> withSteps(db, List.of(j)).map(l -> l.get(0)))
        .flatMap(
            j ->
                db.sql(
                        "SELECT at, level, step, message FROM job_events WHERE job_id = :id"
                            + " ORDER BY at, id")
                    .bind("id", id)
                    .map(
                        (r, m) ->
                            new JobEvent()
                                .at(r.get("at", OffsetDateTime.class))
                                .level(JobEvent.LevelEnum.fromValue(r.get("level", String.class)))
                                .step(r.get("step", String.class))
                                .message(r.get("message", String.class)))
                    .all()
                    .collectList()
                    .map(
                        ev -> {
                          j.setEvents(ev);
                          return j;
                        }));
  }

  public Mono<JobPage> list(
      DatabaseClient db,
      Integer limit,
      String cursor,
      UUID tenderId,
      JobType type,
      JobStatus status,
      Boolean active) {
    int size = Cursor.limit(limit);
    Cursor c = Cursor.decodeOrNull(cursor);
    StringBuilder where = new StringBuilder(" WHERE true");
    Map<String, Object> binds = new LinkedHashMap<>();
    if (tenderId != null) {
      where.append(" AND j.tender_id = :tender");
      binds.put("tender", tenderId);
    }
    if (type != null) {
      where.append(" AND j.job_type = :type");
      binds.put("type", type.getValue());
    }
    if (status != null) {
      where.append(" AND j.status = :status");
      binds.put("status", status.getValue());
    }
    if (Boolean.TRUE.equals(active)) {
      where.append(" AND j.status IN ('queued', 'running')");
    }
    String filter = where.toString();
    StringBuilder page = new StringBuilder(filter);
    Map<String, Object> pageBinds = new LinkedHashMap<>(binds);
    if (c != null) {
      page.append(" AND (j.queued_at, j.id) < (:ts, :cid)");
      pageBinds.put("ts", OffsetDateTime.parse(c.sortValue()));
      pageBinds.put("cid", c.id());
    }
    DatabaseClient.GenericExecuteSpec rows =
        db.sql(SELECT + page + " ORDER BY j.queued_at DESC, j.id DESC LIMIT " + (size + 1));
    for (var e : pageBinds.entrySet()) {
      rows = rows.bind(e.getKey(), e.getValue());
    }
    DatabaseClient.GenericExecuteSpec count = db.sql("SELECT count(*) AS n FROM jobs j" + filter);
    for (var e : binds.entrySet()) {
      count = count.bind(e.getKey(), e.getValue());
    }
    Mono<Long> total = count.map((r, m) -> r.get("n", Long.class)).one();
    return rows.map((r, m) -> row(r))
        .all()
        .collectList()
        .zipWith(total)
        .flatMap(
            t -> {
              List<Job> items = t.getT1();
              String next = null;
              if (items.size() > size) {
                items = new ArrayList<>(items.subList(0, size));
                Job last = items.get(size - 1);
                next = new Cursor(last.getQueuedAt().toString(), last.getId()).encode();
              }
              final String nextCursor = next;
              return withSteps(db, items)
                  .map(
                      l ->
                          new JobPage()
                              .items(l)
                              .nextCursor(nextCursor)
                              .total(t.getT2().intValue()));
            });
  }

  private Mono<List<Job>> withSteps(DatabaseClient db, List<Job> jobs) {
    if (jobs.isEmpty()) {
      return Mono.just(jobs);
    }
    List<UUID> ids = jobs.stream().map(Job::getId).toList();
    return db.sql(
            "SELECT job_id, step_no, name, status, started_at, finished_at FROM job_steps"
                + " WHERE job_id IN (:ids) ORDER BY job_id, step_no")
        .bind("ids", ids)
        .map(
            (r, m) ->
                Map.entry(
                    r.get("job_id", UUID.class),
                    new JobStep()
                        .stepNo(r.get("step_no", Integer.class))
                        .name(r.get("name", String.class))
                        .status(StepStatus.fromValue(r.get("status", String.class)))
                        .startedAt(r.get("started_at", OffsetDateTime.class))
                        .finishedAt(r.get("finished_at", OffsetDateTime.class))))
        .all()
        .collectList()
        .map(
            entries -> {
              Map<UUID, List<JobStep>> byJob =
                  entries.stream()
                      .collect(
                          Collectors.groupingBy(
                              Map.Entry::getKey,
                              Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
              jobs.forEach(j -> j.setSteps(byJob.getOrDefault(j.getId(), List.of())));
              return jobs;
            });
  }

  private static Job row(io.r2dbc.spi.Readable r) {
    String status = r.get("status", String.class);
    String type = r.get("job_type", String.class);
    UUID by = r.get("started_by", UUID.class);
    String starter = r.get("starter", String.class);
    OffsetDateTime started = r.get("started_at", OffsetDateTime.class);
    OffsetDateTime finished = r.get("finished_at", OffsetDateTime.class);
    int progress = r.get("progress_percent", Integer.class);
    boolean open = "queued".equals(status) || "running".equals(status);
    boolean ownOrChair = Boolean.TRUE.equals(r.get("own_or_chair", Boolean.class));
    boolean admin = Boolean.TRUE.equals(r.get("is_admin", Boolean.class));
    boolean finishedBad = "failed".equals(status) || "cancelled".equals(status);
    String code = r.get("failure_code", String.class);

    Job job =
        new Job()
            .id(r.get("id", UUID.class))
            .tenderId(r.get("tender_id", UUID.class))
            .jobType(JobType.fromValue(type))
            .targetType(r.get("target_type", String.class))
            .targetId(r.get("target_id", UUID.class))
            .targetLabel(r.get("target_label", String.class))
            .status(JobStatus.fromValue(status))
            .startedBy(
                by == null || starter == null ? null : new UserRef().id(by).displayName(starter))
            .currentStep(r.get("current_step", String.class))
            .progressPercent(progress)
            .queuedAt(r.get("queued_at", OffsetDateTime.class))
            .startedAt(started)
            .finishedAt(finished)
            .updatedAt(r.get("last_update", OffsetDateTime.class))
            .retryOfJobId(r.get("retry_of_job_id", UUID.class))
            .canCancel(open && ownOrChair && !admin)
            // Document processing is retried by an administrator from the document, others by the
            // person who started them or the Chair.
            .canRetry(
                finishedBad
                    && (("document_processing".equals(type) && admin)
                        || (!"document_processing".equals(type) && ownOrChair && !admin))
                    && !(code != null && FILE_CAUSES.contains(code)));
    if (started != null) {
      OffsetDateTime end = finished != null ? finished : OffsetDateTime.now();
      job.durationSeconds((int) Duration.between(started, end).toSeconds());
      if ("running".equals(status) && progress >= 5 && progress < 100) {
        long elapsed = Duration.between(started, end).toSeconds();
        job.estimatedRemainingSeconds((int) (elapsed * (100 - progress) / progress));
      }
    }
    if ("failed".equals(status) && code != null) {
      job.failure(
          new JobFailure()
              .code(code)
              .message(message(code, r.get("failure_message", String.class)))
              .suggestedActions(actions(code)));
    }
    return job;
  }

  /** Plain-language text for a failure code (the worker's message is not shown as is). */
  static String message(String code, String detail) {
    return switch (code) {
      case "password_protected" -> "The file is password protected. Upload an unprotected copy.";
      case "no_selectable_text" ->
          "The file has no selectable text. Upload a text-based copy, not a scan.";
      case "unsupported_format" -> "The file could not be read as a PDF or Word document.";
      case "source_unavailable" -> "The stored file could not be read. Try again in a few minutes.";
      case "index_unavailable" -> "The search index did not answer. Try again in a few minutes.";
      case "llm_unavailable" -> "The language model did not answer. Try again in a few minutes.";
      case "model_output_unusable" ->
          "The language model's answers were cut off or unreadable. Ask an administrator to check the model settings.";
      case "no_content_in_scope" -> "The chosen pages hold no text. Change the selection.";
      case "timeout" -> "The job took longer than allowed. Try again.";
      default -> "The job failed for a reason the platform could not name. Ask an administrator.";
    };
  }

  static List<JobFailure.SuggestedActionsEnum> actions(String code) {
    return switch (code) {
      case "source_unavailable", "index_unavailable", "llm_unavailable", "timeout" ->
          List.of(JobFailure.SuggestedActionsEnum.RETRY);
      case "no_content_in_scope" -> List.of(JobFailure.SuggestedActionsEnum.CHANGE_SELECTION);
      case "other", "model_output_unusable" ->
          List.of(JobFailure.SuggestedActionsEnum.CONTACT_ADMIN);
      default -> List.of();
    };
  }

  // ---- cancel
  // --------------------------------------------------------------------------------------------

  /** The row is cancelled at once; the caller then writes the cancel key and fixes the version. */
  public Mono<Job> cancel(DatabaseClient db, CurrentUser actor, UUID id) {
    return db.sql(
            "SELECT tender_id, status, job_type,"
                + " (started_by = app_user_id() OR app_is_chair_of(tender_id)) AS allowed"
                + " FROM jobs WHERE id = :id FOR UPDATE")
        .bind("id", id)
        .map(
            (r, m) ->
                new Object[] {
                  r.get("tender_id", UUID.class),
                  r.get("status", String.class),
                  r.get("allowed", Boolean.class)
                })
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("job")))
        .flatMap(
            row -> {
              if (!Boolean.TRUE.equals(row[2])) {
                return Mono.error(
                    ApiException.forbidden(
                        "forbidden",
                        "Only the person who started the job or the Chair can cancel it"));
              }
              String status = (String) row[1];
              if (!"queued".equals(status) && !"running".equals(status)) {
                return Mono.error(
                    ApiException.conflict("job_finished", "The job has already finished"));
              }
              return db.sql(
                      "UPDATE jobs SET status = 'cancelled', cancelled_by = :u, finished_at = now()"
                          + " WHERE id = :id AND status IN ('queued', 'running')")
                  .bind("u", actor.id())
                  .bind("id", id)
                  .fetch()
                  .rowsUpdated()
                  .then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("job_cancelled", "job", id)
                              .tender((UUID) row[0])
                              .changed(Map.of("status", status), Map.of("status", "cancelled"))
                              .build()))
                  .then(get(db, id));
            });
  }
}
