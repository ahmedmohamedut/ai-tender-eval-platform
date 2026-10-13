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

  public JobDispatcher(IngestDb db, CommandPublisher publisher, AppProperties props) {
    this.db = db;
    this.publisher = publisher;
    this.props = props;
  }

  public Mono<Void> publish(UUID jobId) {
    return db.inTransaction(
            c ->
                c.sql(
                        "SELECT j.tender_id, j.job_type, j.target_id, j.started_by, v.storage_key"
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
                              r.get("storage_key", String.class)
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
              body.put("input_ref", Map.of("bucket", props.s3BucketDocuments(), "key", row[3]));
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
}
