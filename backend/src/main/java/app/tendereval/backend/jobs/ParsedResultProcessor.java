package app.tendereval.backend.jobs;

import app.tendereval.backend.config.AppProperties;
import app.tendereval.backend.storage.ObjectStorage;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores a parsed document version (PI 5.6, kind {@code parsed}): reads {@code pages}, {@code
 * sections} and {@code passages} from {@code tender-derived} and inserts them in batches with the
 * producer's ids and {@code ON CONFLICT DO NOTHING}, so a redelivery stores nothing twice. The
 * status handler sets the version ready once the counts match.
 */
@Service
public class ParsedResultProcessor {

  private static final Logger log = LoggerFactory.getLogger(ParsedResultProcessor.class);
  static final int BATCH = 250;

  private final IngestDb db;
  private final ObjectStorage storage;
  private final AppProperties props;
  private final JsonMapper json = JsonMapper.builder().build();

  public ParsedResultProcessor(IngestDb db, ObjectStorage storage, AppProperties props) {
    this.db = db;
    this.storage = storage;
    this.props = props;
  }

  public Mono<Outcome> apply(JsonNode body) {
    UUID jobId;
    UUID tenderId;
    UUID versionId;
    JsonNode parsed;
    try {
      jobId = UUID.fromString(StatusMessage.text(body, "job_id"));
      tenderId = UUID.fromString(StatusMessage.text(body, "tender_id"));
      versionId = UUID.fromString(StatusMessage.text(body.get("target"), "id"));
      parsed = body.get("parsed");
      if (parsed == null || !parsed.isObject()) {
        return Mono.just(Outcome.deadLetter("parsed result without files"));
      }
    } catch (RuntimeException e) {
      return Mono.just(Outcome.deadLetter("invalid parsed result"));
    }
    String pagesKey =
        key(parsed, "pages", "parsed/%s/%s/pages.jsonl".formatted(tenderId, versionId));
    String sectionsKey =
        key(parsed, "sections", "parsed/%s/%s/sections.jsonl".formatted(tenderId, versionId));
    String passagesKey =
        key(parsed, "passages", "parsed/%s/%s/passages.jsonl".formatted(tenderId, versionId));
    String renderKey =
        key(parsed, "rendered_pdf", "rendered/%s/%s/document.pdf".formatted(tenderId, versionId));
    if (pagesKey == null || sectionsKey == null || passagesKey == null || renderKey == null) {
      // A worker can only name the fixed keys of AI 3.3; anything else is not read.
      return Mono.just(Outcome.deadLetter("parsed result names unexpected keys"));
    }
    String expectedSha = StatusMessage.text(parsed, "source_sha256");

    return db.inTransaction(
            c ->
                c.sql(
                        "SELECT j.status AS job_status, j.tender_id, j.target_id, v.sha256"
                            + " FROM jobs j JOIN document_versions v ON v.id = j.target_id"
                            + " WHERE j.id = :id AND j.job_type = 'document_processing'")
                    .bind("id", jobId)
                    .map(
                        (r, m) ->
                            new String[] {
                              r.get("job_status", String.class),
                              r.get("tender_id", UUID.class).toString(),
                              r.get("target_id", UUID.class).toString(),
                              r.get("sha256", String.class)
                            })
                    .one())
        .flatMap(
            job -> {
              if (!job[1].equals(tenderId.toString()) || !job[2].equals(versionId.toString())) {
                return Mono.just(Outcome.deadLetter("parsed result does not match the job"));
              }
              if (JobProcessor.TERMINAL.contains(job[0])) {
                return Mono.just(Outcome.ack("job already finished"));
              }
              if (expectedSha != null && job[3] != null && !expectedSha.equals(job[3])) {
                return Mono.just(Outcome.deadLetter("parsed from a different file"));
              }
              return store(versionId, pagesKey, sectionsKey, passagesKey, renderKey);
            })
        .defaultIfEmpty(Outcome.deadLetter("no document processing job for this result"));
  }

