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
 * P5-1: report versions, final grades, copies, vendor results, exports and notifications. A
 * positive and a negative case per policy and trigger, and every allowed and refused status change
 * (DM Independence and access, Allowed status changes; UX-RPT-01, UX-RPT-06, UX-RPT-08).
 */
@Tag("integration")
class ReportSchemaIT {

  static Connection owner;
  static Connection app;
  static Connection ingest;

  static UUID admin, observer, chair, m1, m2, outsider;

  /** A tender with a committee, one vendor, one requirement, a baseline and a scoring model. */
  record World(UUID tender, UUID tv, UUID requirement, UUID baseline, UUID model) {}

  @BeforeAll
  static void connect() {
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

  /** Rows an UPDATE changed: row-level security filters silently, so a refusal shows as zero. */
  static int updated(Connection c, String sql, Object... args) {
    try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      return ps.executeUpdate();
    } catch (SQLException e) {
      return -1;
    }
  }

  static World world() {
    UUID tender =
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
    UUID requirement =
        uuid(
            one(
                owner,
                "INSERT INTO requirements (tender_id, text, importance, origin) VALUES (?, 'Encrypt data.', 'mandatory', 'manual')"
                    + " RETURNING id",
                tender));
    UUID baseline =
        uuid(
            one(
                owner,
                "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by)"
                    + " VALUES (?, 1, 1, ?) RETURNING id",
                tender,
                chair));
    UUID model =
        uuid(
            one(
                owner,
                "INSERT INTO scoring_models (tender_id, baseline_id) VALUES (?, ?) RETURNING id",
                tender,
                baseline));
    return new World(tender, tv, requirement, baseline, model);
  }

  /** The tender in consolidation: a report version opens and finalizes only there. */
  static void consolidation(World w) {
    String status = (String) one(owner, "SELECT status FROM tenders WHERE id = ?", w.tender());
    if (status.equals("draft")) {
      exec(owner, "UPDATE tenders SET status = 'requirements' WHERE id = ?", w.tender());
      status = "requirements";
    }
    if (status.equals("requirements")) {
      exec(owner, "UPDATE tenders SET status = 'evaluation' WHERE id = ?", w.tender());
      status = "evaluation";
    }
    if (status.equals("evaluation")) {
      exec(owner, "UPDATE tenders SET status = 'consolidation' WHERE id = ?", w.tender());
    }
  }

  static UUID version(World w) {
    consolidation(w);
    return uuid(
        one(
            owner,
            "INSERT INTO report_versions (tender_id, version_no, baseline_id, scoring_model_id, created_by)"
                + " VALUES (?, 1, ?, ?, ?) RETURNING id",
            w.tender(),
            w.baseline(),
            w.model(),
            chair));
  }

  static UUID memberEvaluation(World w, UUID member, boolean submitted) {
    UUID id =
        uuid(
            one(
                owner,
                "INSERT INTO member_evaluations (tender_vendor_id, user_id) VALUES (?, ?) RETURNING id",
                w.tv(),
                member));
    if (submitted) {
      exec(owner, "UPDATE member_evaluations SET status = 'in_progress' WHERE id = ?", id);
      exec(owner, "UPDATE member_evaluations SET status = 'submitted' WHERE id = ?", id);
    }
    return id;
  }

  static final String GRADE =
      "INSERT INTO report_grades (report_version_id, tender_vendor_id, requirement_id, final_grade, basis,"
          + " justification, decided_by, decided_at, has_disagreement)"
          + " VALUES (?, ?, ?, 'non_compliant', 'chair_decision', 'The vendor excludes it in section 4.', ?, now(), true)";

  static long count(UUID reader, String role, String table, UUID version) {
    return (Long)
        as(
            reader,
            role,
            c -> one(c, "SELECT count(*) FROM " + table + " WHERE report_version_id = ?", version));
  }

  // ---- report versions -------------------------------------------------------------------------

  @Test
  void onlyTheChairOpensAVersion() {
    World w = world();
    consolidation(w);
    String sql =
        "INSERT INTO report_versions (tender_id, version_no, baseline_id, scoring_model_id, created_by)"
            + " VALUES (?, 1, ?, ?, ?)";
    assertThat(
            as(chair, "member", c -> failure(c, sql, w.tender(), w.baseline(), w.model(), chair)))
        .isNull();
    assertThat(as(m1, "member", c -> failure(c, sql, w.tender(), w.baseline(), w.model(), m1)))
        .isNotNull();
    assertThat(as(admin, "admin", c -> failure(c, sql, w.tender(), w.baseline(), w.model(), admin)))
        .isNotNull();
    // The person named as creator must be the Chair even when the policy is satisfied by the role.
    assertThat(as(chair, "member", c -> failure(c, sql, w.tender(), w.baseline(), w.model(), m1)))
        .isNotNull();
  }

