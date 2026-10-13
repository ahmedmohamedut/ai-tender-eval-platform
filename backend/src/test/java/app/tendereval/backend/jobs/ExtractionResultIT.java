package app.tendereval.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** PI 5.5 to 5.7 for kind requirements: stored once, flagged, counted, run recorded. */
class ExtractionResultIT extends AbstractApiIT {

  @Autowired JobProcessor jobs;
  @Autowired RequirementResultProcessor results;

  private final JsonMapper json = JsonMapper.builder().build();

  private record Setup(UUID tender, UUID version, UUID passage, UUID job, UUID category) {}

  private Setup setup() {
    TestUser admin = TestUsers.create("admin");
    TestUser member = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, member);
    UUID doc =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'rfp', 'RFP') RETURNING id",
                tender);
    UUID v = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format, size_bytes,"
            + " sha256, storage_key, status, uploaded_by) VALUES (?, ?, 1, 'rfp.pdf', 'pdf', 10,"
            + " repeat('a', 64), ?, 'uploading', ?)",
        v,
        doc,
        "originals/%s/%s/%s.pdf".formatted(tender, doc, v),
        admin.id());
    TestUsers.sql("UPDATE document_versions SET status = 'processing' WHERE id = ?", v);
    TestUsers.sql("UPDATE document_versions SET status = 'ready', page_count = 3 WHERE id = ?", v);
    UUID passage = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO passages (id, document_version_id, page_no, passage_no, text, bbox, char_start,"
            + " char_end, content_hash) VALUES (?, ?, 1, 1, 'The supplier shall encrypt all data.',"
            + " '[[1,1,2,2]]'::jsonb, 0, 35, repeat('b', 64))",
        passage,
        v);
    UUID job =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id, started_by)"
                    + " VALUES (?, 'requirement_extraction', 'tender', ?, ?) RETURNING id",
                tender,
                tender,
                member.id());
    UUID category =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO requirement_categories (name) VALUES (?) RETURNING id",
                "Sec " + UUID.randomUUID());
    return new Setup(tender, v, passage, job, category);
  }

  private StatusMessage status(Setup s, long seq, String state, Map<String, Object> extra) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("job_id", s.job().toString());
    body.put("job_type", "requirement_extraction");
    body.put("tender_id", s.tender().toString());
    body.put("target", Map.of("type", "tender", "id", s.tender().toString()));
    body.put("seq", seq);
    body.put("state", state);
    body.putAll(extra);
    return StatusMessage.parse(json.readTree(json.writeValueAsString(body)));
  }

  private JsonNode batch(Setup s, UUID itemA, UUID itemB, double confidenceB) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("item_id", itemA.toString());
    a.put("text", "The supplier shall encrypt all data at rest.");
    a.put("category_id", s.category().toString());
    a.put("importance", "mandatory");
    a.put("acceptance_criteria", List.of("AES-256 or stronger", "Keys held in a KMS"));
    a.put("confidence", 0.9);
    a.put(
        "sources",
        List.of(
            Map.of(
                "document_version_id",
                s.version().toString(),
                "passage_id",
                s.passage().toString(),
                "page_no",
                1,
                "quote",
                "shall encrypt all data")));
    a.put("flags", List.of());
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("item_id", itemB.toString());
    b.put("text", "Data must be encrypted when stored.");
    b.put("category_id", UUID.randomUUID().toString()); // unknown category: stored without one
    b.put("importance", "optional");
    b.put("acceptance_criteria", "");
    b.put("confidence", confidenceB);
    b.put("sources", List.of());
    b.put(
        "flags",
        List.of(
            Map.of(
                "flag_type", "possible_duplicate",
                "detail", "Says the same as the first one",
                "related_item_id", itemA.toString())));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("job_id", s.job().toString());
    body.put("job_type", "requirement_extraction");
    body.put("tender_id", s.tender().toString());
    body.put("target", Map.of("type", "tender", "id", s.tender().toString()));
    body.put("kind", "requirements");
    body.put("part_no", 1);
    body.put("final", true);
    body.put("items", List.of(a, b));
    return json.readTree(json.writeValueAsString(body));
  }

  private Map<String, Object> run() {
    return Map.of(
        "run",
        Map.of(
            "model_name", "gemma-4-26B-A4B",
            "model_version", "1",
            "prompt_version", "extraction-1.0.0",
            "input_scope", Map.of("document_versions", 1)),
        "step",
        "extract",
        "progress_percent",
        10);
  }

  @Test
  void theRunIsRecordedAndTheBatchStoredOnceWithSourcesAndFlags() {
    Setup s = setup();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    // a result before the run record is returned, not lost
    assertThat(results.apply(batch(s, a, b, 0.3)).block()).isInstanceOf(Outcome.Retry.class);

    assertThat(jobs.applyStatus(status(s, 1, "running", run())).block())
        .isInstanceOf(Outcome.Ack.class);
    assertThat(
            TestUsers.scalar(
                "SELECT model_name || '|' || prompt_version FROM ai_runs WHERE job_id = ?",
                s.job()))
        .isEqualTo("gemma-4-26B-A4B|extraction-1.0.0");

    assertThat(results.apply(batch(s, a, b, 0.3)).block()).isInstanceOf(Outcome.Ack.class);
    // a redelivery changes nothing
    assertThat(results.apply(batch(s, a, b, 0.3)).block()).isInstanceOf(Outcome.Ack.class);

    assertThat(
            TestUsers.scalar("SELECT count(*) FROM requirements WHERE tender_id = ?", s.tender()))
        .isEqualTo(2L);
    assertThat(
            TestUsers.scalar(
                "SELECT origin || '|' || review_state || '|' || display_no FROM requirements WHERE source_item_id = ?",
                a))
        .isEqualTo("ai|unreviewed|REQ-001");
    assertThat(
            TestUsers.scalar(
                "SELECT acceptance_criteria FROM requirements WHERE source_item_id = ?", a))
        .isEqualTo("AES-256 or stronger\nKeys held in a KMS");
    assertThat(
            TestUsers.scalar(
                "SELECT ai_original ->> 'text' FROM requirements WHERE source_item_id = ?", a))
        .isEqualTo("The supplier shall encrypt all data at rest.");
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM requirement_sources s JOIN requirements r ON r.id = s.requirement_id"
                    + " WHERE r.source_item_id = ? AND s.passage_id = ? AND s.quote = 'shall encrypt all data'",
                a,
                s.passage()))
        .isEqualTo(1L);
    // the unknown category is dropped, the duplicate flag points at the first row,
    // and low confidence (0.3 under 0.6) adds its own flag
    assertThat(
            TestUsers.scalar(
                "SELECT category_id IS NULL FROM requirements WHERE source_item_id = ?", b))
        .isEqualTo(true);
    assertThat(
            TestUsers.scalar(
                "SELECT string_agg(f.flag_type, ',' ORDER BY f.flag_type) FROM requirement_flags f"
                    + " JOIN requirements r ON r.id = f.requirement_id WHERE r.source_item_id = ?",
                b))
        .isEqualTo("low_confidence,possible_duplicate");
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM requirement_flags f JOIN requirements rel ON rel.id = f.related_requirement_id"
                    + " WHERE rel.source_item_id = ? AND f.flag_type = 'possible_duplicate'",
                a))
        .isEqualTo(1L);
  }

  @Test
  void completionWaitsForTheStoredRowsAndClosesTheRun() {
    Setup s = setup();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    jobs.applyStatus(status(s, 1, "running", run())).block();
    Map<String, Object> done = Map.of("result_parts", 1, "counts", Map.of("requirements", 2));
    // nothing stored yet: the completion is returned
    assertThat(jobs.applyStatus(status(s, 9, "completed", done)).block())
        .isInstanceOf(Outcome.Retry.class);
    results.apply(batch(s, a, b, 0.95)).block();
    assertThat(jobs.applyStatus(status(s, 9, "completed", done)).block())
        .isInstanceOf(Outcome.Ack.class);
    assertThat(TestUsers.scalar("SELECT status FROM jobs WHERE id = ?", s.job()))
        .isEqualTo("completed");
    assertThat(TestUsers.scalar("SELECT outcome FROM ai_runs WHERE job_id = ?", s.job()))
        .isEqualTo("succeeded");
    // and a late batch for a finished job is dropped
    assertThat(results.apply(batch(s, UUID.randomUUID(), UUID.randomUUID(), 0.9)).block())
        .isInstanceOf(Outcome.Ack.class);
    assertThat(
            TestUsers.scalar("SELECT count(*) FROM requirements WHERE tender_id = ?", s.tender()))
        .isEqualTo(2L);
  }

  @Test
  void aFailedExtractionKeepsWhatWasStoredAndClosesTheRunAsFailed() {
    Setup s = setup();
    jobs.applyStatus(status(s, 1, "running", run())).block();
    results.apply(batch(s, UUID.randomUUID(), UUID.randomUUID(), 0.9)).block();
    jobs.applyStatus(
            status(
                s,
                2,
                "failed",
                Map.of(
                    "failure",
                    Map.of("code", "llm_unavailable", "message", "down", "retryable", true))))
        .block();
    assertThat(TestUsers.scalar("SELECT failure_code FROM jobs WHERE id = ?", s.job()))
        .isEqualTo("llm_unavailable");
    assertThat(TestUsers.scalar("SELECT outcome FROM ai_runs WHERE job_id = ?", s.job()))
        .isEqualTo("failed");
    assertThat(
            TestUsers.scalar("SELECT count(*) FROM requirements WHERE tender_id = ?", s.tender()))
        .isEqualTo(2L); // batches already stored stay (UX-JOB-05)
  }
}
