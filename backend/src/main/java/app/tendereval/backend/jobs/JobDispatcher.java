package app.tendereval.backend.jobs;

import app.tendereval.backend.config.AppProperties;
import app.tendereval.backend.messaging.CommandPublisher;
import app.tendereval.backend.messaging.Subjects;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds and publishes the command for a document processing job (PI 5.2, 5.4). It is only called
 * after the transaction that created or changed the job has committed (rule 4). A failure to
 * publish is logged and not thrown: the queued-job timer publishes it again.
 */
@Component
public class JobDispatcher {

  private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

  private final IngestDb db;
  private final CommandPublisher publisher;
  private final AppProperties props;
  private final JsonMapper json = JsonMapper.builder().build();
  private final app.tendereval.backend.storage.ObjectStorage storage;

  public JobDispatcher(
      IngestDb db,
      CommandPublisher publisher,
      AppProperties props,
      app.tendereval.backend.storage.ObjectStorage storage) {
    this.storage = storage;
    this.db = db;
    this.publisher = publisher;
    this.props = props;
  }

  public Mono<Void> publish(UUID jobId) {
    return db.inTransaction(
            c ->
                c.sql(
                        "SELECT j.tender_id, j.job_type, j.target_id, j.started_by, v.storage_key, v.sha256"
                            + " FROM jobs j JOIN document_versions v ON v.id = j.target_id"
                            + " WHERE j.id = :id AND j.status = 'queued'"
                            + " AND j.job_type = 'document_processing'")
                    .bind("id", jobId)
                    .map(
                        (r, m) ->
                            new Object[] {
                              r.get("tender_id", UUID.class),
                              r.get("target_id", UUID.class),
                              r.get("started_by", UUID.class),
                              r.get("storage_key", String.class),
                              r.get("sha256", String.class)
                            })
                    .one()
                    .flatMap(
                        row ->
                            c.sql("UPDATE jobs SET command_published_at = now() WHERE id = :id")
                                .bind("id", jobId)
                                .fetch()
                                .rowsUpdated()
                                .thenReturn(row)))
        .flatMap(
            row -> {
              Map<String, Object> body = new LinkedHashMap<>();
              body.put("job_id", jobId.toString());
              body.put("job_type", "document_processing");
              body.put("tender_id", row[0].toString());
              body.put("target", Map.of("type", "document_version", "id", row[1].toString()));
              body.put("requested_by", row[2] == null ? null : row[2].toString());
              body.put("attempt", 1);
              body.put("created_at", Instant.now().toString());
              body.put(
                  "input_ref",
                  Map.of("bucket", props.s3BucketDocuments(), "key", row[3], "sha256", row[4]));
              // A new id per minute: the stream's two-minute window must not swallow a republish.
              String msgId = "job:" + jobId + ":cmd:" + (Instant.now().getEpochSecond() / 60);
              return publisher
                  .publish(
                      Subjects.command("document_processing"),
                      json.writeValueAsString(body),
                      msgId,
                      app.tendereval.backend.web.TraceIds.newTraceparent(
                          app.tendereval.backend.web.TraceIds.newTraceId()),
                      Subjects.SCHEMA_COMMAND)
                  .then();
            })
        .doOnError(
            e ->
                log.warn(
                    "command not published job={} error={}", jobId, e.getClass().getSimpleName()))
        .onErrorResume(e -> Mono.empty());
  }