  @Test
  void aTenderHasOneInProgressVersion() {
    World w = world();
    version(w);
    String second =
        "INSERT INTO report_versions (tender_id, version_no, baseline_id, scoring_model_id, created_by)"
            + " VALUES (?, 2, ?, ?, ?)";
    assertThat(failure(owner, second, w.tender(), w.baseline(), w.model(), chair)).isNotNull();
  }

  @Test
  void committeeAndAdminReadTheVersionRowButTheObserverOnlyAPublishedOne() {
    World w = world();
    UUID v = version(w);
    String read = "SELECT count(*) FROM report_versions WHERE id = ?";
    assertThat(as(chair, "member", c -> one(c, read, v))).isEqualTo(1L);
    assertThat(as(m1, "member", c -> one(c, read, v))).isEqualTo(1L);
    assertThat(as(admin, "admin", c -> one(c, read, v))).isEqualTo(1L);
    assertThat(as(observer, "observer", c -> one(c, read, v))).isEqualTo(0L);
    assertThat(as(outsider, "member", c -> one(c, read, v))).isEqualTo(0L);
    finalize(w, v);
    assertThat(as(observer, "observer", c -> one(c, read, v))).isEqualTo(1L);
    assertThat(as(outsider, "member", c -> one(c, read, v))).isEqualTo(0L);
  }

  /** A vendor result is on the version, so it is complete enough to finalize. */
  static void results(World w, UUID v) {
    exec(
        owner,
        "INSERT INTO report_vendor_results (report_version_id, tender_vendor_id, outcome, rank, final_score,"
            + " compliance_percent, agreement_level) VALUES (?, ?, 'evaluated', 1, 50, 50, 100)"
            + " ON CONFLICT DO NOTHING",
        v,
        w.tv());
  }

  static void finalize(World w, UUID v) {
    results(w, v);
    exec(
        owner,
        "UPDATE report_versions SET status = 'finalized', finalized_by = ?, finalized_at = now() WHERE id = ?",
        chair,
        v);
  }

  @Test
  void onlyTheChairFinalizesAndOnlyOnce() {
    World w = world();
    UUID v = version(w);
    results(w, v);
    String fin =
        "UPDATE report_versions SET status = 'finalized', finalized_by = ?, finalized_at = now() WHERE id = ?";
    assertThat(as(m1, "member", c -> updated(c, fin, m1, v))).isEqualTo(0);
    assertThat(as(chair, "member", c -> updated(c, fin, m1, v))).isEqualTo(-1);
    assertThat(as(chair, "member", c -> updated(c, fin, chair, v))).isEqualTo(1);
    finalize(w, v);
    // A finalized version cannot go back, and no column of it changes.
    assertThat(
            failure(
                owner,
                "UPDATE report_versions SET status = 'in_progress', finalized_at = NULL, finalized_by = NULL WHERE id = ?",
                v))
        .isNotNull();
    assertThat(
            failure(
                owner,
                "UPDATE report_versions SET baseline_id = baseline_id, version_no = 9 WHERE id = ?",
                v))
        .isNotNull();
    assertThat(failure(owner, "DELETE FROM report_versions WHERE id = ?", v)).isNotNull();
  }

  @Test
  void reopeningAddsANextVersionWithAReasonAndTheOldOneIsSupersededOnFinalize() {
    World w = world();
    UUID v1 = version(w);
    finalize(w, v1);
    String reopen =
        "INSERT INTO report_versions (tender_id, version_no, baseline_id, scoring_model_id, created_by,"
            + " reopened_from_version_id, reopen_reason) VALUES (?, 2, ?, ?, ?, ?, ?)";
    assertThat(failure(owner, reopen, w.tender(), w.baseline(), w.model(), chair, v1, "too short"))
        .isNotNull();
    UUID v2 = UUID.randomUUID();
    exec(
        owner,
        "INSERT INTO report_versions (id, tender_id, version_no, baseline_id, scoring_model_id, created_by,"
            + " reopened_from_version_id, reopen_reason) VALUES (?, ?, 2, ?, ?, ?, ?, ?)",
        v2,
        w.tender(),
        w.baseline(),
        w.model(),
        chair,
        v1,
        "Vendor sent a clarification");
    // The earlier version stays finalized and readable until the new one is finalized.
    assertThat(one(owner, "SELECT status FROM report_versions WHERE id = ?", v1))
        .isEqualTo("finalized");
    exec(owner, "UPDATE report_versions SET status = 'superseded' WHERE id = ?", v1);
    finalize(w, v2);
    assertThat(one(owner, "SELECT status FROM report_versions WHERE id = ?", v1))
        .isEqualTo("superseded");
    assertThat(failure(owner, "UPDATE report_versions SET status = 'finalized' WHERE id = ?", v1))
        .isNotNull();
  }

