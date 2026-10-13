package app.tendereval.backend.jobs;

import app.tendereval.backend.messaging.PushPublisher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores a batch of extracted requirements (PI 5.6, kind {@code requirements}) as {@code
 * tender_ai_ingest}. Ids are the producer's: a redelivered batch inserts nothing twice, and the
 * sources and flags of a row already stored are not added again. The worker has already verified
 * every quote against the passage it cites (rule 6); the backend adds the low-confidence flag from
 * the administrator's threshold.
 */
@Service
public class RequirementResultProcessor {

  private static final Logger log = LoggerFactory.getLogger(RequirementResultProcessor.class);

  private final IngestDb db;
  private final PushPublisher push;
  private final JsonMapper json = JsonMapper.builder().build();

  public RequirementResultProcessor(IngestDb db, PushPublisher push) {
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
        return Mono.just(Outcome.deadLetter("requirements result without items"));
      }
    } catch (RuntimeException e) {
      return Mono.just(Outcome.deadLetter("invalid requirements result"));
    }
    return db.inTransaction(c -> store(c, jobId, tenderId, items))
        .flatMap(
            outcome ->
                outcome instanceof Outcome.Ack a && "stored".equals(a.note())
                    ? push.tender(tenderId, "requirements.changed", "tender", tenderId, Map.of())
                        .thenReturn(outcome)
                    : Mono.just(outcome))
        .onErrorResume(
            e -> {
              log.warn(
                  "requirements not stored job={} error={}", jobId, e.getClass().getSimpleName());
              return Mono.just(Outcome.retry(Duration.ofSeconds(5), "storage unavailable"));
            });
  }

  private Mono<Outcome> store(DatabaseClient c, UUID jobId, UUID tenderId, JsonNode items) {
    return c.sql(
            "SELECT j.status, j.tender_id, a.id AS run_id FROM jobs j"
                + " LEFT JOIN ai_runs a ON a.job_id = j.id"
                + " WHERE j.id = :j AND j.job_type = 'requirement_extraction'")
        .bind("j", jobId)
        .map(
            (r, m) ->
                new Object[] {
                  r.get("status", String.class),
                  r.get("tender_id", UUID.class),
                  r.get("run_id", UUID.class)
                })
        .one()
        .map(java.util.Optional::of)
        .defaultIfEmpty(java.util.Optional.empty())
        .flatMap(
            job -> {
              if (job.isEmpty() || !tenderId.equals(job.get()[1])) {
                return Mono.just(Outcome.deadLetter("no extraction job for this result"));
              }
              Object[] j = job.get();
              if (JobProcessor.TERMINAL.contains(j[0])) {
                return Mono.just(Outcome.ack("job already finished"));
              }
              if (j[2] == null) {
                // the first status (with the run record) has not arrived yet
                return Mono.just(Outcome.retry(Duration.ofSeconds(5), "run not recorded yet"));
              }
              return insertAll(c, tenderId, (UUID) j[2], items).thenReturn(Outcome.ack("stored"));
            });
  }

  private Mono<Void> insertAll(DatabaseClient c, UUID tenderId, UUID runId, JsonNode items) {
    Mono<Double> threshold =
        c.sql("SELECT value FROM settings WHERE key = 'ai.low_confidence_threshold'")
            .map((r, m) -> Double.parseDouble(r.get("value", String.class)))
            .one()
            .defaultIfEmpty(0.6);
    Mono<Set<UUID>> categories =
        c.sql("SELECT id FROM requirement_categories")
            .map((r, m) -> r.get("id", UUID.class))
            .all()
            .collectList()
            .map(l -> (Set<UUID>) new HashSet<>(l));
    return Mono.zip(threshold, categories)
        .flatMap(
            t -> {
              double low = t.getT1();
              Set<UUID> known = t.getT2();
              Map<UUID, UUID> idByItem = new HashMap<>();
              List<Object[]> flagWork = new ArrayList<>(); // requirement id, JsonNode flag, item
              Mono<Void> inserts = Mono.empty();
              for (JsonNode item : items) {
                inserts =
                    inserts.then(
                        insertOne(c, tenderId, runId, item, known, low, idByItem, flagWork));
              }
              return inserts.then(
                  Mono.defer(() -> insertFlags(c, tenderId, runId, flagWork, idByItem)));
            });
  }

  private Mono<Void> insertOne(
      DatabaseClient c,
      UUID tenderId,
      UUID runId,
      JsonNode item,
      Set<UUID> categories,
      double lowThreshold,
      Map<UUID, UUID> idByItem,
      List<Object[]> flagWork) {
    UUID itemId = UUID.fromString(item.path("item_id").asString());
    String text = item.path("text").asString().trim();
    String importance = item.path("importance").asString("mandatory");
    UUID category = null;
    if (item.hasNonNull("category_id")) {
      UUID cat = UUID.fromString(item.path("category_id").asString());
      category = categories.contains(cat) ? cat : null;
    }
    String criteria;
    JsonNode ac = item.get("acceptance_criteria");
    if (ac != null && ac.isArray()) {
      List<String> parts = new ArrayList<>();
      ac.forEach(n -> parts.add(n.asString()));
      criteria = String.join("\n", parts);
    } else {
      criteria = ac == null || ac.isNull() ? "" : ac.asString();
    }
    Map<String, Object> original = new LinkedHashMap<>();
    original.put("text", text);
    original.put("category_id", category == null ? null : category.toString());
    original.put("importance", importance);
    original.put("acceptance_criteria", criteria);
    double confidence = item.path("confidence").asDouble(1.0);

    DatabaseClient.GenericExecuteSpec spec =
        c.sql(
                "INSERT INTO requirements (tender_id, text, category_id, importance,"
                    + " acceptance_criteria, origin, review_state, source_item_id,"
                    + " extraction_ai_run_id, ai_original)"
                    + " VALUES (:t, :text, :cat, :imp, :ac, 'ai', 'unreviewed', :item, :run,"
                    + " CAST(:orig AS jsonb))"
                    + " ON CONFLICT (tender_id, source_item_id) WHERE source_item_id IS NOT NULL"
                    + " DO NOTHING RETURNING id")
            .bind("t", tenderId)
            .bind("text", text)
            .bind("imp", importance)
            .bind("ac", criteria)
            .bind("item", itemId)
            .bind("run", runId)
            .bind("orig", json.writeValueAsString(original));
    spec = category == null ? spec.bindNull("cat", UUID.class) : spec.bind("cat", category);
    final UUID itemKey = itemId;
    return spec.map((r, m) -> r.get("id", UUID.class))
        .one()
        .doOnNext(id -> idByItem.put(itemKey, id))
        .flatMap(
            id -> {
              Mono<Void> sources = Mono.empty();
              for (JsonNode s : item.path("sources")) {
                DatabaseClient.GenericExecuteSpec src =
                    c.sql(
                            "INSERT INTO requirement_sources (requirement_id, document_version_id,"
                                + " passage_id, page_no, quote) SELECT :r, v.id, :p, :n, :q"
                                + " FROM document_versions v JOIN documents d ON d.id = v.document_id"
                                + " WHERE v.id = :v AND d.tender_id = :t ON CONFLICT DO NOTHING")
                        .bind("r", id)
                        .bind("v", UUID.fromString(s.path("document_version_id").asString()))
                        .bind("t", tenderId);
                src =
                    s.hasNonNull("passage_id")
                        ? src.bind("p", UUID.fromString(s.path("passage_id").asString()))
                        : src.bindNull("p", UUID.class);
                src =
                    s.hasNonNull("page_no")
                        ? src.bind("n", s.path("page_no").asInt())
                        : src.bindNull("n", Integer.class);
                src =
                    s.hasNonNull("quote")
                        ? src.bind("q", s.path("quote").asString())
                        : src.bindNull("q", String.class);
                sources = sources.then(src.fetch().rowsUpdated().then());
              }
              for (JsonNode f : item.path("flags")) {
                flagWork.add(new Object[] {id, f});
              }
              if (confidence < lowThreshold) {
                Map<String, Object> low = new LinkedHashMap<>();
                low.put("flag_type", "low_confidence");
                low.put("detail", "The extraction is not certain of this requirement");
                flagWork.add(new Object[] {id, json.valueToTree(low)});
              }
              return sources;
            })
        .then();
  }

  private Mono<Void> insertFlags(
      DatabaseClient c, UUID tenderId, UUID runId, List<Object[]> work, Map<UUID, UUID> idByItem) {
    Flux<Object[]> flux = Flux.fromIterable(work);
    return flux.concatMap(
            w -> {
              UUID requirement = (UUID) w[0];
              JsonNode f = (JsonNode) w[1];
              Mono<UUID> related;
              if (f.hasNonNull("related_requirement_id")) {
                related = Mono.just(UUID.fromString(f.path("related_requirement_id").asString()));
              } else if (f.hasNonNull("related_item_id")) {
                UUID item = UUID.fromString(f.path("related_item_id").asString());
                related =
                    idByItem.containsKey(item)
                        ? Mono.just(idByItem.get(item))
                        : c.sql(
                                "SELECT id FROM requirements WHERE tender_id = :t AND source_item_id = :i")
                            .bind("t", tenderId)
                            .bind("i", item)
                            .map((r, m) -> r.get("id", UUID.class))
                            .one();
              } else {
                related = Mono.empty();
              }
              return related
                  .map(java.util.Optional::of)
                  .defaultIfEmpty(java.util.Optional.empty())
                  .flatMap(
                      rel -> {
                        DatabaseClient.GenericExecuteSpec spec =
                            c.sql(
                                    "INSERT INTO requirement_flags (requirement_id, flag_type, detail,"
                                        + " related_requirement_id, raised_by_ai_run_id)"
                                        + " VALUES (:r, :f, :d, :rel, :run)")
                                .bind("r", requirement)
                                .bind("f", f.path("flag_type").asString())
                                .bind("run", runId);
                        spec =
                            f.hasNonNull("detail")
                                ? spec.bind("d", f.path("detail").asString())
                                : spec.bindNull("d", String.class);
                        spec =
                            rel.isPresent()
                                ? spec.bind("rel", rel.get())
                                : spec.bindNull("rel", UUID.class);
                        return spec.fetch().rowsUpdated().then();
                      });
            })
        .then();
  }
}
