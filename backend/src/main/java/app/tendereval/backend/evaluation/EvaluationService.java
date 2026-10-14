package app.tendereval.backend.evaluation;

import app.tendereval.backend.api.model.Job;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.jobs.JobsService;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Starting evaluation jobs (UX-EVA-01, UX-EVA-14, UX-JOB). The system starts one for a proposal the
 * Chair accepted once a baseline is confirmed, and again for the requirements that changed when a
 * later baseline is confirmed. A person may re-run the failed or outdated rows. The command is
 * published by the caller after the commit (rule 4); a job whose command was lost is published
 * again by the queued-job timer.
 */
@Service
public class EvaluationService {

  private final AuditService audit;
  private final JobsService jobs;

  public EvaluationService(AuditService audit, JobsService jobs) {
    this.audit = audit;
    this.jobs = jobs;
  }

  /** Queues the first evaluation of an accepted proposal when the baseline is confirmed. */
  public Mono<Boolean> queueIfReady(DatabaseClient db, UUID proposalVersionId) {
    return db.sql(
            "INSERT INTO jobs (tender_id, job_type, target_type, target_id)"
                + " SELECT tv.tender_id, 'evaluation', 'proposal_version', p.id"
                + "   FROM proposal_versions p JOIN tender_vendors tv ON tv.id = p.tender_vendor_id"
                + "  WHERE p.id = :pv AND p.status = 'accepted'"
                + "    AND EXISTS (SELECT 1 FROM baselines b WHERE b.tender_id = tv.tender_id"
                + "                AND b.status = 'confirmed')"
                + "    AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.target_id = p.id"
                + "                    AND j.job_type = 'evaluation'"
                + "                    AND j.status IN ('queued', 'running'))"
                + " RETURNING id, tender_id")
        .bind("pv", proposalVersionId)
        .map((r, m) -> new UUID[] {r.get("id", UUID.class), r.get("tender_id", UUID.class)})
        .one()
        .flatMap(
            ids ->
                audit
                    .recordSystem(
                        db,
                        AuditEntry.of("job_status_changed", "job", ids[0])
                            .tender(ids[1])
                            .created(
                                Map.of(
                                    "status",
                                    "queued",
                                    "job_type",
                                    "evaluation",
                                    "started",
                                    "system"))
                            .build())
                    .thenReturn(true))
        .defaultIfEmpty(false);
  }