  @Test
  void onlyAFinalizedVersionCanBeReopened() {
    World w = world();
    UUID v1 = version(w);
    String reopen =
        "INSERT INTO report_versions (tender_id, version_no, baseline_id, scoring_model_id, created_by,"
            + " reopened_from_version_id, reopen_reason) VALUES (?, 2, ?, ?, ?, ?, ?)";
    // v1 is still in progress, so a second in-progress version also hits the unique index.
    assertThat(
            failure(
                owner,
                reopen,
                w.tender(),
                w.baseline(),
                w.model(),
                chair,
                v1,
                "Needs another look"))
        .isNotNull();
  }

  // ---- final grades
  // ------------------------------------------------------------------------------

  @Test
  void onlyTheChairRecordsAFinalGradeWithAJustification() {
    World w = world();
    UUID v = version(w);
    assertThat(as(m1, "member", c -> failure(c, GRADE, v, w.tv(), w.requirement(), m1)))
        .isNotNull();
    assertThat(as(admin, "admin", c -> failure(c, GRADE, v, w.tv(), w.requirement(), admin)))
        .isNotNull();
    assertThat(as(chair, "member", c -> failure(c, GRADE, v, w.tv(), w.requirement(), m1)))
        .isNotNull();
    assertThat(as(chair, "member", c -> failure(c, GRADE, v, w.tv(), w.requirement(), chair)))
        .isNull();
    assertThat(
            as(
                chair,
                "member",
                c ->
                    failure(
                        c,
                        "INSERT INTO report_grades (report_version_id, tender_vendor_id, requirement_id, final_grade,"
                            + " basis, justification, decided_by, decided_at, has_disagreement)"
                            + " VALUES (?, ?, ?, 'non_compliant', 'chair_decision', 'short', ?, now(), true)",
                        v,
                        w.tv(),
                        w.requirement(),
                        chair)))
        .isNotNull();
  }

  @Test
  void aFinalGradeCanBeChangedWhileInProgressButNeverDeletedOrAfterFinalize() {
    World w = world();
    UUID v = version(w);
    exec(owner, GRADE, v, w.tv(), w.requirement(), chair);
    assertThat(
            as(
                chair,
                "member",
                c ->
                    failure(
                        c,
                        "UPDATE report_grades SET final_grade = 'partially_compliant', justification ="
                            + " 'After reading the annex again.', decided_by = ?, decided_at = now()"
                            + " WHERE report_version_id = ?",
                        chair,
                        v)))
        .isNull();
    // The grade's identity never changes.
    assertThat(
            failure(
                owner,
                "UPDATE report_grades SET requirement_id = ? WHERE report_version_id = ?",
                UUID.randomUUID(),
                v))
        .isNotNull();
    assertThat(failure(owner, "DELETE FROM report_grades WHERE report_version_id = ?", v))
        .isNotNull();
    finalize(w, v);
    assertThat(
            failure(
                owner,
                "UPDATE report_grades SET justification = 'Edited after finalizing.' WHERE report_version_id = ?",
                v))
        .isNotNull();
    assertThat(failure(owner, GRADE, v, w.tv(), UUID.randomUUID(), chair)).isNotNull();
  }

  // ---- reading the results (UX-RPT-01)
  // --------------------------------------------------------------

  @Test
  void anInProgressReportOpensToAdminAndChairOnlyOnceEveryMemberHasSubmitted() {
    World w = world();
    UUID v = version(w);
    exec(owner, GRADE, v, w.tv(), w.requirement(), chair);
    UUID e1 = memberEvaluation(w, m1, true);
    UUID e2 = memberEvaluation(w, m2, false);
    // One member has not submitted: nobody reads the result, not even the Chair or the admin.
    assertThat(count(chair, "member", "report_grades", v)).isZero();
    assertThat(count(admin, "admin", "report_grades", v)).isZero();
    exec(owner, "UPDATE member_evaluations SET status = 'in_progress' WHERE id = ?", e2);
    exec(owner, "UPDATE member_evaluations SET status = 'submitted' WHERE id = ?", e2);
    assertThat(e1).isNotEqualTo(e2);
    assertThat(count(chair, "member", "report_grades", v)).isEqualTo(1L);
    assertThat(count(admin, "admin", "report_grades", v)).isEqualTo(1L);
    // Members and the Observer wait for finalization, whatever the progress.
    assertThat(count(m1, "member", "report_grades", v)).isZero();
    assertThat(count(observer, "observer", "report_grades", v)).isZero();
    assertThat(count(outsider, "member", "report_grades", v)).isZero();
    finalize(w, v);
    assertThat(count(m1, "member", "report_grades", v)).isEqualTo(1L);
    assertThat(count(observer, "observer", "report_grades", v)).isEqualTo(1L);
    assertThat(count(admin, "admin", "report_grades", v)).isEqualTo(1L);
    assertThat(count(outsider, "member", "report_grades", v)).isZero();
  }

