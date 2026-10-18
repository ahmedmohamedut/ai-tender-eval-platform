package app.tendereval.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.StorageFixture;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** PI 5.5 to 5.7: status and result messages applied as the AI-ingest role. */
class JobProcessingIT extends AbstractApiIT {

  @Autowired JobProcessor jobs;
  @Autowired ParsedResultProcessor parsed;

  private final JsonMapper json = JsonMapper.builder().build();

  /** A tender with an uploaded version in processing and its queued job. */
  private record Setup(UUID tender, UUID document, UUID version, UUID job) {}

  private Setup setup() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = Fixtures.tender(admin, TestUsers.create("member"));
    UUID doc =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'brd', 'Doc')"
                    + " RETURNING id",
                tender);
    return version(tender, doc, 1, admin);
  }

  private Setup version(UUID tender, UUID doc, int no, TestUser admin) {
    UUID v = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format,"
            + " size_bytes, sha256, storage_key, status, uploaded_by) VALUES (?, ?, ?, 'a.pdf',"
            + " 'pdf', 100, repeat('a', 64), ?, 'uploading', ?)",
        v,
        doc,
        no,
        "originals/%s/%s/%s.pdf".formatted(tender, doc, v),
        admin.id());
    TestUsers.sql("UPDATE document_versions SET status = 'processing' WHERE id = ?", v);
    UUID job =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id, started_by)"
                    + " VALUES (?, 'document_processing', 'document_version', ?, ?) RETURNING id",
                tender,
                v,
                admin.id());
    return new Setup(tender, doc, v, job);
  }

  private StatusMessage status(Setup s, long seq, String state, Map<String, Object> extra) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("job_id", s.job().toString());
    body.put("job_type", "document_processing");
    body.put("tender_id", s.tender().toString());
    body.put("target", Map.of("type", "document_version", "id", s.version().toString()));
    body.put("seq", seq);
    body.put("state", state);
    body.putAll(extra);
    return StatusMessage.parse(json.readTree(json.writeValueAsString(body)));
  }

  private Outcome apply(StatusMessage m) {
    return jobs.applyStatus(m).block();
  }

  private String jobStatus(Setup s) {
    return (String) TestUsers.scalar("SELECT status FROM jobs WHERE id = ?", s.job());
  }

  private String versionStatus(Setup s) {
    return (String)
        TestUsers.scalar("SELECT status FROM document_versions WHERE id = ?", s.version());
  }

  /** Writes the three parsed files and returns the result body. */
  private tools.jackson.databind.JsonNode writeParsed(Setup s, int pages, int passagesPerPage) {
    String base = "parsed/%s/%s/".formatted(s.tender(), s.version());
    StringBuilder pg = new StringBuilder();
    StringBuilder ps = new StringBuilder();
    UUID section = UUID.nameUUIDFromBytes((s.version() + ":s0").getBytes(StandardCharsets.UTF_8));
    String sections =
        "{\"section_id\":\"%s\",\"parent_section_id\":null,\"title\":\"Scope\",\"level\":1,\"sort_order\":0,\"start_page\":1,\"end_page\":%d}\n"
            .formatted(section, pages);
    int ordinal = 0;
    for (int p = 1; p <= pages; p++) {
      pg.append(
          "{\"page_no\":%d,\"width\":595.2,\"height\":841.9,\"text\":\"t\",\"textless\":false}\n"
              .formatted(p));
      for (int n = 1; n <= passagesPerPage; n++) {
        UUID id =
            UUID.nameUUIDFromBytes(
                (s.version() + ":" + ordinal++).getBytes(StandardCharsets.UTF_8));
        ps.append(
            "{\"passage_id\":\"%s\",\"section_id\":\"%s\",\"page_no\":%d,\"passage_no\":%d,\"text\":\"The supplier shall provide item %d.\",\"bbox\":[[72.0,100.0,300.0,112.0]],\"char_start\":0,\"char_end\":35,\"content_hash\":\"%s\"}\n"
                .formatted(id, section, p, n, ordinal, "b".repeat(64)));
      }
    }
    StorageFixture.put(
        StorageFixture.DERIVED,
        base + "pages.jsonl",
        pg.toString().getBytes(StandardCharsets.UTF_8));
    StorageFixture.put(
        StorageFixture.DERIVED, base + "sections.jsonl", sections.getBytes(StandardCharsets.UTF_8));
    StorageFixture.put(
        StorageFixture.DERIVED,
        base + "passages.jsonl",
        ps.toString().getBytes(StandardCharsets.UTF_8));
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("job_id", s.job().toString());
    body.put("job_type", "document_processing");
    body.put("tender_id", s.tender().toString());
    body.put("target", Map.of("type", "document_version", "id", s.version().toString()));
    body.put("kind", "parsed");
    body.put("part_no", 1);
    body.put("final", true);
    body.put("items", List.of());
    body.put(
        "parsed",
        Map.of(
            "pages",
            base + "pages.jsonl",
            "sections",
            base + "sections.jsonl",
            "passages",
            base + "passages.jsonl",
            "rendered_pdf",
            "rendered/%s/%s/document.pdf".formatted(s.tender(), s.version()),
            "manifest",
            base + "manifest.json",
            "page_count",
            pages,
            "counts",
            Map.of("pages", pages, "sections", 1, "passages", pages * passagesPerPage),
            "source_sha256",
            "a".repeat(64)));
    return json.readTree(json.writeValueAsString(body));
  }

  private Map<String, Object> counts(int pages, int passages) {
    return Map.of(
        "result_parts", 1, "counts", Map.of("pages", pages, "sections", 1, "passages", passages));
  }

  // ---- running
  // --------------------------------------------------------------------------------------

  @Test
  void aRunningStatusStartsTheJobAndCreatesItsSteps() {
    Setup s = setup();
    Outcome o =
        apply(
            status(
                s,
                1,
                "running",
                Map.of(
                    "step",
                    "parse",
                    "progress_percent",
                    20,
                    "steps",
                    List.of("fetch", "parse", "index"))));
    assertThat(o).isInstanceOf(Outcome.Ack.class);
    assertThat(jobStatus(s)).isEqualTo("running");
    assertThat(TestUsers.scalar("SELECT progress_percent FROM jobs WHERE id = ?", s.job()))
        .isEqualTo(20);
    assertThat(TestUsers.scalar("SELECT current_step FROM jobs WHERE id = ?", s.job()))
        .isEqualTo("parse");
    assertThat(
            TestUsers.scalar(
                "SELECT string_agg(name || ':' || status, ',' ORDER BY step_no) FROM job_steps WHERE job_id = ?",
                s.job()))
        .isEqualTo("fetch:completed,parse:running,index:pending");
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM audit_events WHERE object_id = ? AND action = 'job_status_changed'"
                    + " AND source = 'system'",
                s.job()))
        .isEqualTo(1L);
  }

  @Test
  void theActivityLogRecordsEachFactOnceWithPlainText() {
    Setup s = setup();
    Map<String, Object> first =
        Map.of("step", "parse", "steps", List.of("fetch", "parse", "index"));
    apply(status(s, 1, "running", first));
    apply(status(s, 1, "running", first));
    apply(status(s, 2, "running", Map.of("step", "index")));
    apply(
        status(
            s,
            3,
            "failed",
            Map.of(
                "failure",
                Map.of(
                    "code",
                    "llm_unavailable",
                    "message",
                    "secret document text",
                    "retryable",
                    true))));
    assertThat(
            TestUsers.scalar(
                "SELECT string_agg(level || '|' || coalesce(step, '-') || '|' || message, ';'"
                    + " ORDER BY at, id) FROM job_events WHERE job_id = ?",
                s.job()))
        .isEqualTo(
            "info|-|The job started;info|parse|Started step parse;info|index|Started step index;"
                + "error|index|The job failed: The language model did not answer."
                + " Try again in a few minutes.");
  }

  @Test
  void anOlderOrRepeatedStatusChangesNothing() {
    Setup s = setup();
    apply(status(s, 5, "running", Map.of("step", "parse", "progress_percent", 50)));
    apply(status(s, 3, "running", Map.of("step", "fetch", "progress_percent", 10)));
    apply(status(s, 5, "running", Map.of("step", "fetch", "progress_percent", 10)));
    assertThat(TestUsers.scalar("SELECT progress_percent FROM jobs WHERE id = ?", s.job()))
        .isEqualTo(50);
    assertThat(TestUsers.scalar("SELECT current_step FROM jobs WHERE id = ?", s.job()))
        .isEqualTo("parse");
  }

  // ---- parsed result and completion
  // -------------------------------------------------------------------

  @Test
  void parsedFilesAreStoredOnceAndCompletionMakesTheVersionReady() {
    Setup s = setup();
    apply(status(s, 1, "running", Map.of("step", "parse")));
    var body = writeParsed(s, 3, 4);

    assertThat(parsed.apply(body).block()).isInstanceOf(Outcome.Ack.class);
    // A redelivery stores nothing more.
    assertThat(parsed.apply(body).block()).isInstanceOf(Outcome.Ack.class);
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM document_pages WHERE document_version_id = ?", s.version()))
        .isEqualTo(3L);
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM passages WHERE document_version_id = ?", s.version()))
        .isEqualTo(12L);
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM document_sections WHERE document_version_id = ?",
                s.version()))
        .isEqualTo(1L);
    assertThat(versionStatus(s)).isEqualTo("processing");

    Outcome done = apply(status(s, 9, "completed", counts(3, 12)));
    assertThat(done).isInstanceOf(Outcome.Ack.class);
    assertThat(jobStatus(s)).isEqualTo("completed");
    assertThat(versionStatus(s)).isEqualTo("ready");
    assertThat(
            TestUsers.scalar("SELECT page_count FROM document_versions WHERE id = ?", s.version()))
        .isEqualTo(3);
    assertThat(TestUsers.scalar("SELECT progress_percent FROM jobs WHERE id = ?", s.job()))
        .isEqualTo(100);
    assertThat(TestUsers.scalar("SELECT finished_at IS NOT NULL FROM jobs WHERE id = ?", s.job()))
        .isEqualTo(true);
  }

  @Test
  void aCompletionBeforeTheResultIsStoredIsReturnedAndChangesNothing() {
    Setup s = setup();
    apply(status(s, 1, "running", Map.of("step", "parse")));
    Outcome o = apply(status(s, 9, "completed", counts(3, 12)));
    assertThat(o).isInstanceOf(Outcome.Retry.class);
    assertThat(((Outcome.Retry) o).delay().toSeconds()).isEqualTo(5);
    assertThat(jobStatus(s)).isEqualTo("running");
    assertThat(versionStatus(s)).isEqualTo("processing");

    // Fewer rows than the worker counted is the same: it is not ready.
    parsed.apply(writeParsed(s, 3, 4)).block();
    assertThat(apply(status(s, 9, "completed", counts(3, 13)))).isInstanceOf(Outcome.Retry.class);
    assertThat(apply(status(s, 9, "completed", counts(3, 12)))).isInstanceOf(Outcome.Ack.class);
    assertThat(jobStatus(s)).isEqualTo("completed");
  }

  @Test
  void aNewerVersionReadySupersedesTheOlderOne() {
    Setup first = setup();
    parsed.apply(writeParsed(first, 1, 1)).block();
    apply(status(first, 1, "completed", counts(1, 1)));
    TestUser admin = TestUsers.create("admin");
    Setup second = version(first.tender(), first.document(), 2, admin);
    assertThat(
            TestUsers.scalar(
                "SELECT superseded_at IS NULL FROM document_versions WHERE id = ?",
                first.version()))
        .isEqualTo(true);
    parsed.apply(writeParsed(second, 1, 1)).block();
    apply(status(second, 1, "completed", counts(1, 1)));
    assertThat(
            TestUsers.scalar(
                "SELECT superseded_at IS NOT NULL FROM document_versions WHERE id = ?",
                first.version()))
        .isEqualTo(true);
    assertThat(
            TestUsers.scalar(
                "SELECT superseded_at IS NULL FROM document_versions WHERE id = ?",
                second.version()))
        .isEqualTo(true);
  }

  // ---- failure and terminal states
  // ----------------------------------------------------------------------

  @Test
  void aFailureStatusFailsTheJobAndTheVersionWithTheReason() {
    Setup s = setup();
    apply(status(s, 1, "running", Map.of("step", "parse", "steps", List.of("fetch", "parse"))));
    Outcome o =
        apply(
            status(
                s,
                2,
                "failed",
                Map.of(
                    "failure",
                    Map.of(
                        "code", "password_protected",
                        "message", "The file is protected",
                        "retryable", false))));
    assertThat(o).isInstanceOf(Outcome.Ack.class);
    assertThat(jobStatus(s)).isEqualTo("failed");
    assertThat(TestUsers.scalar("SELECT failure_code FROM jobs WHERE id = ?", s.job()))
        .isEqualTo("password_protected");
    assertThat(versionStatus(s)).isEqualTo("failed");
    assertThat(
            TestUsers.scalar(
                "SELECT failure_reason FROM document_versions WHERE id = ?", s.version()))
        .isEqualTo("password_protected");
    assertThat(
            TestUsers.scalar(
                "SELECT status FROM job_steps WHERE job_id = ? AND name = 'parse'", s.job()))
        .isEqualTo("failed");
  }

  @Test
  void aFailureCodeOutsideTheListBecomesOther() {
    Setup s = setup();
    apply(
        status(
            s,
            1,
            "failed",
            Map.of("failure", Map.of("code", "other", "message", "boom", "retryable", true))));
    assertThat(TestUsers.scalar("SELECT failure_code FROM jobs WHERE id = ?", s.job()))
        .isEqualTo("other");
    assertThat(
            TestUsers.scalar(
                "SELECT failure_reason FROM document_versions WHERE id = ?", s.version()))
        .isEqualTo("other");
  }

  @Test
  void messagesForAFinishedJobAreDropped() {
    Setup s = setup();
    apply(
        status(
            s,
            1,
            "failed",
            Map.of("failure", Map.of("code", "other", "message", "x", "retryable", false))));
    Outcome late = apply(status(s, 2, "running", Map.of("step", "parse", "progress_percent", 40)));
    assertThat(late).isInstanceOf(Outcome.Ack.class);
    assertThat(jobStatus(s)).isEqualTo("failed");
    Outcome lateComplete = apply(status(s, 3, "completed", counts(1, 1)));
    assertThat(lateComplete).isInstanceOf(Outcome.Ack.class);
    assertThat(jobStatus(s)).isEqualTo("failed");
    // and a stored result for a finished job is not stored either
    assertThat(parsed.apply(writeParsed(s, 1, 1)).block()).isInstanceOf(Outcome.Ack.class);
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM passages WHERE document_version_id = ?", s.version()))
        .isEqualTo(0L);
  }

  @Test
  void aStatusForAnUnknownJobOrAnotherTenderGoesToTheDeadLetterStream() {
    Setup s = setup();
    Setup other = setup();
    StatusMessage unknown =
        StatusMessage.parse(
            json.readTree(
                "{\"job_id\":\"%s\",\"job_type\":\"document_processing\",\"tender_id\":\"%s\",\"target\":{\"type\":\"document_version\",\"id\":\"%s\"},\"seq\":1,\"state\":\"running\"}"
                    .formatted(UUID.randomUUID(), s.tender(), s.version())));
    assertThat(apply(unknown)).isInstanceOf(Outcome.DeadLetter.class);

    StatusMessage mismatch =
        StatusMessage.parse(
            json.readTree(
                "{\"job_id\":\"%s\",\"job_type\":\"document_processing\",\"tender_id\":\"%s\",\"target\":{\"type\":\"document_version\",\"id\":\"%s\"},\"seq\":1,\"state\":\"running\"}"
                    .formatted(s.job(), other.tender(), s.version())));
    assertThat(apply(mismatch)).isInstanceOf(Outcome.DeadLetter.class);
    assertThat(jobStatus(s)).isEqualTo("queued");
  }

  @Test
  void aStatusFromAnUploadEventFindsTheOpenJobByTarget() {
    Setup s = setup();
    StatusMessage m =
        StatusMessage.parse(
            json.readTree(
                "{\"job_id\":null,\"job_type\":\"document_processing\",\"tender_id\":\"%s\",\"target\":{\"type\":\"document_version\",\"id\":\"%s\"},\"seq\":1,\"state\":\"running\",\"step\":\"fetch\"}"
                    .formatted(s.tender(), s.version())));
    assertThat(apply(m)).isInstanceOf(Outcome.Ack.class);
    assertThat(jobStatus(s)).isEqualTo("running");
  }

  @Test
  void parsedKeysOutsideTheFixedPathsAreNotRead() {
    Setup s = setup();
    var body = writeParsed(s, 1, 1);
    var tampered =
        json.readTree(
            body.toString()
                .replace(
                    "parsed/%s/%s/passages.jsonl".formatted(s.tender(), s.version()),
                    "originals/%s/%s/x.pdf".formatted(s.tender(), s.version())));
    assertThat(parsed.apply(tampered).block()).isInstanceOf(Outcome.DeadLetter.class);
  }

  @Test
  void aJobThatCannotStoreItsResultIsFailedByIdOnce() {
    Setup s = setup();
    jobs.failById(s.job(), "other", "The result could not be stored").block();
    assertThat(jobStatus(s)).isEqualTo("failed");
    assertThat(versionStatus(s)).isEqualTo("failed");
    long events =
        (Long)
            TestUsers.scalar(
                "SELECT count(*) FROM audit_events WHERE object_id = ? AND action = 'job_status_changed'",
                s.job());
    jobs.failById(s.job(), "other", "again").block();
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM audit_events WHERE object_id = ? AND action = 'job_status_changed'",
                s.job()))
        .isEqualTo(events);
  }
}
