package app.tendereval.backend.db;

import static app.tendereval.backend.db.Sql.exec;
import static app.tendereval.backend.db.Sql.failure;
import static app.tendereval.backend.db.Sql.one;
import static app.tendereval.backend.db.Sql.rows;
import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.SchemaFixture;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P4-1: screening, AI grades, citations and the member's own evaluation. A positive and a negative
 * case per policy and trigger, and every allowed and refused status change (DM Independence and
 * access, Allowed status changes). The independence rules of the roadmap exit criteria are the
 * tests named "rows ...".
 */
@Tag("integration")
class ScreeningEvaluationSchemaIT {

  static Connection owner;
  static Connection app;
  static Connection ingest;

  static UUID admin, observer, chair, m1, m2, outsider;
  static UUID tender;

  /** One vendor with one proposal version, one cited passage, one requirement and one run. */
  record Scn(UUID tv, UUID pv, UUID dv, UUID passage, UUID requirement, UUID run, UUID baseline) {}

  @BeforeAll
  static void world() {
    SchemaFixture.ensureStarted();
    owner = SchemaFixture.owner();
    app = SchemaFixture.app();
    ingest = SchemaFixture.ingest();
    admin = user("admin");
    observer = user("observer");
    chair = user("member");
    m1 = user("member");
    m2 = user("member");
    outsider = user("member");
    tender =
        uuid(
            one(
                owner,
                "INSERT INTO tenders (name, created_by) VALUES (?, ?) RETURNING id",
                "T " + UUID.randomUUID(),
                admin));
    exec(
        owner,
        "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
        tender,
        chair);
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", tender, m1);
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", tender, m2);
  }

  @AfterAll
  static void close() throws SQLException {
    owner.close();
    app.close();
    ingest.close();
  }

  // ---- helpers ---------------------------------------------------------------------------------

  static UUID uuid(Object o) {
    return o instanceof UUID u ? u : UUID.fromString(String.valueOf(o));
  }

  static UUID user(String role) {
    String n = UUID.randomUUID().toString();
    return uuid(
        one(
            owner,
            "INSERT INTO users (email, display_name, role, status, sso_subject) VALUES (?, ?, ?, 'active', ?) RETURNING id",
            n + "@example.test",
            "U " + n.substring(0, 6),
            role,
            n));
  }

