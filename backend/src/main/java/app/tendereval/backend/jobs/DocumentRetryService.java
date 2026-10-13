package app.tendereval.backend.jobs;

import app.tendereval.backend.api.model.Job;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.ApiException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Retry of document processing (UX-DOC-03, UX-JOB-05): a new job that points back at the failed
 * one, for the stored original. The parse is not repeated when the worker finds a manifest. The
 * caller publishes the command after the transaction commits.
 */
@Service
public class DocumentRetryService {

  private static final Set<String> FILE_CAUSES =
      Set.of("password_protected", "no_selectable_text", "unsupported_format");

  private final AuditService audit;
  private final JobsService jobs;

  public DocumentRetryService(AuditService audit, JobsService jobs) {
    this.audit = audit;
    this.jobs = jobs;
  }

  /** {@code retryDocumentProcessing}: admin only (the contract's role guard). */
  public Mono<Job> retryVersion(DatabaseClient db, CurrentUser actor, UUID versionId) {
    return db.sql(
            "SELECT v.status, v.failure_reason, v.storage_key, v.sha256, d.tender_id,"
                + " (SELECT j.id FROM jobs j WHERE j.target_id = v.id"
                + "  AND j.job_type = 'document_processing' ORDER BY j.queued_at DESC LIMIT 1)"
                + "  AS last_job"
                + " FROM document_versions v JOIN documents d ON d.id = v.document_id"
                + " WHERE v.id = :id AND v.deleted_at IS NULL FOR UPDATE OF v")
        .bind("id", versionId)
        .map(
            (r, m) ->
                new Object[] {
                  r.get("status", String.class),
                  r.get("failure_reason", String.class),
                  r.get("storage_key", String.class),
                  r.get("sha256", String.class),
                  r.get("tender_id", UUID.class),
                  r.get("last_job", UUID.class)
                })
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("document version")))
        .flatMap(
            v -> {
              if (!"failed".equals(v[0])) {
                return Mono.error(
                    ApiException.conflict(
                        "not_failed", "Only a document that failed processing can be retried"));
              }
              if (v[1] != null && FILE_CAUSES.contains(v[1])) {
                return Mono.error(
                    ApiException.conflict(
                        "not_retryable",
                        "The file itself is the cause. Upload a new version instead"));
              }
              if (v[2] == null || v[3] == null) {
                return Mono.error(
                    ApiException.conflict(
                        "not_retryable", "The upload never completed. Upload the file again"));
              }
              UUID tender = (UUID) v[4];
              UUID last = (UUID) v[5];
              DatabaseClient.GenericExecuteSpec insert =
                  db.sql(
                          "INSERT INTO jobs (tender_id, job_type, target_type, target_id, started_by,"
                              + " retry_of_job_id) VALUES (:t, 'document_processing',"
                              + " 'document_version', :v, :u, :r) RETURNING id")
                      .bind("t", tender)
                      .bind("v", versionId)
                      .bind("u", actor.id());
              insert = last == null ? insert.bindNull("r", UUID.class) : insert.bind("r", last);
              return db.sql(
                      "UPDATE document_versions SET status = 'processing', failure_reason = NULL"
                          + " WHERE id = :v AND status = 'failed'")
                  .bind("v", versionId)
                  .fetch()
                  .rowsUpdated()
                  .then(insert.map((r, m) -> r.get("id", UUID.class)).one())
                  .flatMap(
                      jobId ->
                          audit
                              .record(
                                  db,
                                  actor,
                                  AuditEntry.of(
                                          "document_retry_requested", "document_version", versionId)
                                      .tender(tender)
                                      .created(
                                          Map.of(
                                              "job_id",
                                              jobId.toString(),
                                              "retry_of_job_id",
                                              last == null ? "" : last.toString()))
                                      .build())
                              .then(jobs.get(db, jobId)));
            });
  }
}