  @Test
  void aFormalExclusionCountsAsDoneForTheRelease() {
    World w = world();
    UUID v = version(w);
    exec(owner, GRADE, v, w.tv(), w.requirement(), chair);
    memberEvaluation(w, m1, true);
    UUID e2 = memberEvaluation(w, m2, false);
    assertThat(count(chair, "member", "report_grades", v)).isZero();
    // A declared conflict excludes the member from an existing evaluation (V20 trigger).
    exec(
        owner,
        "INSERT INTO conflict_declarations (tender_vendor_id, user_id, has_conflict, statement) VALUES (?, ?, true, ?)",
        w.tv(),
        m2,
        "I advised this vendor last year");
    assertThat(
            one(owner, "SELECT excluded_at IS NOT NULL FROM member_evaluations WHERE id = ?", e2))
        .isEqualTo(true);
    assertThat(count(chair, "member", "report_grades", v)).isEqualTo(1L);
  }

  /** A member's row for the world's requirement, decided as the owner (override with a note). */
  static void decide(World w, UUID evaluation, String grade, String note) {
    exec(
        owner,
        "INSERT INTO member_evaluation_rows (member_evaluation_id, requirement_id) VALUES (?, ?)",
        evaluation,
        w.requirement());
    exec(
        owner,
        "UPDATE member_evaluation_rows SET review_state = 'overridden', member_grade = ?, note = ?"
            + " WHERE member_evaluation_id = ?",
        grade,
        note,
        evaluation);
  }

  @Test
  void memberGradesAreCopiedFromTheirOwnRowOnceAndNeverChange() {
    World w = world();
    UUID v = version(w);
    exec(owner, GRADE, v, w.tv(), w.requirement(), chair);
    UUID g = uuid(one(owner, "SELECT id FROM report_grades WHERE report_version_id = ?", v));
    UUID e1 = memberEvaluation(w, m1, false);
    decide(w, e1, "fully_compliant", "The annex covers it fully.");
    String copy =
        "INSERT INTO report_member_grades (report_grade_id, user_id, member_grade, note, review_state)"
            + " VALUES (?, ?, 'fully_compliant', 'The annex covers it fully.', 'overridden')";
    assertThat(as(m1, "member", c -> failure(c, copy, g, m1))).isNotNull();
    assertThat(as(chair, "member", c -> failure(c, copy, g, m1))).isNull();
    // A copy that differs from the member's row, or for someone with no row, is refused.
    assertThat(
            failure(
                owner,
                "INSERT INTO report_member_grades (report_grade_id, user_id, member_grade, note, review_state)"
                    + " VALUES (?, ?, 'non_compliant', 'The annex covers it fully.', 'overridden')",
                g,
                m1))
        .isNotNull();
    assertThat(failure(owner, copy, g, m2)).isNotNull();
    exec(owner, copy, g, m1);
    assertThat(
            failure(
                owner, "UPDATE report_member_grades SET note = 'x' WHERE report_grade_id = ?", g))
        .isNotNull();
    assertThat(failure(owner, "DELETE FROM report_member_grades WHERE report_grade_id = ?", g))
        .isNotNull();
  }