  private Mono<Outcome> store(
      UUID versionId, String pagesKey, String sectionsKey, String passagesKey, String renderKey) {
    String bucket = props.s3BucketDerived();
    return Mono.zip(
            storage.getBytes(bucket, pagesKey),
            storage.getBytes(bucket, sectionsKey),
            storage.getBytes(bucket, passagesKey))
        .flatMap(
            t -> {
              List<JsonNode> pages = lines(t.getT1());
              List<JsonNode> sections = lines(t.getT2());
              List<JsonNode> passages = lines(t.getT3());
              sections.sort(
                  Comparator.comparingInt((JsonNode n) -> n.get("level").asInt())
                      .thenComparingInt(n -> n.get("sort_order").asInt()));
              return db.inTransaction(
                  c ->
                      insertAll(c, versionId, renderKey, pages, sections, passages)
                          .thenReturn(Outcome.ack("stored")));
            })
        .onErrorResume(
            e -> {
              if (e instanceof tools.jackson.core.JacksonException
                  || e instanceof IllegalArgumentException
                  || e instanceof NullPointerException) {
                log.warn(
                    "parsed files unreadable version={} error={}",
                    versionId,
                    e.getClass().getSimpleName());
                return Mono.just(Outcome.deadLetter("parsed files are not valid"));
              }
              // Storage or database unavailable: the message is returned with backoff.
              log.warn(
                  "parsed result not stored version={} error={}",
                  versionId,
                  e.getClass().getSimpleName());
              return Mono.just(Outcome.retry(Duration.ofSeconds(5), "storage unavailable"));
            });
  }

  private static String key(JsonNode parsed, String field, String expected) {
    String v = StatusMessage.text(parsed, field);
    return expected.equals(v) ? v : null;
  }

