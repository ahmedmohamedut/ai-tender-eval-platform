package app.tendereval.backend.jobs;

import app.tendereval.backend.messaging.PushPublisher;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Stores a batch of pre-check findings (PI 5.6, kind {@code prechecks}) as {@code
 * tender_ai_ingest}. The item id is the producer's: a redelivered batch inserts nothing twice. The
 * findings are advisory; nothing reads them to make a decision (UX-SCR-03).
 */
@Service
public class PrecheckResultProcessor {

  private static final Logger log = LoggerFactory.getLogger(PrecheckResultProcessor.class);

  private final IngestDb db;
  private final PushPublisher push;

  public PrecheckResultProcessor(IngestDb db, PushPublisher push) {
    this.db = db;
    this.push = push;
  }

  public Mono<Outcome> apply(JsonNode body) {
    UUID jobId;
    UUID tenderId;
    JsonNode items;
    try {
      jobId = UUID.fromString(StatusMessage.text(body, "job_id"));
      tenderId = UUID.fromString(StatusMessage.text(body, "tender_id"));
      items = body.get("items");
      if (items == null || !items.isArray()) {
        return Mono.just(Outcome.deadLetter("prechecks result without items"));
      }
    } catch (RuntimeException e) {
      return Mono.just(Outcome.deadLetter("invalid prechecks result"));
    }
    return db.inTransaction(c -> store(c, jobId, tenderId, items))
        .flatMap(
            outcome ->
                outcome instanceof Outcome.Ack a && "stored".equals(a.note())
                    ? push.tender(
                            tenderId, "tender.changed", "job", jobId, Map.of("reason", "prechecks"))
                        .thenReturn(outcome)
                    : Mono.just(outcome))
        .onErrorResume(
            e -> {
              log.warn("prechecks not stored job={} error={}", jobId, e.getClass().getSimpleName());
              return Mono.just(Outcome.retry(Duration.ofSeconds(5), "storage unavailable"));
            });
  }

  private Mono<Outcome> store(DatabaseClient c, UUID jobId, UUID tenderId, JsonNode items) {
    return c.sql(
            "SELECT j.status, j.tender_id, j.target_id, a.id AS run_id FROM jobs j"
                + " LEFT JOIN ai_runs a ON a.job_id = j.id"
                + " WHERE j.id = :j AND j.job_type = 'screening_precheck'")
        .bind("j", jobId)
        .map(
            (r, m) ->
                new Object[] {
                  r.get("status", String.class),
                  r.get("tender_id", UUID.class),
                  r.get("target_id", UUID.class),
                  r.get("run_id", UUID.class)
                })
        .one()
        .map(java.util.Optional::of)
        .defaultIfEmpty(java.util.Optional.empty())
        .flatMap(
            job -> {
              if (job.isEmpty() || !tenderId.equals(job.get()[1])) {
                return Mono.just(Outcome.deadLetter("no pre-check job for this result"));
              }
              Object[] j = job.get();
              if (JobProcessor.TERMINAL.contains(j[0])) {
                return Mono.just(Outcome.ack("job already finished"));
              }
              if (j[3] == null) {
                return Mono.just(Outcome.retry(Duration.ofSeconds(5), "run not recorded yet"));
              }
              Mono<Void> inserts = Mono.empty();
              for (JsonNode item : items) {
                String id = item.path("item_id").asString("");
                String section = item.path("expected_section").asString("").strip();
                if (id.isEmpty() || section.isEmpty()) {
                  continue;
                }
                String note = item.path("note").isNull() ? null : item.path("note").asString(null);
                DatabaseClient.GenericExecuteSpec spec =
                    c.sql(
                            "INSERT INTO screening_prechecks (source_item_id, proposal_version_id,"
                                + " ai_run_id, expected_section, found, note)"
                                + " VALUES (:i, :p, :r, :s, :f, :n) ON CONFLICT (source_item_id) DO NOTHING")
                        .bind("i", UUID.fromString(id))
                        .bind("p", (UUID) j[2])
                        .bind("r", (UUID) j[3])
                        .bind("s", section)
                        .bind("f", item.path("found").asBoolean(false));
                spec = note == null ? spec.bindNull("n", String.class) : spec.bind("n", note);
                inserts = inserts.then(spec.fetch().rowsUpdated().then());
              }
              return inserts.thenReturn(Outcome.ack("stored"));
            });
  }
}
