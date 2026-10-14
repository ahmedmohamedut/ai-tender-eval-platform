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

/** PI 5.5 to 5.7 for kind grades: stored once with their citations, current swapped, counted. */
class GradeResultIT extends AbstractApiIT {

  @Autowired JobProcessor jobs;
  @Autowired GradeResultProcessor results;

  private final JsonMapper json = JsonMapper.builder().build();

  private record Setup(
      UUID tender,
      TestUser chair,
      TestUser member,
      UUID tv,
      UUID pv,
      UUID dv,
      UUID passage,
      UUID baseline,
      UUID r1,
      UUID r2,
      UUID job) {}

  private Setup setup() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser member = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, member);
    UUID r1 = requirement(tender, "Encrypt data at rest.");
    UUID r2 = requirement(tender, "Provide 24/7 support.");
    UUID baseline =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by)"
                    + " VALUES (?, 1, 2, ?) RETURNING id",
                tender,
                chair.id());
    for (UUID r : List.of(r1, r2)) {
      TestUsers.sql(
          "INSERT INTO baseline_requirements (baseline_id, requirement_id, display_no, text, importance,"
              + " content_hash) VALUES (?, ?, 'REQ', 'x', 'mandatory', repeat('c', 64))",
          baseline,
          r);
    }
    UUID vendor =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO vendors (name, normalized_name) VALUES ('Gr', ?) RETURNING id",
                UUID.randomUUID().toString());
    UUID tv =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO tender_vendors (tender_id, vendor_id, added_by) VALUES (?, ?, ?) RETURNING id",
                tender,
                vendor,
                admin.id());
    UUID pv =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by) VALUES (?, 1, ?) RETURNING id",
                tv,
                admin.id());
    UUID doc =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'proposal', 'P') RETURNING id",
                tender);
    UUID dv = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format, size_bytes,"
            + " sha256, storage_key, status, uploaded_by) VALUES (?, ?, 1, 'p.pdf', 'pdf', 10,"
            + " repeat('a', 64), ?, 'uploading', ?)",
        dv,
        doc,
        "originals/%s/%s/%s.pdf".formatted(tender, doc, dv),
        admin.id());
    TestUsers.sql(
        "INSERT INTO proposal_version_files (proposal_version_id, document_version_id, file_role)"
            + " VALUES (?, ?, 'main')",
        pv,
        dv);
    UUID passage = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO passages (id, document_version_id, page_no, passage_no, text, bbox, char_start,"
            + " char_end, content_hash) VALUES (?, ?, 4, 1, 'We encrypt all data at rest.',"
            + " '[]'::jsonb, 0, 28, repeat('b', 64))",
        passage,
        dv);
    TestUsers.sql("UPDATE proposal_versions SET status = 'in_screening' WHERE id = ?", pv);
    TestUsers.sql("UPDATE proposal_versions SET status = 'accepted' WHERE id = ?", pv);
    UUID job =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id) VALUES (?, 'evaluation',"
                    + " 'proposal_version', ?) RETURNING id",
                tender,
                pv);
    return new Setup(tender, chair, member, tv, pv, dv, passage, baseline, r1, r2, job);
  }

  private static UUID requirement(UUID tender, String text) {
    return (UUID)
        TestUsers.scalar(
            "INSERT INTO requirements (tender_id, text, importance, origin) VALUES (?, ?, 'mandatory',"
                + " 'manual') RETURNING id",
            tender,
            text);
  }

  private StatusMessage status(Setup s, long seq, String state, Map<String, Object> extra) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("job_id", s.job().toString());
    body.put("job_type", "evaluation");
    body.put("tender_id", s.tender().toString());
    body.put("target", Map.of("type", "proposal_version", "id", s.pv().toString()));
    body.put("seq", seq);
    body.put("state", state);
    body.putAll(extra);
    return StatusMessage.parse(json.readTree(json.writeValueAsString(body)));
  }

  private Map<String, Object> running(Setup s) {
    return Map.of(
        "step",
        "grade",
        "progress_percent",
        10,
        "run",
        Map.of(
            "model_name",
            "stub",
            "prompt_version",
            "evaluation-1.0.0",
            "input_scope",
            Map.of("baseline_id", s.baseline().toString())));
  }

  private Map<String, Object> graded(Setup s, UUID item, UUID requirement, String grade) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("item_id", item.toString());
    o.put("requirement_id", requirement.toString());
    o.put("requirement_hash", "c".repeat(64));
    o.put("outcome", "graded");
    o.put("grade", grade);
    o.put("rationale_summary", "Encryption at rest is stated");
    o.put("rationale", "The proposal states that all data is encrypted at rest.");
    o.put("missing_elements", List.of());
    o.put("confidence", 0.9);
    o.put(
        "citations",
        List.of(
            Map.of(
                "citation_no",
                1,
                "document_version_id",
                s.dv().toString(),
                "passage_id",
                s.passage().toString(),
                "page_no",
                4,
                "excerpt",
                "We encrypt all data at rest.")));
    return o;
  }

  private static Map<String, Object> withMissing(Map<String, Object> grade, String... elements) {
    grade.put("missing_elements", List.of(elements));
    return grade;
  }

  private Map<String, Object> failed(UUID item, UUID requirement) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("item_id", item.toString());
    o.put("requirement_id", requirement.toString());
    o.put("requirement_hash", "c".repeat(64));
    o.put("outcome", "failed");
    o.put("failure_code", "ungrounded");
    return o;
  }

  private JsonNode batch(Setup s, int part, boolean last, List<Map<String, Object>> items) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("job_id", s.job().toString());
    body.put("job_type", "evaluation");
    body.put("tender_id", s.tender().toString());
    body.put("target", Map.of("type", "proposal_version", "id", s.pv().toString()));
    body.put("kind", "grades");
    body.put("part_no", part);
    body.put("final", last);
    body.put("items", items);
    return json.readTree(json.writeValueAsString(body));
  }

  @Test
  void gradesAreStoredOnceWithCitationsAndTheJobCompletesAndCreatesTheMembersRows() {
    Setup s = setup();
    assertThat(TestUsers.scalar("SELECT status FROM proposal_versions WHERE id = ?", s.pv()))
        .isEqualTo("evaluating");
    // the run is not recorded yet: asked again later
    assertThat(
            results
                .apply(
                    batch(
                        s,
                        1,
                        true,
                        List.of(graded(s, UUID.randomUUID(), s.r1(), "fully_compliant"))))
                .block())
        .isInstanceOf(Outcome.Retry.class);
    jobs.applyStatus(status(s, 1, "running", running(s))).block();

    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    JsonNode part =
        batch(s, 1, true, List.of(graded(s, a, s.r1(), "fully_compliant"), failed(b, s.r2())));
    assertThat(results.apply(part).block()).isInstanceOf(Outcome.Ack.class);
    assertThat(results.apply(part).block()).isInstanceOf(Outcome.Ack.class); // redelivered
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM ai_grades WHERE proposal_version_id = ?", s.pv()))
        .isEqualTo(2L);
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM grade_citations c JOIN ai_grades g ON g.id = c.ai_grade_id"
                    + " WHERE g.proposal_version_id = ?",
                s.pv()))
        .isEqualTo(1L);

    // wrong count held back, right count completes the job and the proposal
    assertThat(
            jobs.applyStatus(
                    status(
                        s,
                        2,
                        "completed",
                        Map.of("result_parts", 1, "counts", Map.of("grades", 3))))
                .block())
        .isInstanceOf(Outcome.Retry.class);
    assertThat(
            jobs.applyStatus(
                    status(
                        s,
                        3,
                        "completed_with_warnings",
                        Map.of(
                            "result_parts",
                            1,
                            "counts",
                            Map.of("grades", 2),
                            "warnings",
                            List.of(
                                Map.of(
                                    "code",
                                    "row_failed",
                                    "message",
                                    "One row failed",
                                    "count",
                                    1)))))
                .block())
        .isInstanceOf(Outcome.Ack.class);
    assertThat(TestUsers.scalar("SELECT status FROM jobs WHERE id = ?", s.job()))
        .isEqualTo("completed_with_warnings");
    assertThat(TestUsers.scalar("SELECT status FROM proposal_versions WHERE id = ?", s.pv()))
        .isEqualTo("evaluated");
    // every committee member (the Chair too) has an evaluation with one row per graded requirement
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM member_evaluations WHERE tender_vendor_id = ?", s.tv()))
        .isEqualTo(2L);
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM member_evaluation_rows r JOIN member_evaluations e"
                    + " ON e.id = r.member_evaluation_id WHERE e.tender_vendor_id = ?",
                s.tv()))
        .isEqualTo(4L);
  }

  @Test
  void aNewGradeReplacesTheCurrentOneAndTheOldRowStays() {
    Setup s = setup();
    jobs.applyStatus(status(s, 1, "running", running(s))).block();
    UUID first = UUID.randomUUID();
    results.apply(batch(s, 1, true, List.of(failed(first, s.r1())))).block();
    UUID second = UUID.randomUUID();
    results
        .apply(
            batch(
                s,
                2,
                true,
                List.of(
                    withMissing(graded(s, second, s.r1(), "partially_compliant"), "Key rotation"))))
        .block();
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM ai_grades WHERE proposal_version_id = ? AND requirement_id = ?",
                s.pv(),
                s.r1()))
        .isEqualTo(2L);
    assertThat(
            TestUsers.scalar(
                "SELECT grade FROM ai_grades WHERE proposal_version_id = ? AND requirement_id = ?"
                    + " AND is_current",
                s.pv(),
                s.r1()))
        .isEqualTo("partially_compliant");
  }

  @Test
  void aGradeWithoutEvidenceRollsTheWholeBatchBack() {
    Setup s = setup();
    jobs.applyStatus(status(s, 1, "running", running(s))).block();
    Map<String, Object> bad = graded(s, UUID.randomUUID(), s.r1(), "fully_compliant");
    bad.put("citations", List.of());
    Outcome out =
        results
            .apply(
                batch(
                    s,
                    1,
                    true,
                    List.of(graded(s, UUID.randomUUID(), s.r2(), "fully_compliant"), bad)))
            .block();
    assertThat(out).isInstanceOf(Outcome.Retry.class);
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM ai_grades WHERE proposal_version_id = ?", s.pv()))
        .isEqualTo(0L);
  }
}