  /**
   * The proposals of a vendor that need a first evaluation or a re-run after a confirmed baseline.
   */
  public Mono<Void> onBaselineConfirmed(
      DatabaseClient db, CurrentUser actor, UUID tenderId, int versionNo) {
    return db.sql(
            "SELECT p.id, p.status, p.tender_vendor_id FROM proposal_versions p"
                + " JOIN tender_vendors tv ON tv.id = p.tender_vendor_id"
                + " WHERE tv.tender_id = :t AND tv.removed_at IS NULL"
                + " AND p.status IN ('accepted', 'evaluating', 'evaluated')")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new Object[] {
                  r.get("id", UUID.class),
                  r.get("status", String.class),
                  r.get("tender_vendor_id", UUID.class)
                })
        .all()
        .collectList()
        .flatMap(
            rows -> {
              Mono<Void> chain = Mono.empty();
              for (Object[] row : rows) {
                UUID pv = (UUID) row[0];
                String status = (String) row[1];
                UUID tv = (UUID) row[2];
                if ("accepted".equals(status)) {
                  chain = chain.then(queueIfReady(db, pv).then());
                } else if ("evaluated".equals(status)) {
                  // rows whose requirement changed become Outdated, then the new grades are made
                  chain =
                      chain
                          .then(
                              db.sql(
                                      "SELECT app_mark_rows_outdated(:v, 'baseline_changed', NULL, :n)"
                                          + " AS n")
                                  .bind("v", tv)
                                  .bind(
                                      "n", "Requirements changed in baseline version " + versionNo)
                                  .map((r, m) -> r.get("n", Integer.class))
                                  .one()
                                  .then())
                          .then(queueRerun(db, null, null, pv).then());
                }
              }
              return chain;
            });
  }

  /** A person re-runs the failed, changed or new requirements of an evaluated proposal. */
  public Mono<Job> rerun(DatabaseClient db, CurrentUser actor, UUID tenderVendorId) {
    return db.sql(
            "SELECT p.id, p.status, tv.tender_id, app_is_member_of(tv.tender_id) AS member"
                + " FROM tender_vendors tv JOIN proposal_versions p ON p.tender_vendor_id = tv.id"
                + " AND p.status <> 'superseded' WHERE tv.id = :v AND tv.removed_at IS NULL")
        .bind("v", tenderVendorId)
        .map(
            (r, m) ->
                new Object[] {
                  r.get("id", UUID.class),
                  r.get("status", String.class),
                  Boolean.TRUE.equals(r.get("member", Boolean.class))
                })
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("vendor of this tender")))
        .flatMap(
            row -> {
              if (!(Boolean) row[2]) {
                return Mono.<Job>error(
                    ApiException.forbidden(
                        "forbidden", "Only the committee re-runs an evaluation"));
              }
              if (!"evaluated".equals(row[1])) {
                return Mono.<Job>error(
                    ApiException.conflict(
                        "not_evaluated", "The proposal has not finished its first evaluation"));
              }
              return queueRerun(db, actor, actor.id(), (UUID) row[0])
                  .flatMap(
                      id ->
                          id == null
                              ? Mono.<Job>error(
                                  ApiException.conflict(
                                      "nothing_to_rerun",
                                      "Every requirement already has a current grade"))
                              : jobs.get(db, id));
            });
  }

  /** Retry of a failed or cancelled evaluation: the requirements that still need a grade. */
  public Mono<Job> retry(DatabaseClient db, CurrentUser actor, UUID jobId) {
    return jobs.get(db, jobId)
        .flatMap(
            old -> {
              if (!Boolean.TRUE.equals(old.getCanRetry())) {
                return Mono.<Job>error(
                    ApiException.conflict("not_retryable", "This job cannot be retried"));
              }
              return db.sql(
                      "SELECT 1 AS x FROM proposal_versions WHERE id = :pv AND status <> 'superseded'")
                  .bind("pv", old.getTargetId())
                  .map((r, m) -> 1)
                  .one()
                  .switchIfEmpty(
                      Mono.error(
                          ApiException.conflict(
                              "not_retryable", "The proposal has a newer version")))
                  .then(queueRerun(db, actor, actor.id(), old.getTargetId()))
                  .flatMap(id -> jobs.get(db, id))
                  .switchIfEmpty(
                      Mono.error(
                          ApiException.conflict(
                              "nothing_to_rerun",
                              "Every requirement already has a current grade")));
            });
  }

  /** Queues a re-run when something needs a grade and no evaluation of this proposal is open. */
  private Mono<UUID> queueRerun(
      DatabaseClient db, CurrentUser actor, UUID startedBy, UUID proposalVersionId) {
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id, started_by)"
                    + " SELECT tv.tender_id, 'evaluation', 'proposal_version', p.id, :u"
                    + "   FROM proposal_versions p JOIN tender_vendors tv ON tv.id = p.tender_vendor_id"
                    + "  WHERE p.id = :pv AND app_grades_needed(p.id) > 0"
                    + "    AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.target_id = p.id"
                    + "                    AND j.job_type = 'evaluation'"
                    + "                    AND j.status IN ('queued', 'running'))"
                    + " RETURNING id, tender_id")
            .bind("pv", proposalVersionId);
    spec = startedBy == null ? spec.bindNull("u", UUID.class) : spec.bind("u", startedBy);
    return spec.map((r, m) -> new UUID[] {r.get("id", UUID.class), r.get("tender_id", UUID.class)})
        .one()
        .flatMap(
            ids -> {
              AuditEntry entry =
                  AuditEntry.of("job_status_changed", "job", ids[0])
                      .tender(ids[1])
                      .created(Map.of("status", "queued", "job_type", "evaluation", "rerun", true))
                      .build();
              return (actor == null
                      ? audit.recordSystem(db, entry)
                      : audit.record(db, actor, entry))
                  .thenReturn(ids[0]);
            });
  }
}