  private List<JsonNode> lines(byte[] data) {
    List<JsonNode> out = new ArrayList<>();
    for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
      if (!line.isBlank()) {
        out.add(json.readTree(line));
      }
    }
    return out;
  }

  private Mono<Void> insertAll(
      DatabaseClient c,
      UUID versionId,
      String renderKey,
      List<JsonNode> pages,
      List<JsonNode> sections,
      List<JsonNode> passages) {
    Mono<Void> work = Mono.empty();
    for (List<JsonNode> batch : partition(pages)) {
      work = work.then(insertPages(c, versionId, renderKey, batch));
    }
    for (List<JsonNode> batch : partition(sections)) {
      work = work.then(insertSections(c, versionId, batch));
    }
    for (List<JsonNode> batch : partition(passages)) {
      work = work.then(insertPassages(c, versionId, batch));
    }
    return work;
  }

  private static List<List<JsonNode>> partition(List<JsonNode> all) {
    List<List<JsonNode>> out = new ArrayList<>();
    for (int i = 0; i < all.size(); i += BATCH) {
      out.add(all.subList(i, Math.min(all.size(), i + BATCH)));
    }
    return out;
  }

  private Mono<Void> insertPages(
      DatabaseClient c, UUID versionId, String renderKey, List<JsonNode> rows) {
    return multiInsert(
        c,
        "INSERT INTO document_pages (document_version_id, page_no, width, height, render_key) VALUES ",
        " ON CONFLICT DO NOTHING",
        5,
        rows,
        (spec, i) -> {},
        (spec, n, i) -> {
          spec.bind("a" + i, versionId);
          spec.bind("b" + i, n.get("page_no").asInt());
          spec.bind("c" + i, new BigDecimal(n.get("width").asString()));
          spec.bind("d" + i, new BigDecimal(n.get("height").asString()));
          spec.bind("e" + i, renderKey);
        },
        new String[] {"", "", "", "", ""});
  }

  private Mono<Void> insertSections(DatabaseClient c, UUID versionId, List<JsonNode> rows) {
    return multiInsert(
        c,
        "INSERT INTO document_sections (id, document_version_id, parent_section_id, title, level,"
            + " sort_order, start_page, end_page) VALUES ",
        " ON CONFLICT DO NOTHING",
        8,
        rows,
        (spec, i) -> {},
        (spec, n, i) -> {
          spec.bind("a" + i, UUID.fromString(n.get("section_id").asString()));
          spec.bind("b" + i, versionId);
          JsonNode parent = n.get("parent_section_id");
          if (parent == null || parent.isNull()) {
            spec.bindNull("c" + i, UUID.class);
          } else {
            spec.bind("c" + i, UUID.fromString(parent.asString()));
          }
          spec.bind("d" + i, n.get("title").asString());
          spec.bind("e" + i, n.get("level").asInt());
          spec.bind("f" + i, n.get("sort_order").asInt());
          spec.bind("g" + i, n.get("start_page").asInt());
          spec.bind("h" + i, n.get("end_page").asInt());
        },
        new String[] {"", "", "", "", "", "", "", ""});
  }

  private Mono<Void> insertPassages(DatabaseClient c, UUID versionId, List<JsonNode> rows) {
    return multiInsert(
        c,
        "INSERT INTO passages (id, document_version_id, section_id, page_no, passage_no, text,"
            + " bbox, char_start, char_end, content_hash) VALUES ",
        " ON CONFLICT DO NOTHING",
        10,
        rows,
        (spec, i) -> {},
        (spec, n, i) -> {
          spec.bind("a" + i, UUID.fromString(n.get("passage_id").asString()));
          spec.bind("b" + i, versionId);
          JsonNode section = n.get("section_id");
          if (section == null || section.isNull()) {
            spec.bindNull("c" + i, UUID.class);
          } else {
            spec.bind("c" + i, UUID.fromString(section.asString()));
          }
          spec.bind("d" + i, n.get("page_no").asInt());
          spec.bind("e" + i, n.get("passage_no").asInt());
          spec.bind("f" + i, n.get("text").asString());
          spec.bind("g" + i, n.get("bbox").toString());
          spec.bind("h" + i, n.get("char_start").asInt());
          spec.bind("i" + i, n.get("char_end").asInt());
          spec.bind("j" + i, n.get("content_hash").asString());
        },
        new String[] {"", "", "", "", "", "", "CAST(%s AS jsonb)", "", "", ""});
  }

  /** Per-column placeholder wrappers: {@code %s} in a wrapper is replaced by the placeholder. */
  private interface RowBinder {
    void bind(Spec spec, JsonNode row, int index);
  }

  /** A mutable view over the statement being built. */
  private static final class Spec {
    DatabaseClient.GenericExecuteSpec spec;

    void bind(String name, Object v) {
      spec = spec.bind(name, v);
    }

    void bindNull(String name, Class<?> type) {
      spec = spec.bindNull(name, type);
    }
  }

  private Mono<Void> multiInsert(
      DatabaseClient c,
      String head,
      String tail,
      int columns,
      List<JsonNode> rows,
      BiConsumer<Spec, Integer> unused,
      RowBinder binder,
      String[] wrappers) {
    if (rows.isEmpty()) {
      return Mono.empty();
    }
    String letters = "abcdefghij";
    StringBuilder sql = new StringBuilder(head);
    for (int i = 0; i < rows.size(); i++) {
      sql.append(i == 0 ? "(" : ",(");
      for (int col = 0; col < columns; col++) {
        String ph = ":" + letters.charAt(col) + i;
        String w = wrappers[col];
        sql.append(col == 0 ? "" : ",").append(w.isEmpty() ? ph : w.formatted(ph));
      }
      sql.append(')');
    }
    sql.append(tail);
    Spec s = new Spec();
    s.spec = c.sql(sql.toString());
    for (int i = 0; i < rows.size(); i++) {
      binder.bind(s, rows.get(i), i);
    }
    return s.spec.fetch().rowsUpdated().then();
  }
}