  @Test
  void allAgreeIsTrueOnlyWhenEveryMemberGradedItTheSame() {
    World w = world();
    UUID v = version(w);
    UUID e1 = memberEvaluation(w, m1, false);
    UUID e2 = memberEvaluation(w, m2, false);
    decide(w, e1, "fully_compliant", "The annex covers it fully.");
    String agree =
        "INSERT INTO report_grades (report_version_id, tender_vendor_id, requirement_id, final_grade, basis,"
            + " has_disagreement) VALUES (?, ?, ?, ?, 'all_agree', ?)";
    // m2 has no decision yet, so nobody can claim agreement.
    assertThat(failure(owner, agree, v, w.tv(), w.requirement(), "fully_compliant", false))
        .isNotNull();
    decide(w, e2, "partially_compliant", "Only encrypted in transit, not at rest.");
    assertThat(failure(owner, agree, v, w.tv(), w.requirement(), "fully_compliant", false))
        .isNotNull();
    assertThat(failure(owner, agree, v, w.tv(), w.requirement(), "fully_compliant", true))
        .isNotNull();
    // Same grade from both members: agreement holds; flipping a Chair decision to it is checked
    // too.
    exec(
        owner,
        "UPDATE member_evaluation_rows SET member_grade = 'fully_compliant', note = 'Agreed after the call.'"
            + " WHERE member_evaluation_id = ?",
        e2);
    assertThat(failure(owner, agree, v, w.tv(), w.requirement(), "fully_compliant", false))
        .isNull();
    assertThat(failure(owner, agree, v, w.tv(), UUID.randomUUID(), "fully_compliant", false))
        .isNotNull();
  }

  @Test
  void aFinalizedVersionIsSupersededOnlyByItsSuccessorAndOnlyACompleteOneFinalizes() {
    World w = world();
    UUID v1 = version(w);
    // No result at all: the version is not complete.
    String fin =
        "UPDATE report_versions SET status = 'finalized', finalized_by = ?, finalized_at = now() WHERE id = ?";
    assertThat(failure(owner, fin, chair, v1)).isNotNull();
    finalize(w, v1);
    assertThat(failure(owner, "UPDATE report_versions SET status = 'superseded' WHERE id = ?", v1))
        .isNotNull();
  }

  @Test
  void aVersionOpensAndFinalizesOnlyInConsolidation() {
    World w = world();
    String sql =
        "INSERT INTO report_versions (tender_id, version_no, baseline_id, scoring_model_id, created_by)"
            + " VALUES (?, 1, ?, ?, ?)";
    assertThat(failure(owner, sql, w.tender(), w.baseline(), w.model(), chair)).isNotNull();
  }

  @Test
  void aVersionAndItsRowsStayInsideTheirTender() {
    World w = world();
    World other = world();
    consolidation(w);
    String sql =
        "INSERT INTO report_versions (tender_id, version_no, baseline_id, scoring_model_id, created_by)"
            + " VALUES (?, 1, ?, ?, ?)";
    assertThat(failure(owner, sql, w.tender(), other.baseline(), w.model(), chair)).isNotNull();
    assertThat(failure(owner, sql, w.tender(), w.baseline(), other.model(), chair)).isNotNull();
    UUID v = version(w);
    assertThat(failure(owner, GRADE, v, other.tv(), w.requirement(), chair)).isNotNull();
    assertThat(failure(owner, GRADE, v, w.tv(), other.requirement(), chair)).isNotNull();
    assertThat(
            failure(
                owner,
                "INSERT INTO report_vendor_results (report_version_id, tender_vendor_id, outcome)"
                    + " VALUES (?, ?, 'evaluated')",
                v,
                other.tv()))
        .isNotNull();
  }

  @Test
  void vendorResultsAreWrittenOnceWhileInProgress() {
    World w = world();
    UUID v = version(w);
    String insert =
        "INSERT INTO report_vendor_results (report_version_id, tender_vendor_id, outcome, rank, final_score,"
            + " compliance_percent, agreement_level) VALUES (?, ?, 'evaluated', 1, 82.5, 70, 90)";
    assertThat(as(m1, "member", c -> failure(c, insert, v, w.tv()))).isNotNull();
    assertThat(as(chair, "member", c -> failure(c, insert, v, w.tv()))).isNull();
    exec(owner, insert, v, w.tv());
    assertThat(failure(owner, insert, v, w.tv())).isNotNull();
    assertThat(
            failure(
                owner,
                "UPDATE report_vendor_results SET final_score = 10 WHERE report_version_id = ?",
                v))
        .isNotNull();
    assertThat(failure(owner, "DELETE FROM report_vendor_results WHERE report_version_id = ?", v))
        .isNotNull();
  }

  @Test
  void aRejectedVendorKeepsItsReasonAndHasNoRank() {
    World w = world();
    UUID v = version(w);
    assertThat(
            failure(
                owner,
                "INSERT INTO report_vendor_results (report_version_id, tender_vendor_id, outcome, rank)"
                    + " VALUES (?, ?, 'rejected_at_screening', 1)",
                v,
                w.tv()))
        .isNotNull();
    assertThat(
            failure(
                owner,
                "INSERT INTO report_vendor_results (report_version_id, tender_vendor_id, outcome, reason)"
                    + " VALUES (?, ?, 'rejected_at_screening', 'Missing the signed form')",
                v,
                w.tv()))
        .isNull();
  }