  static Object as(UUID user, String role, Function<Connection, Object> fn) {
    try {
      app.setAutoCommit(false);
      try {
        rows(
            app,
            "SELECT set_config('app.user_id', ?, true), set_config('app.role', ?, true)",
            user == null ? "" : user.toString(),
            role == null ? "" : role);
        return fn.apply(app);
      } finally {
        app.rollback();
        app.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static Scn scenario() {
    return scenario(tender);
  }

  static Scn scenario(UUID tender) {
    UUID vendor =
        uuid(
            one(
                owner,
                "INSERT INTO vendors (name, normalized_name) VALUES (?, ?) RETURNING id",
                "V",
                UUID.randomUUID().toString()));
    UUID tv =
        uuid(
            one(
                owner,
                "INSERT INTO tender_vendors (tender_id, vendor_id, added_by) VALUES (?, ?, ?) RETURNING id",
                tender,
                vendor,
                admin));
    UUID pv =
        uuid(
            one(
                owner,
                "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by) VALUES (?, 1, ?) RETURNING id",
                tv,
                admin));
    UUID doc =
        uuid(
            one(
                owner,
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'proposal', 'P') RETURNING id",
                tender));
    UUID dv = UUID.randomUUID();
    exec(
        owner,
        "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format, size_bytes, sha256,"
            + " storage_key, status, uploaded_by) VALUES (?, ?, 1, 'p.pdf', 'pdf', 10, repeat('a', 64), ?, 'uploading', ?)",
        dv,
        doc,
        "originals/%s/%s/%s.pdf".formatted(tender, doc, dv),
        admin);
    exec(
        owner,
        "INSERT INTO proposal_version_files (proposal_version_id, document_version_id, file_role)"
            + " VALUES (?, ?, 'main')",
        pv,
        dv);
    UUID passage = UUID.randomUUID();
    exec(
        owner,
        "INSERT INTO passages (id, document_version_id, page_no, passage_no, text, bbox, char_start, char_end,"
            + " content_hash) VALUES (?, ?, 1, 1, 'We encrypt all data at rest.', '[]'::jsonb, 0, 28, repeat('b', 64))",
        passage,
        dv);
    UUID requirement =
        uuid(
            one(
                owner,
                "INSERT INTO requirements (tender_id, text, importance, origin) VALUES (?, 'Encrypt data.', 'mandatory', 'manual')"
                    + " RETURNING id",
                tender));
    UUID baseline = baseline(tender);
    exec(
        owner,
        "INSERT INTO baseline_requirements (baseline_id, requirement_id, display_no, text, importance, content_hash)"
            + " VALUES (?, ?, 'REQ-001', 'Encrypt data.', 'mandatory', repeat('c', 64))",
        baseline,
        requirement);
    UUID job =
        uuid(
            one(
                owner,
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id, started_by)"
                    + " VALUES (?, 'evaluation', 'proposal_version', ?, ?) RETURNING id",
                tender,
                pv,
                chair));
    UUID run =
        uuid(
            one(
                owner,
                "INSERT INTO ai_runs (job_id, run_type, model_name, prompt_version) VALUES (?, 'evaluation', 'stub', 'e-1')"
                    + " RETURNING id",
                job));
    return new Scn(tv, pv, dv, passage, requirement, run, baseline);
  }

  static final java.util.Map<UUID, UUID> BASELINES = new java.util.HashMap<>();

  static synchronized UUID baseline(UUID t) {
    return BASELINES.computeIfAbsent(
        t,
        k ->
            uuid(
                one(
                    owner,
                    "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by)"
                        + " VALUES (?, 1, 1, ?) RETURNING id",
                    k,
                    chair)));
  }

  static void declare(Scn s, UUID member, boolean conflict) {
    exec(
        owner,
        "INSERT INTO conflict_declarations (tender_vendor_id, user_id, has_conflict, statement) VALUES (?, ?, ?, ?)",
        s.tv(),
        member,
        conflict,
        conflict ? "I advised this vendor last year" : null);
  }

  /**
   * A graded row with one citation, inserted in one transaction (the evidence rule is deferred).
   */
  static UUID grade(Scn s, String grade, String reason, boolean withCitation) {
    try {
      owner.setAutoCommit(false);
      try {
        UUID id =
            uuid(
                one(
                    owner,
                    "INSERT INTO ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id, requirement_id,"
                        + " outcome, grade, non_compliance_reason, rationale_summary, rationale, missing_elements,"
                        + " confidence, requirement_hash) VALUES (?, ?, ?, ?, ?, 'graded', ?, ?, 's', 'r',"
                        + " CASE WHEN ? = 'partially_compliant' THEN '[\"x\"]'::jsonb ELSE '[]'::jsonb END, 0.9,"
                        + " repeat('c', 64)) RETURNING id",
                    UUID.randomUUID(),
                    s.run(),
                    s.pv(),
                    s.baseline(),
                    s.requirement(),
                    grade,
                    reason,
                    grade));
        if (withCitation) {
          exec(
              owner,
              "INSERT INTO grade_citations (ai_grade_id, citation_no, document_version_id, passage_id, page_no, excerpt)"
                  + " VALUES (?, 1, ?, ?, 1, 'We encrypt all data at rest.')",
              id,
              s.dv(),
              s.passage());
        }
        owner.commit();
        return id;
      } catch (RuntimeException e) {
        owner.rollback();
        throw e;
      } finally {
        owner.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static UUID evaluation(Scn s, UUID member) {
    return uuid(
        one(
            owner,
            "SELECT id FROM member_evaluations WHERE tender_vendor_id = ? AND user_id = ?",
            s.tv(),
            member));
  }

  /** Grades the scenario's requirement and creates every member's evaluation and rows. */
  static UUID gradedAndSynced(Scn s) {
    UUID g = grade(s, "fully_compliant", null, true);
    exec(owner, "UPDATE proposal_versions SET status = 'in_screening' WHERE id = ?", s.pv());
    exec(owner, "UPDATE proposal_versions SET status = 'accepted' WHERE id = ?", s.pv());
    rows(ingest, "SELECT * FROM ingest_sync_member_evaluations(?)", s.pv());
    return g;
  }

  static void submit(UUID evaluation) {
    exec(
        owner,
        "UPDATE member_evaluations SET status = 'in_progress' WHERE id = ? AND status = 'not_started'",
        evaluation);
    exec(owner, "UPDATE member_evaluations SET status = 'submitted' WHERE id = ?", evaluation);
  }

  static long rowsVisible(UUID reader, String role, UUID owned) {
    return (Long)
        as(
            reader,
            role,
            c ->
                one(
                    c,
                    "SELECT count(*) FROM member_evaluation_rows WHERE member_evaluation_id = ?",
                    owned));
  }

  // ---- screening
  // ----------------------------------------------------------------------------------

  @Test
  void everyTenderHasTheFiveDefaultChecklistItems() {
    assertThat(
            one(
                owner,
                "SELECT count(*) FROM screening_checklist_items WHERE tender_id = ? AND is_default",
                tender))
        .isEqualTo(5L);
  }

  @Test
  void onlyTheChairEditsTheChecklistAndItLocksOnceScreeningStarts() {
    UUID t =
        uuid(
            one(
                owner,
                "INSERT INTO tenders (name, created_by) VALUES (?, ?) RETURNING id",
                "T " + UUID.randomUUID(),
                admin));
    exec(
        owner,
        "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
        t,
        chair);
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", t, m1);
    String add =
        "INSERT INTO screening_checklist_items (tender_id, label, sort_order) VALUES (?, 'Own item', 9)";
    assertThat(as(chair, "member", c -> failure(c, add, t))).isNull();
    assertThat(as(m1, "member", c -> failure(c, add, t))).isNotNull();
    assertThat(as(admin, "admin", c -> failure(c, add, t))).isNotNull();
    assertThat(
            as(
                chair,
                "member",
                c -> failure(c, "DELETE FROM screening_checklist_items WHERE tender_id = ?", t)))
        .isNotNull();
    assertThat(
            as(
                chair,
                "member",
                c ->
                    failure(
                        c,
                        "UPDATE screening_checklist_items SET is_active = false WHERE tender_id = ? AND sort_order = 1",
                        t)))
        .isNull();

    // a check by a member starts screening: the checklist is locked
    UUID vendor =
        uuid(
            one(
                owner,
                "INSERT INTO vendors (name, normalized_name) VALUES ('V', ?) RETURNING id",
                UUID.randomUUID().toString()));
    UUID tv =
        uuid(
            one(
                owner,
                "INSERT INTO tender_vendors (tender_id, vendor_id, added_by) VALUES (?, ?, ?) RETURNING id",
                t,
                vendor,
                admin));
    UUID pv =
        uuid(
            one(
                owner,
                "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by)"
                    + " VALUES (?, 1, ?) RETURNING id",
                tv,
                admin));
    UUID item =
        uuid(one(owner, "SELECT id FROM screening_checklist_items WHERE tender_id = ? LIMIT 1", t));
    exec(
        owner,
        "INSERT INTO screening_checks (proposal_version_id, checklist_item_id, user_id, is_checked)"
            + " VALUES (?, ?, ?, true)",
        pv,
        item,
        m1);
    assertThat(as(chair, "member", c -> failure(c, add, t))).isNotNull();
  }

  @Test
  void aRecommendationOrCheckMovesTheProposalIntoScreeningAndRejectNeedsAReason() {
    Scn s = scenario();
    declare(s, m1, false);
    String rec =
        "INSERT INTO screening_recommendations (proposal_version_id, user_id, recommendation, reason)"
            + " VALUES (?, ?, ?, ?)";
    assertThat(as(m1, "member", c -> failure(c, rec, s.pv(), m1, "reject", null))).isNotNull();
    assertThat(as(m1, "member", c -> failure(c, rec, s.pv(), m2, "accept", null)))
        .isNotNull(); // someone else's
    assertThat(as(m1, "member", c -> failure(c, rec, s.pv(), m1, "accept", null))).isNull();
    exec(
        owner,
        "INSERT INTO screening_recommendations (proposal_version_id, user_id, recommendation, reason)"
            + " VALUES (?, ?, 'accept', null)",
        s.pv(),
        m2);
    assertThat(one(owner, "SELECT status FROM proposal_versions WHERE id = ?", s.pv()))
        .isEqualTo("in_screening");
  }

  @Test
  void recommendationsAreReadByTheCommitteeNotByTheAdministratorOrOutsiders() {
    Scn s = scenario();
    exec(
        owner,
        "INSERT INTO screening_recommendations (proposal_version_id, user_id, recommendation)"
            + " VALUES (?, ?, 'accept')",
        s.pv(),
        m1);
    String sql = "SELECT count(*) FROM screening_recommendations WHERE proposal_version_id = ?";
    assertThat(as(m2, "member", c -> one(c, sql, s.pv()))).isEqualTo(1L);
    assertThat(as(chair, "member", c -> one(c, sql, s.pv()))).isEqualTo(1L);
    assertThat(as(admin, "admin", c -> one(c, sql, s.pv()))).isEqualTo(0L);
    assertThat(as(outsider, "member", c -> one(c, sql, s.pv()))).isEqualTo(0L);
    assertThat(as(observer, "observer", c -> one(c, sql, s.pv()))).isEqualTo(0L);
  }

  @Test
  void onlyTheChairDecidesAndTheDecisionMovesTheProposalAndIsAppendOnly() {
    Scn s = scenario();
    exec(owner, "UPDATE proposal_versions SET status = 'in_screening' WHERE id = ?", s.pv());
    String decide =
        "INSERT INTO screening_decisions (proposal_version_id, decision, reason, decided_by)"
            + " VALUES (?, ?, ?, ?)";
    assertThat(as(m1, "member", c -> failure(c, decide, s.pv(), "accept", null, m1))).isNotNull();
    assertThat(as(chair, "member", c -> failure(c, decide, s.pv(), "reject", "too short", chair)))
        .isNotNull();
    assertThat(as(chair, "member", c -> failure(c, decide, s.pv(), "accept", null, m1)))
        .isNotNull();
    assertThat(one(owner, "SELECT status FROM proposal_versions WHERE id = ?", s.pv()))
        .isEqualTo("in_screening");

    exec(owner, decide, s.pv(), "reject", "The mandatory documents are missing", chair);
    assertThat(one(owner, "SELECT status FROM proposal_versions WHERE id = ?", s.pv()))
        .isEqualTo("rejected");
    assertThat(
            failure(
                owner,
                "UPDATE screening_decisions SET reason = 'x' WHERE proposal_version_id = ?",
                s.pv()))
        .isNotNull();
    assertThat(
            failure(owner, "DELETE FROM screening_decisions WHERE proposal_version_id = ?", s.pv()))
        .isNotNull();
    // a second ordinary decision on a rejected proposal is refused; a reinstatement is the way back
    assertThat(failure(owner, decide, s.pv(), "accept", null, chair)).isNotNull();
    assertThat(
            failure(
                owner,
                "INSERT INTO screening_decisions (proposal_version_id, decision, reason, decided_by, is_reinstatement)"
                    + " VALUES (?, 'accept', 'no', ?, true)",
                s.pv(),
                chair))
        .isNotNull();
    exec(
        owner,
        "INSERT INTO screening_decisions (proposal_version_id, decision, reason, decided_by, is_reinstatement)"
            + " VALUES (?, 'accept', 'The missing documents were supplied', ?, true)",
        s.pv(),
        chair);
    assertThat(one(owner, "SELECT status FROM proposal_versions WHERE id = ?", s.pv()))
        .isEqualTo("accepted");
  }

  @Test
  void theEvaluationJobMovesTheProposalVersion() {
    Scn s = scenario();
    exec(owner, "UPDATE proposal_versions SET status = 'in_screening' WHERE id = ?", s.pv());
    exec(owner, "UPDATE proposal_versions SET status = 'accepted' WHERE id = ?", s.pv());
    UUID job =
        uuid(
            one(
                owner,
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id, started_by)"
                    + " VALUES (?, 'evaluation', 'proposal_version', ?, ?) RETURNING id",
                tender,
                s.pv(),
                chair));
    assertThat(one(owner, "SELECT status FROM proposal_versions WHERE id = ?", s.pv()))
        .isEqualTo("evaluating");
    grade(s, "non_compliant", "not_addressed", false);
    exec(owner, "UPDATE jobs SET status = 'running', started_at = now() WHERE id = ?", job);
    exec(owner, "UPDATE jobs SET status = 'completed', finished_at = now() WHERE id = ?", job);
    assertThat(one(owner, "SELECT status FROM proposal_versions WHERE id = ?", s.pv()))
        .isEqualTo("evaluated");
  }

  // ---- AI grades and citations
  // -------------------------------------------------------------------

  @Test
  void gradeShapeIsEnforcedAndAGradeWithoutEvidenceIsNeverStored() {
    Scn s = scenario();
    String base =
        "INSERT INTO ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id, requirement_id,"
            + " outcome, failure_code, grade, non_compliance_reason, missing_elements, requirement_hash)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, repeat('c', 64))";
    // failed needs a code and no grade
    assertThat(
            failure(
                owner,
                base,
                UUID.randomUUID(),
                s.run(),
                s.pv(),
                s.baseline(),
                s.requirement(),
                "failed",
                null,
                null,
                null,
                "[]"))
        .isNotNull();
    assertThat(
            failure(
                owner,
                base,
                UUID.randomUUID(),
                s.run(),
                s.pv(),
                s.baseline(),
                s.requirement(),
                "failed",
                "ungrounded",
                "fully_compliant",
                null,
                "[]"))
        .isNotNull();
    // non_compliant needs a reason; partially needs missing elements
    assertThat(
            failure(
                owner,
                base,
                UUID.randomUUID(),
                s.run(),
                s.pv(),
                s.baseline(),
                s.requirement(),
                "graded",
                null,
                "non_compliant",
                null,
                "[]"))
        .isNotNull();
    assertThat(
            failure(
                owner,
                base,
                UUID.randomUUID(),
                s.run(),
                s.pv(),
                s.baseline(),
                s.requirement(),
                "graded",
                null,
                "partially_compliant",
                null,
                "[]"))
        .isNotNull();
    // a failed row is a valid result
    assertThat(
            failure(
                owner,
                base,
                UUID.randomUUID(),
                s.run(),
                s.pv(),
                s.baseline(),
                s.requirement(),
                "failed",
                "ungrounded",
                null,
                null,
                "[]"))
        .isNull();
    // fully compliant without a citation is refused at commit; with one it is stored
    Scn t = scenario();
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class, () -> grade(t, "fully_compliant", null, false));
    assertThat(one(owner, "SELECT count(*) FROM ai_grades WHERE proposal_version_id = ?", t.pv()))
        .isEqualTo(0L);
    grade(t, "fully_compliant", null, true);
    assertThat(
            one(
                owner,
                "SELECT count(*) FROM grade_citations gc JOIN ai_grades g ON g.id = gc.ai_grade_id"
                    + " WHERE g.proposal_version_id = ?",
                t.pv()))
        .isEqualTo(1L);
    // not addressed needs no evidence
    Scn u = scenario();
    grade(u, "non_compliant", "not_addressed", false);
    assertThat(one(owner, "SELECT count(*) FROM ai_grades WHERE proposal_version_id = ?", u.pv()))
        .isEqualTo(1L);
  }

  @Test
  void aGradeIsImmutableExceptThatItStopsBeingCurrent() {
    Scn s = scenario();
    UUID g = grade(s, "fully_compliant", null, true);
    assertThat(failure(owner, "UPDATE ai_grades SET rationale = 'changed' WHERE id = ?", g))
        .isNotNull();
    assertThat(failure(owner, "DELETE FROM ai_grades WHERE id = ?", g)).isNotNull();
    assertThat(failure(owner, "UPDATE ai_grades SET is_current = false WHERE id = ?", g)).isNull();
    assertThat(failure(owner, "UPDATE ai_grades SET is_current = true WHERE id = ?", g))
        .isNotNull();
    assertThat(failure(owner, "UPDATE grade_citations SET excerpt = 'x' WHERE ai_grade_id = ?", g))
        .isNotNull();
  }

  @Test
  void aCitationMustPointAtAPassageOfTheGradedProposalVersion() {
    Scn s = scenario();
    Scn other = scenario();
    UUID g;
    try {
      owner.setAutoCommit(false);
      g =
          uuid(
              one(
                  owner,
                  "INSERT INTO ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id, requirement_id,"
                      + " outcome, grade, requirement_hash) VALUES (?, ?, ?, ?, ?, 'graded', 'fully_compliant', repeat('c', 64))"
                      + " RETURNING id",
                  UUID.randomUUID(),
                  s.run(),
                  s.pv(),
                  s.baseline(),
                  s.requirement()));
      String cite =
          "INSERT INTO grade_citations (ai_grade_id, citation_no, document_version_id, passage_id, page_no,"
              + " excerpt) VALUES (?, 1, ?, ?, 1, 'x')";
      // the other proposal's document is not a file of this proposal version
      assertThat(failure(owner, cite, g, other.dv(), other.passage())).isNotNull();
      owner.rollback();
      owner.setAutoCommit(false);
      g =
          uuid(
              one(
                  owner,
                  "INSERT INTO ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id, requirement_id,"
                      + " outcome, grade, requirement_hash) VALUES (?, ?, ?, ?, ?, 'graded', 'fully_compliant', repeat('c', 64))"
                      + " RETURNING id",
                  UUID.randomUUID(),
                  s.run(),
                  s.pv(),
                  s.baseline(),
                  s.requirement()));
      // a passage of another document version, named as if it were this one
      assertThat(failure(owner, cite, g, s.dv(), other.passage())).isNotNull();
      owner.rollback();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    } finally {
      try {
        owner.setAutoCommit(true);
      } catch (SQLException ignored) {
        // the connection is closed with the class
      }
    }
  }

  // ---- reading proposals and grades
  // ---------------------------------------------------------------------

  @Test
  void membersReadGradesOnlyWithAClearanceAndNeverWhenExcluded() {
    Scn s = scenario();
    grade(s, "fully_compliant", null, true);
    String sql = "SELECT count(*) FROM ai_grades WHERE proposal_version_id = ?";
    String cite = "SELECT count(*) FROM grade_citations";
    // no declaration yet: nothing
    assertThat(as(m1, "member", c -> one(c, sql, s.pv()))).isEqualTo(0L);
    declare(s, m1, false);
    declare(s, m2, true);
    assertThat(as(m1, "member", c -> one(c, sql, s.pv()))).isEqualTo(1L);
    assertThat(as(m1, "member", c -> (Long) one(c, cite) >= 1L)).isEqualTo(true);
    assertThat(as(m2, "member", c -> one(c, sql, s.pv()))).isEqualTo(0L); // declared a conflict
    assertThat(as(outsider, "member", c -> one(c, sql, s.pv()))).isEqualTo(0L);
    assertThat(as(observer, "observer", c -> one(c, sql, s.pv()))).isEqualTo(0L);
    assertThat(as(admin, "admin", c -> one(c, sql, s.pv())))
        .isEqualTo(0L); // not before every evaluation is in
    // an exclusion hides it again
    UUID g = uuid(one(owner, "SELECT id FROM ai_grades WHERE proposal_version_id = ?", s.pv()));
    assertThat(g).isNotNull();
    exec(owner, "UPDATE proposal_versions SET status = 'in_screening' WHERE id = ?", s.pv());
    exec(owner, "UPDATE proposal_versions SET status = 'accepted' WHERE id = ?", s.pv());
    rows(ingest, "SELECT * FROM ingest_sync_member_evaluations(?)", s.pv());
    exec(
        owner,
        "UPDATE member_evaluations SET excluded_at = now(), excluded_by = ?, exclusion_reason ="
            + " 'Excluded by the Chair after a late disclosure' WHERE id = ?",
        chair,
        evaluation(s, m1));
    assertThat(as(m1, "member", c -> one(c, sql, s.pv()))).isEqualTo(0L);
  }

  @Test
  void theProposalFileFollowsTheSameClearance() {
    Scn s = scenario();
    String sql = "SELECT count(*) FROM document_versions WHERE id = ?";
    assertThat(as(m1, "member", c -> one(c, sql, s.dv()))).isEqualTo(0L);
    declare(s, m1, false);
    assertThat(as(m1, "member", c -> one(c, sql, s.dv()))).isEqualTo(1L);
    assertThat(as(admin, "admin", c -> one(c, sql, s.dv()))).isEqualTo(1L);
    assertThat(as(outsider, "member", c -> one(c, sql, s.dv()))).isEqualTo(0L);
  }

  // ---- the AI-ingest role
  // --------------------------------------------------------------------------------

  @Test
  void theAiIngestRoleCannotReadOrWriteAMembersEvaluation() {
    Scn s = scenario();
    gradedAndSynced(s);
    assertThat(failure(ingest, "SELECT count(*) FROM member_evaluation_rows")).isNotNull();
    assertThat(failure(ingest, "SELECT count(*) FROM member_evaluations")).isNotNull();
    assertThat(
            failure(
                ingest,
                "INSERT INTO member_evaluations (tender_vendor_id, user_id) VALUES (?, ?)",
                s.tv(),
                outsider))
        .isNotNull();
    assertThat(failure(ingest, "UPDATE member_evaluation_rows SET note = 'x'")).isNotNull();
    assertThat(failure(ingest, "SELECT count(*) FROM ai_grade_feedback")).isNotNull();
    assertThat(failure(ingest, "SELECT count(*) FROM screening_recommendations")).isNotNull();
    // what it may do: read its own output and call the sync function (counts only)
    assertThat(failure(ingest, "SELECT count(*) FROM ai_grades")).isNull();
  }

  @Test
  void syncCreatesEvaluationsForEveryActiveMemberExcludesConflictsAndIsRepeatable() {
    Scn s = scenario();
    declare(s, m2, true);
    gradedAndSynced(s);
    assertThat(
            one(
                owner,
                "SELECT count(*) FROM member_evaluations WHERE tender_vendor_id = ?",
                s.tv()))
        .isEqualTo(3L); // chair, m1, m2
    assertThat(
            one(
                owner,
                "SELECT excluded_at IS NOT NULL FROM member_evaluations WHERE id = ?",
                evaluation(s, m2)))
        .isEqualTo(true);
    assertThat(
            one(
                owner,
                "SELECT count(*) FROM member_evaluation_rows r JOIN member_evaluations e"
                    + " ON e.id = r.member_evaluation_id WHERE e.tender_vendor_id = ?",
                s.tv()))
        .isEqualTo(2L); // not m2's
    java.util.List<java.util.List<Object>> again =
        rows(ingest, "SELECT * FROM ingest_sync_member_evaluations(?)", s.pv());
    assertThat(again.get(0)).containsExactly(0, 0, 0);
  }

  // ---- independence: reading a member's rows
  // ------------------------------------------------------------------

  @Test
  void rowsAreReadByTheOwnerAlwaysAndByOthersOnlyAfterTheReleaseRules() {
    Scn s = scenario();
    declare(s, m1, false);
    declare(s, m2, false);
    declare(s, chair, false);
    gradedAndSynced(s);
    UUID e1 = evaluation(s, m1);
    UUID e2 = evaluation(s, m2);
    UUID ec = evaluation(s, chair);

    // before anyone submits: the owner only
    assertThat(rowsVisible(m1, "member", e1)).isEqualTo(1L);
    assertThat(rowsVisible(m2, "member", e1)).isEqualTo(0L);
    assertThat(rowsVisible(chair, "member", e1)).isEqualTo(0L);
    assertThat(rowsVisible(admin, "admin", e1)).isEqualTo(0L);
    assertThat(rowsVisible(observer, "observer", e1)).isEqualTo(0L);
    assertThat(rowsVisible(outsider, "member", e1)).isEqualTo(0L);

    // m1 submits: m2 still cannot read it, because m2 has not submitted their own
    submit(e1);
    assertThat(rowsVisible(m2, "member", e1)).isEqualTo(0L);
    assertThat(rowsVisible(chair, "member", e1)).isEqualTo(0L);

    // m2 submits: m2 and nobody else reads m1's rows now, and m1 reads m2's
    submit(e2);
    assertThat(rowsVisible(m2, "member", e1)).isEqualTo(1L);
    assertThat(rowsVisible(m1, "member", e2)).isEqualTo(1L);
    assertThat(rowsVisible(chair, "member", e1)).isEqualTo(0L); // the Chair has not submitted
    assertThat(rowsVisible(admin, "admin", e1)).isEqualTo(0L); // the Chair's evaluation is open

    // the Chair submits: the Chair reads the others (decision Q3) and the administrator reads all
    submit(ec);
    assertThat(rowsVisible(chair, "member", e1)).isEqualTo(1L);
    assertThat(rowsVisible(admin, "admin", e1)).isEqualTo(1L);
    assertThat(rowsVisible(observer, "observer", e1)).isEqualTo(0L);
    assertThat(rowsVisible(outsider, "member", e1)).isEqualTo(0L);

    // a reopen hides the owner's rows again from others, but never un-unlocks the reader's own
    exec(
        owner,
        "UPDATE member_evaluations SET status = 'reopened', reopened_by = ?, reopened_at = now(),"
            + " reopen_reason = 'The Chair asked for a second look' WHERE id = ?",
        chair,
        e1);
    assertThat(rowsVisible(m2, "member", e1)).isEqualTo(0L);
    assertThat(rowsVisible(m1, "member", e2)).isEqualTo(1L);
  }

  @Test
  void theProgressMatrixShowsStatusToTheCommitteeAndTheAdministratorButNeverToOthers() {
    Scn s = scenario();
    gradedAndSynced(s);
    String sql = "SELECT count(*) FROM member_evaluations WHERE tender_vendor_id = ?";
    assertThat(as(m1, "member", c -> one(c, sql, s.tv()))).isEqualTo(3L);
    assertThat(as(chair, "member", c -> one(c, sql, s.tv()))).isEqualTo(3L);
    assertThat(as(admin, "admin", c -> one(c, sql, s.tv()))).isEqualTo(3L);
    assertThat(as(outsider, "member", c -> one(c, sql, s.tv()))).isEqualTo(0L);
    assertThat(as(observer, "observer", c -> one(c, sql, s.tv()))).isEqualTo(0L);
  }

  // ---- writing a member's rows
  // ----------------------------------------------------------------------------------

  @Test
  void anOverrideNeedsANoteOfTenCharactersAndAgreeingTakesTheAiGrade() {
    Scn s = scenario();
    declare(s, m1, false);
    gradedAndSynced(s);
    UUID e1 = evaluation(s, m1);
    String override =
        "UPDATE member_evaluation_rows SET review_state = 'overridden', member_grade = 'non_compliant',"
            + " note = ? WHERE member_evaluation_id = ?";
    assertThat(as(m1, "member", c -> failure(c, override, "too short", e1))).isNotNull();
    assertThat(as(m1, "member", c -> failure(c, override, "         x         ", e1))).isNotNull();
    assertThat(as(m1, "member", c -> failure(c, override, "The encryption is only in transit", e1)))
        .isNull();
    String agree =
        "UPDATE member_evaluation_rows SET review_state = 'agreed', member_grade = ?"
            + " WHERE member_evaluation_id = ?";
    assertThat(as(m1, "member", c -> failure(c, agree, "non_compliant", e1)))
        .isNotNull(); // not the AI grade
    assertThat(as(m1, "member", c -> failure(c, agree, "fully_compliant", e1))).isNull();
    // the first decision started the evaluation
    assertThat(
            as(
                m1,
                "member",
                c -> {
                  exec(
                      c,
                      "UPDATE member_evaluation_rows SET review_state = 'agreed', member_grade = 'fully_compliant'"
                          + " WHERE member_evaluation_id = ?",
                      e1);
                  return one(c, "SELECT status FROM member_evaluations WHERE id = ?", e1);
                }))
        .isEqualTo("in_progress");
  }

  @Test
  void onlyTheOwnerWritesTheirRowsAndNotAfterSubmitting() {
    Scn s = scenario();
    declare(s, m1, false);
    declare(s, m2, false);
    gradedAndSynced(s);
    UUID e1 = evaluation(s, m1);
    String flag =
        "UPDATE member_evaluation_rows SET needs_clarification = true WHERE member_evaluation_id = ?";
    assertThat(as(m1, "member", c -> failure(c, flag, e1))).isNull();
    assertThat(as(m2, "member", c -> (Object) updated(c, flag, e1))).isEqualTo(0);
    assertThat(as(chair, "member", c -> (Object) updated(c, flag, e1))).isEqualTo(0);
    assertThat(as(admin, "admin", c -> (Object) updated(c, flag, e1))).isEqualTo(0);
    submit(e1);
    assertThat(as(m1, "member", c -> (Object) updated(c, flag, e1))).isEqualTo(0);
    // nobody inserts rows directly, not even the owner
    assertThat(
            as(
                m1,
                "member",
                c ->
                    failure(
                        c,
                        "INSERT INTO member_evaluation_rows (member_evaluation_id, requirement_id) VALUES (?, ?)",
                        e1,
                        s.requirement())))
        .isNotNull();
  }

  static int updated(Connection c, String sql, Object... args) {
    try (var ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void onlyTheChairReopensOrExcludesAndFirstSubmittedAtIsSetOnce() {
    Scn s = scenario();
    declare(s, m1, false);
    gradedAndSynced(s);
    UUID e1 = evaluation(s, m1);
    submit(e1);
    Object first = one(owner, "SELECT first_submitted_at FROM member_evaluations WHERE id = ?", e1);
    assertThat(first).isNotNull();
    String reopen =
        "UPDATE member_evaluations SET status = 'reopened', reopened_by = ?, reopened_at = now(),"
            + " reopen_reason = ? WHERE id = ?";
    assertThat(as(m1, "member", c -> failure(c, reopen, m1, "I want to change a grade", e1)))
        .isNotNull();
    assertThat(
            as(m2, "member", c -> (Object) updated(c, reopen, m2, "Another member reopening", e1)))
        .isEqualTo(0);
    assertThat(as(chair, "member", c -> failure(c, reopen, chair, "short", e1))).isNotNull();
    assertThat(
            as(
                chair,
                "member",
                c -> failure(c, reopen, chair, "The pricing section was misread", e1)))
        .isNull();
    exec(
        owner,
        "UPDATE member_evaluations SET status = 'reopened', reopened_by = ?, reopened_at = now(),"
            + " reopen_reason = 'The pricing section was misread' WHERE id = ?",
        chair,
        e1);
    exec(owner, "UPDATE member_evaluations SET status = 'submitted' WHERE id = ?", e1);
    assertThat(one(owner, "SELECT first_submitted_at FROM member_evaluations WHERE id = ?", e1))
        .isEqualTo(first);
    assertThat(
            failure(
                owner, "UPDATE member_evaluations SET first_submitted_at = null WHERE id = ?", e1))
        .isNotNull();
    String exclude =
        "UPDATE member_evaluations SET excluded_at = now(), excluded_by = ?, exclusion_reason = ?"
            + " WHERE id = ?";
    assertThat(as(m1, "member", c -> failure(c, exclude, m1, "I would rather not take part", e1)))
        .isNotNull();
    assertThat(as(chair, "member", c -> failure(c, exclude, chair, "short", e1))).isNotNull();
    assertThat(
            as(
                chair,
                "member",
                c -> failure(c, exclude, chair, "Disclosed a financial interest", e1)))
        .isNull();
  }

  @Test
  void everyAllowedEvaluationStatusChangeWorksAndOthersAreRefused() {
    Scn s = scenario();
    gradedAndSynced(s);
    UUID e = evaluation(s, m1);
    String set = "UPDATE member_evaluations SET status = ? WHERE id = ?";
    assertThat(failure(owner, set, "submitted", e)).isNotNull(); // not_started -> submitted
    assertThat(failure(owner, set, "reopened", e)).isNotNull(); // not_started -> reopened
    assertThat(failure(owner, set, "outdated", e)).isNotNull(); // not_started -> outdated
    assertThat(failure(owner, set, "in_progress", e)).isNull();
    assertThat(failure(owner, set, "not_started", e)).isNotNull(); // never back
    assertThat(failure(owner, set, "submitted", e)).isNull();
    assertThat(failure(owner, set, "in_progress", e)).isNotNull(); // submitted -> in_progress
    assertThat(
            failure(
                owner,
                "UPDATE member_evaluations SET status = 'reopened', reopened_by = ?, reopened_at = now(),"
                    + " reopen_reason = 'Needs a second look at section 4' WHERE id = ?",
                chair,
                e))
        .isNull();
    assertThat(failure(owner, set, "outdated", e)).isNotNull(); // reopened -> outdated
    assertThat(failure(owner, set, "submitted", e)).isNull(); // reopened -> submitted
    assertThat(failure(owner, set, "outdated", e)).isNull(); // submitted -> outdated
    assertThat(failure(owner, set, "in_progress", e)).isNull(); // outdated -> in_progress
  }

  /** A tender of its own with the same people, so a baseline or proposal change stays isolated. */
  static UUID ownTender() {
    UUID t =
        uuid(
            one(
                owner,
                "INSERT INTO tenders (name, created_by) VALUES (?, ?) RETURNING id",
                "T " + UUID.randomUUID(),
                admin));
    exec(
        owner,
        "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
        t,
        chair);
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", t, m1);
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", t, m2);
    return t;
  }

  static final String MARK =
      "SELECT app_mark_rows_outdated(?, ?, NULL, 'Requirement text changed in version 2')";

  @Test
  void outdatingNeedsARealChangeAndTheEarlierDecisionNeverReachesTheAuditTrail() {
    UUID t = ownTender();
    Scn s = scenario(t);
    declare(s, m1, false);
    gradedAndSynced(s);
    UUID e1 = evaluation(s, m1);
    exec(
        owner,
        "UPDATE member_evaluation_rows SET review_state = 'agreed', member_grade = 'fully_compliant'"
            + " WHERE member_evaluation_id = ?",
        e1);

    // who may call it
    assertThat(as(m1, "member", c -> failure(c, MARK, s.tv(), "baseline_changed"))).isNotNull();
    assertThat(as(admin, "admin", c -> failure(c, MARK, s.tv(), "baseline_changed"))).isNotNull();
    assertThat(as(chair, "member", c -> failure(c, MARK, s.tv(), "proposal_changed"))).isNotNull();
    assertThat(as(outsider, "member", c -> failure(c, MARK, s.tv(), "baseline_changed")))
        .isNotNull();

    // nothing changed: nothing is outdated, even for the Chair
    assertThat(as(chair, "member", c -> one(c, MARK, s.tv(), "baseline_changed"))).isEqualTo(0);
    assertThat(as(admin, "admin", c -> one(c, MARK, s.tv(), "proposal_changed"))).isEqualTo(0);

    // the Chair reopens and confirms version 2 with a changed requirement
    exec(
        owner,
        "UPDATE baselines SET status = 'reopened', reopened_by = ?, reopened_at = now(),"
            + " reopen_reason = 'The requirement was reworded' WHERE id = ?",
        chair,
        s.baseline());
    exec(owner, "UPDATE baselines SET status = 'superseded' WHERE id = ?", s.baseline());
    UUID v2 =
        uuid(
            one(
                owner,
                "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by)"
                    + " VALUES (?, 2, 1, ?) RETURNING id",
                t,
                chair));
    exec(
        owner,
        "INSERT INTO baseline_requirements (baseline_id, requirement_id, display_no, text, importance, content_hash)"
            + " VALUES (?, ?, 'REQ-001', 'Encrypt data in transit and at rest.', 'mandatory', repeat('d', 64))",
        v2,
        s.requirement());

    // the Chair learns a count, never a grade
    Object n =
        as(
            chair,
            "member",
            c -> {
              Object count = one(c, MARK, s.tv(), "baseline_changed");
              assertThat(
                      one(
                          c,
                          "SELECT count(*) FROM member_evaluation_rows WHERE member_evaluation_id = ?",
                          e1))
                  .isEqualTo(0L);
              return count;
            });
    assertThat(n).isEqualTo(3); // one row in each member's evaluation
    exec(
        owner,
        "SELECT set_config('app.user_id', ?, false), set_config('app.role', 'member', false)",
        chair.toString());
    assertThat(one(owner, MARK, s.tv(), "baseline_changed")).isEqualTo(3);
    exec(owner, "SELECT set_config('app.user_id', '', false), set_config('app.role', '', false)");
    assertThat(
            one(
                owner,
                "SELECT review_state || ':' || coalesce(member_grade, '-') || ':' || outdated_reason"
                    + " FROM member_evaluation_rows WHERE member_evaluation_id = ?",
                e1))
        .isEqualTo("not_reviewed:-:baseline_changed");
    assertThat(one(owner, "SELECT status FROM member_evaluations WHERE id = ?", e1))
        .isEqualTo("outdated");

    // the earlier decision is on the member's own row, and not in the audit trail
    assertThat(
            as(
                m1,
                "member",
                c ->
                    one(
                        c,
                        "SELECT previous_member_grade FROM member_evaluation_rows WHERE member_evaluation_id = ?",
                        e1)))
        .isEqualTo("fully_compliant");
    assertThat(
            one(
                owner,
                "SELECT count(*) FROM audit_events WHERE tender_vendor_id = ? AND action = 'rows_marked_outdated'",
                s.tv()))
        .isEqualTo(3L);
    String leak =
        "SELECT count(*) FROM audit_events WHERE tender_vendor_id = ? AND (coalesce(before::text, '') || coalesce(after::text, ''))"
            + " ~ '(compliant|agreed|overridden)'";
    assertThat(as(admin, "admin", c -> one(c, leak, s.tv()))).isEqualTo(0L);
    assertThat(as(observer, "observer", c -> one(c, leak, s.tv()))).isEqualTo(0L);
  }

  @Test
  void aProposalChangeOutdatesRowsOfTheReplacedVersionOnly() {
    UUID t = ownTender();
    Scn s = scenario(t);
    declare(s, m1, false);
    gradedAndSynced(s);
    assertThat(as(admin, "admin", c -> one(c, MARK, s.tv(), "proposal_changed"))).isEqualTo(0);
    exec(
        owner,
        "UPDATE proposal_versions SET status = 'superseded', superseded_at = now() WHERE id = ?",
        s.pv());
    exec(
        owner,
        "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by) VALUES (?, 2, ?)",
        s.tv(),
        admin);
    assertThat(as(chair, "member", c -> failure(c, MARK, s.tv(), "proposal_changed"))).isNotNull();
    assertThat(as(admin, "admin", c -> one(c, MARK, s.tv(), "proposal_changed"))).isEqualTo(3);
  }

  // ---- review findings: negative tests
  // ---------------------------------------------------------------------------

  @Test
  void aMemberCannotUnlockTheReleaseRuleOrChangeAnythingButTheirOwnStatus() {
    Scn s = scenario();
    declare(s, m1, false);
    gradedAndSynced(s);
    UUID e1 = evaluation(s, m1);
    for (String set :
        new String[] {
          "first_submitted_at = now()",
          "submitted_at = now()",
          "reopened_by = user_id, reopened_at = now(), reopen_reason = 'Reopening my own evaluation'",
          "excluded_at = now(), excluded_by = user_id, exclusion_reason = 'Excluding myself from this vendor'"
        }) {
      assertThat(
              as(
                  m1,
                  "member",
                  c -> failure(c, "UPDATE member_evaluations SET " + set + " WHERE id = ?", e1)))
          .as(set)
          .isNotNull();
    }
    // first_submitted_at stays empty, so another member's submitted rows stay closed
    assertThat(one(owner, "SELECT first_submitted_at FROM member_evaluations WHERE id = ?", e1))
        .isNull();
  }

  @Test
  void aChairCannotReopenOrExcludeThemselvesNorCombineChanges() {
    Scn s = scenario();
    declare(s, chair, false);
    declare(s, m1, false);
    gradedAndSynced(s);
    UUID ec = evaluation(s, chair);
    UUID e1 = evaluation(s, m1);
    submit(ec);
    String reopen =
        "UPDATE member_evaluations SET status = 'reopened', reopened_by = ?, reopened_at = now(),"
            + " reopen_reason = 'Looking at my grades again after reading theirs' WHERE id = ?";
    assertThat(as(chair, "member", c -> failure(c, reopen, chair, ec))).isNotNull(); // self reopen
    // exclusion together with a status change of someone else's evaluation
    assertThat(
            as(
                chair,
                "member",
                c ->
                    failure(
                        c,
                        "UPDATE member_evaluations SET excluded_at = now(), excluded_by = ?, exclusion_reason ="
                            + " 'Disclosed a financial interest', status = 'in_progress' WHERE id = ?",
                        chair,
                        e1)))
        .isNotNull();
    assertThat(
            as(
                chair,
                "member",
                c ->
                    failure(
                        c,
                        "UPDATE member_evaluations SET excluded_at = now(), excluded_by = ?, exclusion_reason ="
                            + " 'Excluding myself from this vendor' WHERE id = ?",
                        chair,
                        ec)))
        .isNotNull();
  }

  @Test
  void aMemberWithoutClearanceReadsNothingOfOthersAndWritesNothing() {
    Scn s = scenario();
    declare(s, m1, false);
    gradedAndSynced(s); // m2 never declared
    UUID e1 = evaluation(s, m1);
    UUID e2 = evaluation(s, m2);
    exec(owner, "UPDATE member_evaluations SET status = 'in_progress' WHERE id = ?", e2);
    exec(
        owner,
        "UPDATE member_evaluations SET status = 'submitted' WHERE id = ?",
        e2); // owner-role setup
    submit(e1);
    assertThat(rowsVisible(m2, "member", e1)).isEqualTo(0L); // submitted, but not cleared
    // a blind override by an uncleared member is refused
    UUID e3 = evaluation(s, chair);
    assertThat(
            as(
                chair,
                "member",
                c ->
                    (Object)
                        updated(
                            c,
                            "UPDATE member_evaluation_rows SET needs_clarification = true WHERE member_evaluation_id = ?",
                            e3)))
        .isEqualTo(0);
  }

  @Test
  void aScreeningRowCannotBeMovedToAnotherVendorOrEditedAfterTheDecision() {
    Scn a = scenario();
    Scn b = scenario();
    declare(a, m1, false);
    exec(
        owner,
        "INSERT INTO screening_recommendations (proposal_version_id, user_id, recommendation)"
            + " VALUES (?, ?, 'accept')",
        a.pv(),
        m1);
    // moving it to the vendor the member is not cleared for
    assertThat(
            as(
                m1,
                "member",
                c ->
                    failure(
                        c,
                        "UPDATE screening_recommendations SET proposal_version_id = ? WHERE proposal_version_id = ?",
                        b.pv(),
                        a.pv())))
        .isNotNull();
    // once the Chair has decided, the screening rows are closed
    exec(owner, "UPDATE proposal_versions SET status = 'in_screening' WHERE id = ?", a.pv());
    exec(
        owner,
        "INSERT INTO screening_decisions (proposal_version_id, decision, reason, decided_by)"
            + " VALUES (?, 'accept', null, ?)",
        a.pv(),
        chair);
    assertThat(
            as(
                m1,
                "member",
                c ->
                    failure(
                        c,
                        "UPDATE screening_recommendations SET recommendation = 'reject', reason = 'changed my mind'"
                            + " WHERE proposal_version_id = ?",
                        a.pv())))
        .isNotNull();
    // a check names a checklist item of the same tender
    UUID foreignItem =
        uuid(
            one(
                owner,
                "SELECT i.id FROM screening_checklist_items i WHERE i.tender_id <> ? LIMIT 1",
                tender));
    assertThat(
            failure(
                owner,
                "INSERT INTO screening_checks (proposal_version_id, checklist_item_id, user_id, is_checked)"
                    + " VALUES (?, ?, ?, true)",
                b.pv(),
                foreignItem,
                m1))
        .isNotNull();
  }

  @Test
  void theApplicationRoleCannotWriteWhatOnlyTheIngestConnectionOrTheDatabaseWrites() {
    Scn s = scenario();
    String denied = "42501";
    assertThat(
            as(
                chair,
                "member",
                c ->
                    failure(
                        c,
                        "INSERT INTO ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id, requirement_id,"
                            + " outcome, failure_code, requirement_hash) VALUES (?, ?, ?, ?, ?, 'failed', 'ungrounded',"
                            + " repeat('c', 64))",
                        UUID.randomUUID(),
                        s.run(),
                        s.pv(),
                        s.baseline(),
                        s.requirement())))
        .isEqualTo(denied);
    assertThat(
            as(
                chair,
                "member",
                c ->
                    failure(
                        c,
                        "INSERT INTO screening_prechecks (source_item_id, proposal_version_id, expected_section, found)"
                            + " VALUES (?, ?, 'Scope', true)",
                        UUID.randomUUID(),
                        s.pv())))
        .isEqualTo(denied);
    assertThat(
            as(
                admin,
                "admin",
                c ->
                    failure(
                        c,
                        "INSERT INTO member_evaluations (tender_vendor_id, user_id) VALUES (?, ?)",
                        s.tv(),
                        m1)))
        .isEqualTo(denied);
    assertThat(
            as(
                chair,
                "member",
                c ->
                    (Object)
                        updated(
                            c,
                            "UPDATE proposal_versions SET status = 'in_screening' WHERE id = ?",
                            s.pv())))
        .isEqualTo(0);
    // functions: the sync is the ingest connection's; the outdating is the application's
    assertThat(
            as(
                chair,
                "member",
                c -> failure(c, "SELECT * FROM ingest_sync_member_evaluations(?)", s.pv())))
        .isEqualTo(denied);
    assertThat(
            failure(
                ingest,
                "SELECT app_mark_rows_outdated(?, 'baseline_changed', NULL, 'Not allowed at all')",
                s.tv()))
        .isEqualTo(denied);
  }

  @Test
  void aCitationIsStoredWithItsGradeAndTheGradeMatchesTheBaseline() {
    Scn s = scenario();
    UUID g = grade(s, "fully_compliant", null, true);
    // appended later, in another transaction: refused
    assertThat(
            failure(
                owner,
                "INSERT INTO grade_citations (ai_grade_id, citation_no, document_version_id, passage_id, page_no, excerpt)"
                    + " VALUES (?, 2, ?, ?, 1, 'We encrypt all data at rest.')",
                g,
                s.dv(),
                s.passage()))
        .isNotNull();
    // wrong requirement hash for the baseline
    Scn t = scenario();
    assertThat(
            failure(
                owner,
                "INSERT INTO ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id, requirement_id,"
                    + " outcome, failure_code, requirement_hash) VALUES (?, ?, ?, ?, ?, 'failed', 'ungrounded',"
                    + " repeat('e', 64))",
                UUID.randomUUID(),
                t.run(),
                t.pv(),
                t.baseline(),
                t.requirement()))
        .isNotNull();
    // a run of another proposal version's evaluation
    assertThat(
            failure(
                owner,
                "INSERT INTO ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id, requirement_id,"
                    + " outcome, failure_code, requirement_hash) VALUES (?, ?, ?, ?, ?, 'failed', 'ungrounded',"
                    + " repeat('c', 64))",
                UUID.randomUUID(),
                s.run(),
                t.pv(),
                t.baseline(),
                t.requirement()))
        .isNotNull();
  }

  @Test
  void aCompletedEvaluationJobWithoutGradesDoesNotMarkTheProposalEvaluated() {
    Scn s = scenario();
    exec(owner, "UPDATE proposal_versions SET status = 'in_screening' WHERE id = ?", s.pv());
    exec(owner, "UPDATE proposal_versions SET status = 'accepted' WHERE id = ?", s.pv());
    UUID job =
        uuid(
            one(
                owner,
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id, started_by)"
                    + " VALUES (?, 'evaluation', 'proposal_version', ?, ?) RETURNING id",
                tender,
                s.pv(),
                chair));
    exec(owner, "UPDATE jobs SET status = 'running', started_at = now() WHERE id = ?", job);
    exec(owner, "UPDATE jobs SET status = 'completed', finished_at = now() WHERE id = ?", job);
    assertThat(one(owner, "SELECT status FROM proposal_versions WHERE id = ?", s.pv()))
        .isEqualTo("evaluating");
  }

  // ---- feedback
  // -------------------------------------------------------------------------------------------------------

  @Test
  void feedbackBelongsToTheMemberWhoGaveItAndUsesAFixedTagList() {
    Scn s = scenario();
    declare(s, m1, false);
    UUID g = grade(s, "fully_compliant", null, true);
    String put =
        "INSERT INTO ai_grade_feedback (ai_grade_id, user_id, is_useful, reason_tags) VALUES (?, ?, false,"
            + " ARRAY[?]::text[])";
    assertThat(as(m1, "member", c -> failure(c, put, g, m1, "wrong_page"))).isNull();
    assertThat(as(m1, "member", c -> failure(c, put, g, m1, "made_up_tag"))).isNotNull();
    assertThat(as(m1, "member", c -> failure(c, put, g, m2, "wrong_page"))).isNotNull();
    exec(
        owner,
        "INSERT INTO ai_grade_feedback (ai_grade_id, user_id, is_useful) VALUES (?, ?, true)",
        g,
        m1);
    String read = "SELECT count(*) FROM ai_grade_feedback WHERE ai_grade_id = ?";
    assertThat(as(m1, "member", c -> one(c, read, g))).isEqualTo(1L);
    assertThat(as(m2, "member", c -> one(c, read, g))).isEqualTo(0L);
    assertThat(as(chair, "member", c -> one(c, read, g))).isEqualTo(0L);
    assertThat(as(admin, "admin", c -> one(c, read, g))).isEqualTo(0L);
  }
}