  /**
   * The command of an extraction job (PI 5.4): scope, categories and the requirements that exist,
   * inline up to 64 KB and otherwise a file in {@code tender-jobs} named by {@code input_ref}.
   */
  public Mono<Void> publishExtraction(UUID jobId) {
    return db.inTransaction(
            c ->
                c.sql(
                        "SELECT tender_id, started_by FROM jobs WHERE id = :id AND status = 'queued'"
                            + " AND job_type = 'requirement_extraction'")
                    .bind("id", jobId)
                    .map(
                        (r, m) ->
                            new UUID[] {
                              r.get("tender_id", UUID.class), r.get("started_by", UUID.class)
                            })
                    .one()
                    .flatMap(
                        j ->
                            Mono.zip(
                                    c.sql(
                                            "SELECT i.document_version_id, i.section_id, i.page_from,"
                                                + " i.page_to FROM job_scope_items i WHERE i.job_id = :j"
                                                + " ORDER BY i.created_at, i.id")
                                        .bind("j", jobId)
                                        .map(
                                            (r, m) -> {
                                              Map<String, Object> o = new LinkedHashMap<>();
                                              o.put(
                                                  "document_version_id",
                                                  r.get("document_version_id", UUID.class)
                                                      .toString());
                                              UUID s = r.get("section_id", UUID.class);
                                              o.put("section_id", s == null ? null : s.toString());
                                              o.put("page_from", r.get("page_from", Integer.class));
                                              o.put("page_to", r.get("page_to", Integer.class));
                                              return o;
                                            })
                                        .all()
                                        .collectList(),
                                    c.sql(
                                            "SELECT id, name FROM requirement_categories WHERE is_active"
                                                + " ORDER BY sort_order, name")
                                        .map(
                                            (r, m) ->
                                                Map.<String, Object>of(
                                                    "id", r.get("id", UUID.class).toString(),
                                                    "name", r.get("name", String.class)))
                                        .all()
                                        .collectList(),
                                    c.sql(
                                            "SELECT id, display_no, text FROM requirements WHERE tender_id = :t"
                                                + " AND deleted_at IS NULL ORDER BY display_no")
                                        .bind("t", j[0])
                                        .map(
                                            (r, m) ->
                                                Map.<String, Object>of(
                                                    "id", r.get("id", UUID.class).toString(),
                                                    "number", r.get("display_no", String.class),
                                                    "text", r.get("text", String.class)))
                                        .all()
                                        .collectList())
                                .flatMap(
                                    t -> {
                                      Map<String, Object> input = new LinkedHashMap<>();
                                      input.put("scope", t.getT1());
                                      input.put("categories", t.getT2());
                                      input.put("existing_requirements", t.getT3());
                                      return c.sql(
                                              "UPDATE jobs SET command_published_at = now() WHERE id = :id")
                                          .bind("id", jobId)
                                          .fetch()
                                          .rowsUpdated()
                                          .thenReturn(new Object[] {j, input});
                                    })))
        .flatMap(
            row -> {
              UUID[] j = (UUID[]) row[0];
              @SuppressWarnings("unchecked")
              Map<String, Object> input = (Map<String, Object>) row[1];
              String inline = json.writeValueAsString(input);
              Map<String, Object> body = new LinkedHashMap<>();
              body.put("job_id", jobId.toString());
              body.put("job_type", "requirement_extraction");
              body.put("tender_id", j[0].toString());
              body.put("target", Map.of("type", "tender", "id", j[0].toString()));
              body.put("requested_by", j[1] == null ? null : j[1].toString());
              body.put("attempt", 1);
              body.put("created_at", Instant.now().toString());
              Mono<Void> stored = Mono.empty();
              if (inline.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64 * 1024) {
                body.put("input", input);
              } else {
                byte[] data = inline.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                String key = "inputs/%s/%s.json".formatted(j[0], jobId);
                String sha = java.util.HexFormat.of().formatHex(sha256(data));
                body.put(
                    "input_ref", Map.of("bucket", props.s3BucketJobs(), "key", key, "sha256", sha));
                stored = storage.putBytes(props.s3BucketJobs(), key, data, "application/json");
              }
              String msgId = "job:" + jobId + ":cmd:" + (Instant.now().getEpochSecond() / 60);
              return stored.then(
                  publisher
                      .publish(
                          Subjects.command("requirement_extraction"),
                          json.writeValueAsString(body),
                          msgId,
                          app.tendereval.backend.web.TraceIds.newTraceparent(
                              app.tendereval.backend.web.TraceIds.newTraceId()),
                          Subjects.SCHEMA_COMMAND)
                      .then());
            })
        .doOnError(
            e ->
                log.warn(
                    "extraction command not published job={} error={}",
                    jobId,
                    e.getClass().getSimpleName()))
        .onErrorResume(e -> Mono.empty());
  }