  // ---- tender stage moves
  // ------------------------------------------------------------------------

  @Test
  void theChairFinalizesAndReopensTheTenderStageAndNobodyElseDoes() {
    World w = world();
    exec(owner, "UPDATE tenders SET status = 'requirements' WHERE id = ?", w.tender());
    exec(owner, "UPDATE tenders SET status = 'evaluation' WHERE id = ?", w.tender());
    exec(owner, "UPDATE tenders SET status = 'consolidation' WHERE id = ?", w.tender());
    String move = "SELECT app_chair_report_stage(?, ?)";
    assertThat(as(m1, "member", c -> failure(c, move, w.tender(), "finalized"))).isNotNull();
    assertThat(as(admin, "admin", c -> failure(c, move, w.tender(), "finalized"))).isNotNull();
    assertThat(as(chair, "member", c -> failure(c, move, w.tender(), "evaluation"))).isNotNull();
    assertThat(as(chair, "member", c -> failure(c, move, w.tender(), "finalized"))).isNull();
    exec(owner, "UPDATE tenders SET status = 'finalized' WHERE id = ?", w.tender());
    assertThat(as(chair, "member", c -> failure(c, move, w.tender(), "consolidation"))).isNull();
    assertThat(as(chair, "member", c -> failure(c, move, w.tender(), "requirements"))).isNotNull();
  }

  @Test
  void theTenderAdvancesToConsolidationOnlyWhenEveryEvaluationIsIn() {
    World w = world();
    exec(owner, "UPDATE tenders SET status = 'requirements' WHERE id = ?", w.tender());
    exec(owner, "UPDATE tenders SET status = 'evaluation' WHERE id = ?", w.tender());
    UUID vendorProposal =
        uuid(
            one(
                owner,
                "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by) VALUES (?, 1, ?) RETURNING id",
                w.tv(),
                admin));
    for (String s : new String[] {"in_screening", "accepted", "evaluating", "evaluated"}) {
      exec(owner, "UPDATE proposal_versions SET status = ? WHERE id = ?", s, vendorProposal);
    }
    memberEvaluation(w, m1, true);
    UUID e2 = memberEvaluation(w, m2, false);
    String advance = "SELECT app_advance_to_consolidation(?)";
    assertThat(as(outsider, "member", c -> failure(c, advance, w.tender()))).isNotNull();
    assertThat(as(m1, "member", c -> one(c, advance, w.tender()))).isEqualTo(false);
    exec(owner, "UPDATE member_evaluations SET status = 'in_progress' WHERE id = ?", e2);
    exec(owner, "UPDATE member_evaluations SET status = 'submitted' WHERE id = ?", e2);
    assertThat(as(m1, "member", c -> one(c, advance, w.tender()))).isEqualTo(true);
  }

  // ---- exports
  // ------------------------------------------------------------------------------------

  @Test
  void anExportBelongsToThePersonWhoAskedAndTheAdministratorReadsAll() {
    World w = world();
    UUID id = UUID.randomUUID();
    String insert =
        "INSERT INTO exports (id, tender_id, export_type, source_type, format, requested_by)"
            + " VALUES (?, ?, 'requirements_list', 'baseline', 'xlsx', ?)";
    assertThat(as(m1, "member", c -> failure(c, insert, id, w.tender(), m2))).isNotNull();
    assertThat(as(m1, "member", c -> failure(c, insert, id, w.tender(), m1))).isNull();
    exec(owner, insert, id, w.tender(), m1);
    String read = "SELECT count(*) FROM exports WHERE id = ?";
    assertThat(as(m1, "member", c -> one(c, read, id))).isEqualTo(1L);
    assertThat(as(m2, "member", c -> one(c, read, id))).isEqualTo(0L);
    assertThat(as(admin, "admin", c -> one(c, read, id))).isEqualTo(1L);
    assertThat(as(observer, "observer", c -> one(c, read, id))).isEqualTo(0L);
  }

