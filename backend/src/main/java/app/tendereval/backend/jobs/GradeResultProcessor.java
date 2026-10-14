package app.tendereval.backend.jobs;

import app.tendereval.backend.messaging.PushPublisher;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores a batch of AI grades (PI 5.6, kind {@code grades}) with their citations as {@code
 * tender_ai_ingest}. Ids are the producer's: a row already stored is skipped as a whole, so a
 * redelivered batch changes nothing. A new grade replaces the requirement's current one (the old
 * row stays, no longer current). The worker has verified every citation (rule 6); the database
 * refuses a grade without evidence at commit. Rows appear to the committee as each batch lands.
 */
@Service
public class GradeResultProcessor {

  private static final Logger log = LoggerFactory.getLogger(GradeResultProcessor.class);

  private final IngestDb db;
  private final PushPublisher push;
  private final JsonMapper json = JsonMapper.builder().build();

  public GradeResultProcessor(IngestDb db, PushPublisher push) {
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
        return Mono.just(Outcome.deadLetter("grades result without items"));
      }
    } catch (RuntimeException e) {
      return Mono.just(Outcome.deadLetter("invalid grades result"));
    }
    return db.inTransaction(c -> store(c, jobId, tenderId, items))
        .flatMap(
            outcome ->
                outcome instanceof Outcome.Ack a && "stored".equals(a.note())
                    ? push.tender(
                            tenderId,
                            "evaluation.progress",
                            "job",
                            jobId,
                            Map.of("job_id", jobId.toString(), "stored", items.size()))
                        .thenReturn(outcome)
                    : Mono.just(outcome))
        .onErrorResume(
            e -> {
              log.warn("grades not stored job={} error={}", jobId, e.getClass().getSimpleName());
              return Mono.just(Outcome.retry(Duration.ofSeconds(5), "storage unavailable"));
            });
  }

  private Mono<Outcome> store(DatabaseClient c, UUID jobId, UUID tenderId, JsonNode items) {
    return c.sql(
            "SELECT j.status, j.tender_id, j.target_id, a.id AS run_id,"
                + " a.input_scope ->> 'baseline_id' AS baseline_id FROM jobs j"
                + " LEFT JOIN ai_runs a ON a.job_id = j.id"
                + " WHERE j.id = :j AND j.job_type = 'evaluation'")
        .bind("j", jobId)
        .map(
            (r, m) ->
                new Object[] {
                  r.get("status", String.class),
                  r.get("tender_id", UUID.class),
                  r.get("target_id", UUID.class),
                  r.get("run_id", UUID.class),
                  r.get("baseline_id", String.class)
                })
        .one()
        .map(java.util.Optional::of)
        .defaultIfEmpty(java.util.Optional.empty())
        .flatMap(
            job -> {
              if (job.isEmpty() || !tenderId.equals(job.get()[1])) {
                return Mono.just(Outcome.deadLetter("no evaluation job for this result"));
              }
              Object[] j = job.get();
              if (JobProcessor.TERMINAL.contains(j[0])) {
                return Mono.just(Outcome.ack("job already finished"));
              }
              if (j[3] == null || j[4] == null) {
                return Mono.just(Outcome.retry(Duration.ofSeconds(5), "run not recorded yet"));
              }
              UUID pv = (UUID) j[2];
              UUID run = (UUID) j[3];
              UUID baseline = UUID.fromString((String) j[4]);
              Mono<Void> all = Mono.empty();
              for (JsonNode item : items) {
                all = all.then(storeOne(c, pv, run, baseline, item));
              }
              return all.thenReturn(Outcome.ack("stored"));
            });
  }

  private Mono<Void> storeOne(DatabaseClient c, UUID pv, UUID run, UUID baseline, JsonNode item) {
    UUID itemId = UUID.fromString(item.path("item_id").asString());
    UUID requirement = UUID.fromString(item.path("requirement_id").asString());
    return c.sql("SELECT 1 AS x FROM ai_grades WHERE source_item_id = :i")
        .bind("i", itemId)
        .map((r, m) -> 1)
        .one()
        .map(x -> true)
        .defaultIfEmpty(false)
        .flatMap(
            exists -> {
              if (exists) {
                return Mono.<Void>empty();
              }
              // the previous current grade of this requirement stops being current first
              return c.sql(
                      "UPDATE ai_grades SET is_current = false WHERE proposal_version_id = :p"
                          + " AND requirement_id = :r AND is_current")
                  .bind("p", pv)
                  .bind("r", requirement)
                  .fetch()
                  .rowsUpdated()
                  .then(insertGrade(c, pv, run, baseline, itemId, requirement, item));
            });
  }

  private Mono<Void> insertGrade(
      DatabaseClient c,
      UUID pv,
      UUID run,
      UUID baseline,
      UUID itemId,
      UUID requirement,
      JsonNode item) {
    String outcome = item.path("outcome").asString("graded");
    List<String> missing = new java.util.ArrayList<>();
    JsonNode me = item.path("missing_elements");
    if (me.isArray()) {
      me.forEach(n -> missing.add(n.asString()));
    }
    DatabaseClient.GenericExecuteSpec spec =
        c.sql(
                "INSERT INTO ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id,"
                    + " requirement_id, outcome, failure_code, grade, non_compliance_reason,"
                    + " rationale_summary, rationale, missing_elements, confidence,"
                    + " requirement_hash) VALUES (:i, :run, :pv, :b, :r, :o, :fc, :g, :nr, :rs,"
                    + " :ra, CAST(:me AS jsonb), :conf, :h) RETURNING id")
            .bind("i", itemId)
            .bind("run", run)
            .bind("pv", pv)
            .bind("b", baseline)
            .bind("r", requirement)
            .bind("o", outcome)
            .bind("me", json.writeValueAsString(missing))
            .bind("h", item.path("requirement_hash").asString());
    spec = bindText(spec, "fc", item, "failure_code");
    spec = bindText(spec, "g", item, "grade");
    spec = bindText(spec, "nr", item, "non_compliance_reason");
    spec = bindText(spec, "rs", item, "rationale_summary");
    spec = bindText(spec, "ra", item, "rationale");
    JsonNode conf = item.path("confidence");
    spec =
        conf.isNumber()
            ? spec.bind(
                "conf",
                java.math.BigDecimal.valueOf(conf.asDouble())
                    .setScale(2, java.math.RoundingMode.HALF_UP))
            : spec.bindNull("conf", java.math.BigDecimal.class);
    return spec.map((r, m) -> r.get("id", UUID.class))
        .one()
        .flatMap(
            gradeId -> {
              Mono<Void> cites = Mono.empty();
              JsonNode citations = item.path("citations");
              if (citations.isArray()) {
                for (JsonNode ci : citations) {
                  cites =
                      cites.then(
                          c.sql(
                                  "INSERT INTO grade_citations (ai_grade_id, citation_no,"
                                      + " document_version_id, passage_id, page_no, excerpt)"
                                      + " VALUES (:g, :n, :d, :p, :pg, :e)")
                              .bind("g", gradeId)
                              .bind("n", ci.path("citation_no").asInt())
                              .bind("d", UUID.fromString(ci.path("document_version_id").asString()))
                              .bind("p", UUID.fromString(ci.path("passage_id").asString()))
                              .bind("pg", ci.path("page_no").asInt())
                              .bind("e", ci.path("excerpt").asString())
                              .fetch()
                              .rowsUpdated()
                              .then());
                }
              }
              return cites;
            });
  }

  private static DatabaseClient.GenericExecuteSpec bindText(
      DatabaseClient.GenericExecuteSpec spec, String name, JsonNode item, String field) {
    JsonNode n = item.path(field);
    return n.isNull() || n.isMissingNode() || n.asString("").isEmpty()
        ? spec.bindNull(name, String.class)
        : spec.bind(name, n.asString());
  }
}
