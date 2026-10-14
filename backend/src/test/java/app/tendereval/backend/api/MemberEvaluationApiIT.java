package app.tendereval.backend.api;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** UX-EVA-01..18: the member's own rows, decisions, independence and submit (DM Independence). */
class MemberEvaluationApiIT extends AbstractApiIT {

  private record World(
      TestUser admin,
      TestUser chair,
      TestUser m1,
      TestUser m2,
      UUID tender,
      UUID tv,
      UUID pv,
      UUID r1,
      UUID r2,
      UUID dv,
      UUID passage) {}

  private World world(boolean synced) {
    return world(synced, true);
  }

  private World world(boolean synced, boolean m2Declared) {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser m1 = TestUsers.create("member");
    TestUser m2 = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, m1, m2);
    TestUsers.sql("UPDATE tenders SET status = 'requirements' WHERE id = ?", tender);
    TestUsers.sql("UPDATE tenders SET status = 'evaluation' WHERE id = ?", tender);
    UUID r1 = requirement(tender, "Encrypt data at rest.");
    UUID r2 = requirement(tender, "Provide support all day.");
    UUID baseline =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by)"
                    + " VALUES (?, 1, 2, ?) RETURNING id",
                tender,
                chair.id());
    int n = 0;
    for (UUID r : List.of(r1, r2)) {
      n++;
      TestUsers.sql(
          "INSERT INTO baseline_requirements (baseline_id, requirement_id, display_no, text,"
              + " importance, content_hash) VALUES (?, ?, ?, 'x', 'mandatory', repeat('c', 64))",
          baseline,
          r,
          "REQ-00" + n);
    }
    TestUsers.sql(
        "INSERT INTO scoring_models (tender_id, baseline_id, points_fully, points_partially,"
            + " points_non, mandatory_rule) VALUES (?, ?, 1, 0.5, 0, 'flag_only')",
        tender,
        baseline);
    UUID vendor =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO vendors (name, normalized_name) VALUES ('Me', ?) RETURNING id",
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
    for (TestUser u : m2Declared ? List.of(chair, m1, m2) : List.of(chair, m1)) {
      TestUsers.sql(
          "INSERT INTO conflict_declarations (tender_vendor_id, user_id, has_conflict) VALUES (?, ?, false)",
          tv,
          u.id());
    }
    TestUsers.sql("UPDATE proposal_versions SET status = 'in_screening' WHERE id = ?", pv);
    TestUsers.sql("UPDATE proposal_versions SET status = 'accepted' WHERE id = ?", pv);
    UUID job =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id) VALUES (?, 'evaluation',"
                    + " 'proposal_version', ?) RETURNING id",
                tender,
                pv);
    UUID run =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO ai_runs (job_id, run_type, model_name, prompt_version) VALUES (?,"
                    + " 'evaluation', 'stub', 'e-1') RETURNING id",
                job);
    // r1: fully compliant with a citation; r2: failed (needs the member's own grade)
    TestUsers.sql("UPDATE jobs SET status = 'running', started_at = now() WHERE id = ?", job);
    UUID g1 = UUID.randomUUID();
    TestUsers.sql(
        "WITH g AS (INSERT INTO ai_grades (id, source_item_id, ai_run_id, proposal_version_id,"
            + " baseline_id, requirement_id, outcome, grade, rationale_summary, rationale,"
            + " confidence, requirement_hash) VALUES (?, gen_random_uuid(), ?, ?, ?, ?, 'graded',"
            + " 'fully_compliant', 'Stated', 'The proposal states it.', 0.9, repeat('c', 64))"
            + " RETURNING id) INSERT INTO grade_citations (ai_grade_id, citation_no,"
            + " document_version_id, passage_id, page_no, excerpt)"
            + " SELECT id, 1, ?, ?, 4, 'We encrypt all data at rest.' FROM g",
        g1,
        run,
        pv,
        baseline,
        r1,
        dv,
        passage);
    TestUsers.sql(
        "INSERT INTO ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id,"
            + " requirement_id, outcome, failure_code, requirement_hash) VALUES (gen_random_uuid(),"
            + " ?, ?, ?, ?, 'failed', 'ungrounded', repeat('c', 64))",
        run,
        pv,
        baseline,
        r2);
    if (synced) {
      TestUsers.sql("UPDATE jobs SET status = 'completed', finished_at = now() WHERE id = ?", job);
      TestUsers.scalar("SELECT count(*) FROM ingest_sync_member_evaluations(?)", pv);
    }
    return new World(admin, chair, m1, m2, tender, tv, pv, r1, r2, dv, passage);
  }

  private static UUID requirement(UUID tender, String text) {
    return (UUID)
        TestUsers.scalar(
            "INSERT INTO requirements (tender_id, text, importance, origin) VALUES (?, ?, 'mandatory',"
                + " 'manual') RETURNING id",
            tender,
            text);
  }

  private WebTestClient.RequestBodySpec call(String method, String uri, TestUser u) {
    return web.method(HttpMethod.valueOf(method))
        .uri(uri)
        .header("Authorization", "Bearer " + tokenFor(u))
        .header("Idempotency-Key", "m-" + UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON);
  }

  private String rows(World w) {
    return "/tender-vendors/" + w.tv() + "/evaluation/rows";
  }

  private int version(World w, TestUser u, UUID requirement) {
    byte[] out =
        call("GET", rows(w) + "/" + requirement, u)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult()
            .getResponseBody();
    return JsonPath.read(new String(out), "$.row_version");
  }

  @Test
  void rowsAppearWhileTheJobRunsAndDecisionsWaitForItsEnd() {
    World w = world(false);
    call("GET", rows(w), w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(2)
        .jsonPath("$.items[0].ai.grade")
        .isEqualTo("fully_compliant")
        .jsonPath("$.items[0].ai.citations[0].page_no")
        .isEqualTo(4)
        .jsonPath("$.items[1].flags[0]")
        .isEqualTo("not_evaluated");
    call("PATCH", rows(w) + "/" + w.r1(), w.m1())
        .header("If-Match", "\"1\"")
        .bodyValue(Map.of("action", "agree"))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("evaluation_not_ready");
    call("GET", "/tender-vendors/" + w.tv() + "/evaluation", w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("not_started")
        .jsonPath("$.actions.decide_rows.allowed")
        .isEqualTo(false);
  }

  @Test
  void aMemberAgreesOverridesAndFlagsWithTheRulesOfTheDataModel() {
    World w = world(true);
    String row1 = rows(w) + "/" + w.r1();
    String row2 = rows(w) + "/" + w.r2();
    int v1 = version(w, w.m1(), w.r1());
    // a stale version is refused
    call("PATCH", row1, w.m1())
        .header("If-Match", "\"" + (v1 + 5) + "\"")
        .bodyValue(Map.of("action", "agree"))
        .exchange()
        .expectStatus()
        .isEqualTo(412);
    // agree takes the AI grade
    call("PATCH", row1, w.m1())
        .header("If-Match", "\"" + v1 + "\"")
        .bodyValue(Map.of("action", "agree"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.member.review_state")
        .isEqualTo("agreed")
        .jsonPath("$.member.grade")
        .isEqualTo("fully_compliant");
    // no AI grade to agree with on the failed row; overriding needs a grade and a note of 10+
    int v2 = version(w, w.m1(), w.r2());
    call("PATCH", row2, w.m1())
        .header("If-Match", "\"" + v2 + "\"")
        .bodyValue(Map.of("action", "agree"))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("no_ai_grade");
    call("PATCH", row2, w.m1())
        .header("If-Match", "\"" + v2 + "\"")
        .bodyValue(Map.of("action", "override", "grade", "non_compliant", "note", "short"))
        .exchange()
        .expectStatus()
        .isEqualTo(422);
    call("PATCH", row2, w.m1())
        .header("If-Match", "\"" + v2 + "\"")
        .bodyValue(
            Map.of(
                "action",
                "override",
                "grade",
                "partially_compliant",
                "note",
                "Support hours are only in the annex"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.member.review_state")
        .isEqualTo("overridden")
        .jsonPath("$.flags[?(@ == 'overridden')]")
        .exists();
    // the first decision started the evaluation
    call("GET", "/tender-vendors/" + w.tv() + "/evaluation", w.m1())
        .exchange()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("in_progress")
        .jsonPath("$.summary.reviewed")
        .isEqualTo(2)
        .jsonPath("$.summary.counts.fully_compliant")
        .isEqualTo(1)
        .jsonPath("$.summary.counts.partially_compliant")
        .isEqualTo(1)
        .jsonPath("$.actions.submit.allowed")
        .isEqualTo(true);
    // another member sees only their own rows, never m1's decisions
    call("GET", rows(w), w.m2())
        .exchange()
        .expectBody()
        .jsonPath("$.items[0].member.review_state")
        .isEqualTo("not_reviewed")
        .jsonPath("$.items[1].member.review_state")
        .isEqualTo("not_reviewed");
  }

  @Test
  void submitNeedsEveryRowDecidedAndPeersOpenOnlyAfterTheOwnSubmission() {
    World w = world(true);
    String submit = "/tender-vendors/" + w.tv() + "/evaluation/submit";
    call("POST", submit, w.m1())
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("precheck_failed")
        .jsonPath("$.failures.length()")
        .isEqualTo(2);
    for (TestUser u : List.of(w.m1(), w.m2())) {
      call("POST", "/tender-vendors/" + w.tv() + "/evaluation/rows/bulk", u)
          .bodyValue(Map.of("requirement_ids", List.of(w.r1(), w.r2()), "action", "agree"))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.updated")
          .isEqualTo(1)
          .jsonPath("$.skipped[0].code")
          .isEqualTo("no_ai_grade");
      int v = version(w, u, w.r2());
      call("PATCH", rows(w) + "/" + w.r2(), u)
          .header("If-Match", "\"" + v + "\"")
          .bodyValue(
              Map.of(
                  "action",
                  "override",
                  "grade",
                  "non_compliant",
                  "note",
                  "Nothing in the proposal covers this"))
          .exchange()
          .expectStatus()
          .isOk();
    }
    String peers = "/tender-vendors/" + w.tv() + "/evaluation/peers";
    call("GET", peers, w.m1())
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("peers_locked");

    call("POST", submit, w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("submitted");
    // submitted: read only
    int v = version(w, w.m1(), w.r1());
    call("PATCH", rows(w) + "/" + w.r1(), w.m1())
        .header("If-Match", "\"" + v + "\"")
        .bodyValue(Map.of("action", "flag_clarification", "note", "Why?"))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("evaluation_submitted");
    // m1 has submitted but m2 has not: m1 sees nothing of m2 yet
    call("GET", peers, w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(0);
    // m2 submits: now each reads the other's decisions
    call("POST", submit, w.m2()).exchange().expectStatus().isOk();
    call("GET", peers, w.m2())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(2)
        .jsonPath("$.items[0].grade")
        .isNotEmpty();
    // the Chair has not submitted: peers stay closed for them, and the administrator reads nothing
    call("GET", peers, w.chair()).exchange().expectStatus().isForbidden();
    call("GET", rows(w), w.admin()).exchange().expectStatus().isForbidden();
  }

  @Test
  void theChairReopensASubmittedEvaluationWithAReasonAndTheMemberEditsAgain() {
    World w = world(true);
    for (UUID r : List.of(w.r1(), w.r2())) {
      int v = version(w, w.m1(), r);
      call("PATCH", rows(w) + "/" + r, w.m1())
          .header("If-Match", "\"" + v + "\"")
          .bodyValue(
              r.equals(w.r1())
                  ? Map.of("action", "agree")
                  : Map.of(
                      "action",
                      "override",
                      "grade",
                      "non_compliant",
                      "note",
                      "Nothing in the proposal covers this"))
          .exchange()
          .expectStatus()
          .isOk();
    }
    call("POST", "/tender-vendors/" + w.tv() + "/evaluation/submit", w.m1())
        .exchange()
        .expectStatus()
        .isOk();
    UUID evaluation =
        (UUID)
            TestUsers.scalar(
                "SELECT id FROM member_evaluations WHERE tender_vendor_id = ? AND user_id = ?",
                w.tv(),
                w.m1().id());
    String reopen = "/member-evaluations/" + evaluation + "/reopen";
    call("POST", reopen, w.m2())
        .bodyValue(Map.of("reason", "I want to see their grades"))
        .exchange()
        .expectStatus()
        .isForbidden();
    call("POST", reopen, w.chair())
        .bodyValue(Map.of("reason", "short"))
        .exchange()
        .expectStatus()
        .isEqualTo(422);
    call("POST", reopen, w.chair())
        .bodyValue(Map.of("reason", "The pricing section was misread"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("reopened");
    int v = version(w, w.m1(), w.r1());
    call("PATCH", rows(w) + "/" + w.r1(), w.m1())
        .header("If-Match", "\"" + v + "\"")
        .bodyValue(
            Map.of("action", "flag_clarification", "note", "Ask the vendor about the region"))
        .exchange()
        .expectStatus()
        .isOk();
    call("GET", "/tender-vendors/" + w.tv() + "/evaluation/clarifications", w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.length()")
        .isEqualTo(1);
  }

  @Test
  void historyShowsTheMembersOwnDecisionsAndCrossVendorShowsTheirOwnRows() {
    World w = world(true);
    int v = version(w, w.m1(), w.r1());
    call("PATCH", rows(w) + "/" + w.r1(), w.m1())
        .header("If-Match", "\"" + v + "\"")
        .bodyValue(
            Map.of(
                "action",
                "override",
                "grade",
                "non_compliant",
                "note",
                "Encryption is in transit only"))
        .exchange()
        .expectStatus()
        .isOk();
    call("GET", rows(w) + "/" + w.r1() + "/history", w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.events.length()")
        .isEqualTo(1)
        .jsonPath("$.events[0].action")
        .isEqualTo("grade_overridden")
        .jsonPath("$.ai_original.grade")
        .isEqualTo("fully_compliant");
    // another member sees no history of someone else's row
    call("GET", rows(w) + "/" + w.r1() + "/history", w.m2())
        .exchange()
        .expectBody()
        .jsonPath("$.events.length()")
        .isEqualTo(0);
    call("GET", "/requirements/" + w.r1() + "/cross-vendor", w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.vendors.length()")
        .isEqualTo(1)
        .jsonPath("$.vendors[0].my_decision.review_state")
        .isEqualTo("overridden")
        .jsonPath("$.next_requirement_id")
        .isEqualTo(w.r2().toString());
    call("GET", "/tenders/" + w.tender() + "/evaluations", w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].reviewed")
        .isEqualTo(1)
        .jsonPath("$.items[0].total")
        .isEqualTo(2)
        .jsonPath("$.items[0].is_next")
        .isEqualTo(true);
  }

  @Test
  void aMemberWhoHasNotDeclaredIsRefusedAndOutsidersFindNothing() {
    World w = world(true, false);
    call("GET", rows(w), w.m2())
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("declaration_required");
    call("GET", rows(w), TestUsers.create("member")).exchange().expectStatus().isNotFound();
  }
}