  @Test
  void anExportOwnerChangesOnlyItsProgressAndOnlyToItsOwnKey() {
    World w = world();
    UUID id = UUID.randomUUID();
    exec(
        owner,
        "INSERT INTO exports (id, tender_id, export_type, source_type, format, requested_by)"
            + " VALUES (?, ?, 'requirements_list', 'baseline', 'xlsx', ?)",
        id,
        w.tender(),
        m1);
    // Not the tender, and not another export's file.
    assertThat(
            as(
                m1,
                "member",
                c -> updated(c, "UPDATE exports SET tender_id = NULL WHERE id = ?", id)))
        .isEqualTo(-1);
    assertThat(
            as(
                m1,
                "member",
                c ->
                    updated(
                        c,
                        "UPDATE exports SET status = 'ready', storage_key = ? WHERE id = ?",
                        "exports/%s/%s.xlsx".formatted(w.tender(), UUID.randomUUID()),
                        id)))
        .isEqualTo(-1);
    assertThat(
            as(
                m1,
                "member",
                c ->
                    updated(
                        c,
                        "UPDATE exports SET status = 'ready', storage_key = ? WHERE id = ?",
                        "exports/%s/%s.xlsx".formatted(w.tender(), id),
                        id)))
        .isEqualTo(1);
    // A request for a tender one is not on is refused.
    assertThat(
            as(
                outsider,
                "member",
                c ->
                    failure(
                        c,
                        "INSERT INTO exports (tender_id, export_type, source_type, format, requested_by)"
                            + " VALUES (?, 'requirements_list', 'baseline', 'xlsx', ?)",
                        w.tender(),
                        outsider)))
        .isNotNull();
  }

  @Test
  void anExportMovesPendingToReadyOrFailedOnlyAndReadyNeedsAFile() {
    World w = world();
    UUID id =
        uuid(
            one(
                owner,
                "INSERT INTO exports (tender_id, export_type, source_type, format, requested_by)"
                    + " VALUES (?, 'audit_log', 'audit_filter', 'csv', ?) RETURNING id",
                w.tender(),
                admin));
    assertThat(failure(owner, "UPDATE exports SET status = 'ready' WHERE id = ?", id)).isNotNull();
    assertThat(
            failure(
                owner,
                "UPDATE exports SET status = 'ready', storage_key = ? WHERE id = ?",
                "exports/%s/%s.csv".formatted(w.tender(), id),
                id))
        .isNull();
    assertThat(failure(owner, "UPDATE exports SET status = 'pending' WHERE id = ?", id))
        .isNotNull();
    assertThat(
            failure(
                owner, "UPDATE exports SET status = 'failed', failure_code = 'x' WHERE id = ?", id))
        .isNotNull();
  }

  // ---- notifications
  // --------------------------------------------------------------------------------

  @Test
  void aNotificationIsCreatedOnlyThroughTheFunctionAndReadByItsRecipient() {
    World w = world();
    String direct =
        "INSERT INTO notifications (user_id, notification_type) VALUES (?, 'manual_reminder')";
    assertThat(as(m1, "member", c -> failure(c, direct, m2))).isNotNull();
    // Through the function the row exists for the recipient, inside the caller's transaction.
    String notify = "SELECT app_notify(?, ?, 'manual_reminder', 'tender', ?, '{}'::jsonb)";
    assertThat(as(chair, "member", c -> one(c, notify, m1, w.tender(), w.tender()))).isNotNull();
    UUID n =
        uuid(
            one(
                owner,
                "INSERT INTO notifications (user_id, tender_id, notification_type) VALUES (?, ?, 'manual_reminder')"
                    + " RETURNING id",
                m1,
                w.tender()));
    String read = "SELECT count(*) FROM notifications WHERE id = ?";
    assertThat(as(m1, "member", c -> one(c, read, n))).isEqualTo(1L);
    assertThat(as(m2, "member", c -> one(c, read, n))).isEqualTo(0L);
    assertThat(as(admin, "admin", c -> one(c, read, n))).isEqualTo(0L);
    // Only read_at changes, and only the recipient changes it.
    assertThat(
            as(
                m1,
                "member",
                c -> updated(c, "UPDATE notifications SET read_at = now() WHERE id = ?", n)))
        .isEqualTo(1);
    assertThat(
            as(
                m2,
                "member",
                c -> updated(c, "UPDATE notifications SET read_at = now() WHERE id = ?", n)))
        .isEqualTo(0);
    assertThat(
            as(
                m1,
                "member",
                c -> updated(c, "UPDATE notifications SET params = '{}' WHERE id = ?", n)))
        .isEqualTo(-1);
  }