  /**
   * The command of a screening pre-check job (PI 5.4): the RFP's and addenda's document versions
   * and the proposal version's files, inline.
   */
  public Mono<Void> publishPrecheck(UUID jobId) {
    return db.inTransaction(
            c ->
                c.sql(
                        "SELECT j.tender_id, j.started_by, j.target_id FROM jobs j WHERE j.id = :id"
                            + " AND j.status = 'queued' AND j.job_type = 'screening_precheck'")
                    .bind("id", jobId)
                    .map(
                        (r, m) ->
                            new UUID[] {
                              r.get("tender_id", UUID.class),
                              r.get("started_by", UUID.class),
                              r.get("target_id", UUID.class)
                            })
                    .one()
                    .flatMap(
                        j ->
                            Mono.zip(
                                    c.sql(
                                            "SELECT v.id FROM document_versions v JOIN documents d"
                                                + " ON d.id = v.document_id WHERE d.tender_id = :t"
                                                + " AND d.doc_type IN ('rfp', 'addendum')"
                                                + " AND d.deleted_at IS NULL AND v.deleted_at IS NULL"
                                                + " AND v.status = 'ready' AND v.superseded_at IS NULL"
                                                + " ORDER BY d.doc_type, v.created_at")
                                        .bind("t", j[0])
                                        .map((r, m) -> r.get("id", UUID.class).toString())
                                        .all()
                                        .collectList(),
                                    c.sql(
                                            "SELECT f.document_version_id FROM proposal_version_files f"
                                                + " JOIN document_versions v ON v.id = f.document_version_id"
                                                + " WHERE f.proposal_version_id = :p AND v.status = 'ready'"
                                                + " ORDER BY f.sort_order, f.created_at")
                                        .bind("p", j[2])
                                        .map(
                                            (r, m) ->
                                                r.get("document_version_id", UUID.class).toString())
                                        .all()
                                        .collectList())
                                .flatMap(
                                    t -> {
                                      Map<String, Object> input = new LinkedHashMap<>();
                                      input.put("proposal_version_id", j[2].toString());
                                      input.put("rfp_document_version_ids", t.getT1());
                                      input.put("proposal_document_version_ids", t.getT2());
                                      return c.sql(
                                              "UPDATE jobs SET command_published_at = now() WHERE id = :id")
                                          .bind("id", jobId)
                                          .fetch()
                                          .rowsUpdated()
                                          .thenReturn(new Object[] {j, input});
                                    })))
        .flatMap(
            row -> {
              UUID[] j = (UUID[]) row[0];
              Map<String, Object> body = new LinkedHashMap<>();
              body.put("job_id", jobId.toString());
              body.put("job_type", "screening_precheck");
              body.put("tender_id", j[0].toString());
              body.put("target", Map.of("type", "proposal_version", "id", j[2].toString()));
              body.put("requested_by", j[1] == null ? null : j[1].toString());
              body.put("attempt", 1);
              body.put("created_at", Instant.now().toString());
              body.put("input", row[1]);
              String msgId = "job:" + jobId + ":cmd:" + (Instant.now().getEpochSecond() / 60);
              return publisher
                  .publish(
                      Subjects.command("screening_precheck"),
                      json.writeValueAsString(body),
                      msgId,
                      app.tendereval.backend.web.TraceIds.newTraceparent(
                          app.tendereval.backend.web.TraceIds.newTraceId()),
                      Subjects.SCHEMA_COMMAND)
                  .then();
            })
        .doOnError(
            e ->
                log.warn(
                    "pre-check command not published job={} error={}",
                    jobId,
                    e.getClass().getSimpleName()))
        .onErrorResume(e -> Mono.empty());
  }

  private static byte[] sha256(byte[] data) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(data);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