  @Test
  void aNotificationFunctionRefusesAnOutsiderAndSkipsAnUnrelatedRecipient() {
    World w = world();
    String notify = "SELECT app_notify(?, ?, 'manual_reminder', 'tender', ?, '{}'::jsonb)";
    assertThat(as(outsider, "member", c -> failure(c, notify, m1, w.tender(), w.tender())))
        .isNotNull();
    assertThat(as(chair, "member", c -> failure(c, notify, m1, w.tender(), w.tender()))).isNull();
    // A member of another tender is not a recipient of this tender's notifications.
    assertThat(as(chair, "member", c -> one(c, notify, outsider, w.tender(), w.tender()))).isNull();
    assertThat(as(null, null, c -> failure(c, notify, m1, w.tender(), w.tender()))).isNotNull();
    // A member sends only the types their own actions raise; nobody sends one with no tender.
    assertThat(as(m2, "member", c -> failure(c, notify, m1, w.tender(), w.tender()))).isNotNull();
    String own = "SELECT app_notify(?, ?, 'conflict_declared', 'tender', ?, '{}'::jsonb)";
    assertThat(as(m2, "member", c -> failure(c, own, chair, w.tender(), w.tender()))).isNull();
    assertThat(
            as(
                chair,
                "member",
                c ->
                    failure(
                        c,
                        "SELECT app_notify(?, NULL, 'manual_reminder', 'tender', NULL, '{}'::jsonb)",
                        m1)))
        .isNotNull();
    assertThat(as(observer, "observer", c -> failure(c, notify, m1, w.tender(), w.tender())))
        .isNotNull();
    String big = "x".repeat(2100);
    assertThat(
            as(
                chair,
                "member",
                c ->
                    failure(
                        c,
                        "SELECT app_notify(?, ?, 'manual_reminder', 'tender', ?, jsonb_build_object('k', ?::text))",
                        m1,
                        w.tender(),
                        w.tender(),
                        big)))
        .isNotNull();
  }

  @Test
  void aRemovedMemberNoLongerReadsTenderNotifications() {
    World w = world();
    UUID n =
        uuid(
            one(
                owner,
                "INSERT INTO notifications (user_id, tender_id, notification_type) VALUES (?, ?, 'manual_reminder')"
                    + " RETURNING id",
                m1,
                w.tender()));
    String read = "SELECT count(*) FROM notifications WHERE id = ?";
    assertThat(as(m1, "member", c -> one(c, read, n))).isEqualTo(1L);
    exec(
        owner,
        "UPDATE tender_members SET status = 'removed', removed_at = now() WHERE tender_id = ? AND user_id = ?",
        w.tender(),
        m1);
    assertThat(as(m1, "member", c -> one(c, read, n))).isEqualTo(0L);
  }

  @Test
  void emailDeliveryIsRecordedBySystemCodeOnly() {
    World w = world();
    exec(
        owner,
        "INSERT INTO notification_preferences (user_id, notification_type, email_enabled) VALUES (?, 'manual_reminder', true)",
        m1);
    UUID n =
        uuid(
            one(
                owner,
                "INSERT INTO notifications (user_id, tender_id, notification_type, email_status) VALUES (?, ?,"
                    + " 'manual_reminder', 'pending') RETURNING id",
                m1,
                w.tender()));
    // A signed-in person sees no queue and cannot mark delivery.
    assertThat(
            as(m1, "member", c -> one(c, "SELECT count(*) FROM app_notifications_email_next(50)")))
        .isEqualTo(0L);
    assertThat(as(null, null, c -> one(c, "SELECT count(*) FROM app_notifications_email_next(50)")))
        .isNotEqualTo(0L);
    assertThat(
            as(
                m1,
                "member",
                c -> {
                  exec(c, "SELECT app_notification_email_result(?, 'sent')", n);
                  return one(c, "SELECT email_status FROM notifications WHERE id = ?", n);
                }))
        .isEqualTo("pending");
    exec(owner, "SELECT app_notification_email_result(?, 'sent')", n);
    assertThat(one(owner, "SELECT email_status FROM notifications WHERE id = ?", n))
        .isEqualTo("sent");
  }

  @Test
  void preferencesBelongToTheirOwner() {
    String insert =
        "INSERT INTO notification_preferences (user_id, notification_type, email_enabled)"
            + " VALUES (?, 'report_finalized', true)";
    assertThat(as(m1, "member", c -> failure(c, insert, m2))).isNotNull();
    assertThat(as(m1, "member", c -> failure(c, insert, m1))).isNull();
    assertThat(
            failure(
                owner,
                "INSERT INTO notification_preferences (user_id, notification_type, email_enabled)"
                    + " VALUES (?, 'not_a_type', true)",
                m1))
        .isNotNull();
  }

  @Test
  void theAiIngestRoleHasNoPrivilegeOnAnyReportTable() {
    for (String t :
        new String[] {
          "report_versions",
          "report_grades",
          "report_member_grades",
          "report_vendor_results",
          "exports",
          "notifications",
          "notification_preferences"
        }) {
      assertThat(failure(ingest, "SELECT count(*) FROM " + t)).as(t).isNotNull();
    }
  }
}
