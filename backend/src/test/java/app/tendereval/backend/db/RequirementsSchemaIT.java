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
 * P3-1: requirements, baselines, snapshot, scoring and AI runs. A positive and a negative case per
 * policy and trigger, and every allowed and refused status change (DM Independence and access,
 * Allowed status changes).
 */
@Tag("integration")
class RequirementsSchemaIT {

  static Connection owner;
  static Connection app;
  static Connection ingest;

  static UUID admin, observer, chair, member, outsider;
  static UUID tender, other, category;

  @BeforeAll
  static void world() {
    SchemaFixture.ensureStarted();
    owner = SchemaFixture.owner();
    app = SchemaFixture.app();
    ingest = SchemaFixture.ingest();
    admin = user("admin");
    observer = user("observer");
    chair = user("member");
    member = user("member");
    outsider = user("member");
    tender = tender();
    other = tender();
    exec(
        owner,
        "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
        tender,
        chair);
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", tender, member);
    category =
        uuid(
            one(
                owner,
                "INSERT INTO requirement_categories (name) VALUES (?) RETURNING id",
                "Security " + UUID.randomUUID()));
  }

  @AfterAll
  static void close() throws SQLException {
    owner.close();
    app.close();
    ingest.close();
  }

  // ---- helpers -------------------------------------------------------------------------------

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

  static UUID tender() {
    return uuid(
        one(
            owner,
            "INSERT INTO tenders (name, created_by) VALUES (?, ?) RETURNING id",
            "T " + UUID.randomUUID(),
            admin));
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

  static UUID requirement(UUID t, String origin, String state) {
    return uuid(
        one(
            owner,
            "INSERT INTO requirements (tender_id, text, importance, origin, review_state, source_item_id)"
                + " VALUES (?, 'The supplier shall encrypt data.', 'mandatory', ?, ?, ?) RETURNING id",
            t,
            origin,
            state,
            "ai".equals(origin) ? UUID.randomUUID() : null));
  }

  static String insertManual(Connection c, UUID t) {
    return failure(
        c,
        "INSERT INTO requirements (tender_id, text, importance, origin) VALUES (?, 'Manual one', 'optional', 'manual')",
        t);
  }

  // ---- reads --------------------------------------------------------------------------------

  @Test
  void aCommitteeMemberAndTheAdministratorReadRequirementsOthersDoNot() {
    UUID r = requirement(tender, "ai", "unreviewed");
    String sql = "SELECT count(*) FROM requirements WHERE id = ?";
    assertThat(as(member, "member", c -> one(c, sql, r))).isEqualTo(1L);
    assertThat(as(chair, "member", c -> one(c, sql, r))).isEqualTo(1L);
    assertThat(as(admin, "admin", c -> one(c, sql, r))).isEqualTo(1L);
    assertThat(as(outsider, "member", c -> one(c, sql, r))).isEqualTo(0L);
    assertThat(as(observer, "observer", c -> one(c, sql, r))).isEqualTo(0L);
  }

  // ---- writes -------------------------------------------------------------------------------

  @Test
  void aMemberAddsAManualRequirementAndTheNumberIsAssignedAndNeverReused() {
    UUID t = tender();
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", t, member);
    assertThat(as(member, "member", c -> insertManual(c, t))).isNull();
    assertThat(as(member, "member", c -> insertManual(c, t))).isNull();
    // numbers come from the insert, in order, per tender
    UUID r1 = requirement(t, "manual", "reviewed");
    assertThat(one(owner, "SELECT display_no FROM requirements WHERE id = ?", r1))
        .isEqualTo("REQ-001");
    exec(
        owner,
        "UPDATE requirements SET deleted_at = now(), deleted_by = ?, delete_reason = 'other' WHERE id = ?",
        admin,
        r1);
    UUID r2 = requirement(t, "manual", "reviewed");
    assertThat(one(owner, "SELECT display_no FROM requirements WHERE id = ?", r2))
        .isEqualTo("REQ-002");
    // another tender counts on its own
    UUID x = requirement(other, "manual", "reviewed");
    assertThat(one(owner, "SELECT display_no FROM requirements WHERE id = ?", x))
        .isEqualTo("REQ-001");
  }

  @Test
  void onlyAnActiveMemberAddsAndNobodyAddsAnAiRequirementThroughTheApplicationRole() {
    assertThat(as(outsider, "member", c -> insertManual(c, tender))).isNotNull();
    assertThat(as(admin, "admin", c -> insertManual(c, tender))).isNotNull();
    assertThat(as(observer, "observer", c -> insertManual(c, tender))).isNotNull();
    assertThat(
            as(
                member,
                "member",
                c ->
                    failure(
                        c,
                        "INSERT INTO requirements (tender_id, text, importance, origin, source_item_id)"
                            + " VALUES (?, 'x', 'mandatory', 'ai', ?)",
                        tender,
                        UUID.randomUUID())))
        .isNotNull();
  }

  @Test
  void aMemberEditsWhileNoBaselineIsConfirmedAndNotAfter() {
    UUID t = tender();
    exec(
        owner,
        "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
        t,
        chair);
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", t, member);
    UUID r = requirement(t, "ai", "unreviewed");
    String edit =
        "UPDATE requirements SET text = 'Edited text', review_state = 'reviewed' WHERE id = ?";
    assertThat(as(member, "member", c -> failure(c, edit, r))).isNull();
    assertThat(
            as(
                outsider,
                "member",
                c ->
                    rows(c, "UPDATE requirements SET text = 'x' WHERE id = ? RETURNING id", r)
                        .size()))
        .isEqualTo(0);

    exec(owner, "UPDATE requirements SET review_state = 'reviewed' WHERE id = ?", r);
    exec(
        owner,
        "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by) VALUES (?, 1, 1, ?)",
        t,
        chair);
    // a confirmed baseline locks the list for members
    assertThat(
            as(
                member,
                "member",
                c ->
                    rows(
                            c,
                            "UPDATE requirements SET text = 'Late edit' WHERE id = ? RETURNING id",
                            r)
                        .size()))
        .isEqualTo(0);
    assertThat(as(member, "member", c -> insertManual(c, t))).isNotNull();
  }

  // ---- status changes ----------------------------------------------------------------------

  @Test
  void everyAllowedReviewStateChangePassesAndTheOthersAreRefused() {
    UUID a = requirement(tender, "ai", "unreviewed");
    assertThat(failure(owner, "UPDATE requirements SET review_state = 'reviewed' WHERE id = ?", a))
        .isNull();
    assertThat(failure(owner, "UPDATE requirements SET review_state = 'confirmed' WHERE id = ?", a))
        .isNull();
    assertThat(failure(owner, "UPDATE requirements SET review_state = 'reviewed' WHERE id = ?", a))
        .isNull();

    UUID b = requirement(tender, "ai", "unreviewed");
    // unreviewed to confirmed, and reviewed back to unreviewed, are not allowed
    assertThat(failure(owner, "UPDATE requirements SET review_state = 'confirmed' WHERE id = ?", b))
        .isEqualTo("23514");
    exec(owner, "UPDATE requirements SET review_state = 'reviewed' WHERE id = ?", b);
    assertThat(
            failure(owner, "UPDATE requirements SET review_state = 'unreviewed' WHERE id = ?", b))
        .isEqualTo("23514");
  }

  // ---- baselines -----------------------------------------------------------------------------

  @Test
  void onlyTheChairConfirmsAndReopensAndAConfirmedBaselineIsLocked() {
    UUID t = tender();
    exec(
        owner,
        "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
        t,
        chair);
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", t, member);
    String insert =
        "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by) VALUES (?, 1, 0, ?)";
    // a member who is not the Chair: refused by policy; and by the trigger even for the owner role
    assertThat(as(member, "member", c -> failure(c, insert, t, member))).isNotNull();
    assertThat(failure(owner, insert, t, member)).isEqualTo("23000");
    assertThat(as(chair, "member", c -> failure(c, insert, t, chair))).isNull();

    exec(owner, insert, t, chair);
    UUID b = uuid(one(owner, "SELECT id FROM baselines WHERE tender_id = ?", t));
    // locked fields
    assertThat(failure(owner, "UPDATE baselines SET requirement_count = 5 WHERE id = ?", b))
        .isEqualTo("23000");
    // reopening needs the Chair and a reason of at least ten characters
    assertThat(
            failure(
                owner,
                "UPDATE baselines SET status = 'reopened', reopened_by = ?, reopened_at = now(), reopen_reason = 'short' WHERE id = ?",
                chair,
                b))
        .isEqualTo("23000");
    assertThat(
            failure(
                owner,
                "UPDATE baselines SET status = 'reopened', reopened_by = ?, reopened_at = now(),"
                    + " reopen_reason = 'A scope change from the sponsor' WHERE id = ?",
                member,
                b))
        .isEqualTo("23000");
    assertThat(
            failure(
                owner,
                "UPDATE baselines SET status = 'reopened', reopened_by = ?, reopened_at = now(),"
                    + " reopen_reason = 'A scope change from the sponsor' WHERE id = ?",
                chair,
                b))
        .isNull();
    // only one live baseline per tender: v2 waits until v1 is superseded
    String v2 =
        "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by) VALUES (?, 2, 0, ?)";
    assertThat(failure(owner, v2, t, chair)).isEqualTo("23505");
    assertThat(failure(owner, "UPDATE baselines SET status = 'superseded' WHERE id = ?", b))
        .isNull();
    assertThat(failure(owner, v2, t, chair)).isNull();
    // a superseded baseline cannot be confirmed again
    assertThat(failure(owner, "UPDATE baselines SET status = 'confirmed' WHERE id = ?", b))
        .isEqualTo("23514");
  }

  @Test
  void baselineReadsFollowTheCommitteeAndTheSnapshotIsImmutable() {
    UUID t = tender();
    exec(
        owner,
        "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
        t,
        chair);
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", t, member);
    UUID r = requirement(t, "manual", "reviewed");
    UUID b =
        uuid(
            one(
                owner,
                "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by)"
                    + " VALUES (?, 1, 1, ?) RETURNING id",
                t,
                chair));
    exec(
        owner,
        "INSERT INTO baseline_requirements (baseline_id, requirement_id, display_no, text, importance, content_hash)"
            + " VALUES (?, ?, 'REQ-001', 'text', 'mandatory', repeat('a', 64))",
        b,
        r);
    String count = "SELECT count(*) FROM baseline_requirements WHERE baseline_id = ?";
    assertThat(as(member, "member", c -> one(c, count, b))).isEqualTo(1L);
    assertThat(as(outsider, "member", c -> one(c, count, b))).isEqualTo(0L);
    assertThat(as(observer, "observer", c -> one(c, count, b))).isEqualTo(0L);
    assertThat(
            failure(
                owner,
                "UPDATE baseline_requirements SET text = 'changed' WHERE baseline_id = ?",
                b))
        .isEqualTo("23000");
    assertThat(failure(owner, "DELETE FROM baseline_requirements WHERE baseline_id = ?", b))
        .isEqualTo("23000");
  }

  // ---- scoring ---------------------------------------------------------------------------------

  @Test
  void theChairSetsTheModelAndAConfirmedCopyIsLocked() {
    UUID t = tender();
    exec(
        owner,
        "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
        t,
        chair);
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", t, member);
    String insert = "INSERT INTO scoring_models (tender_id) VALUES (?)";
    assertThat(as(member, "member", c -> failure(c, insert, t))).isNotNull();
    assertThat(as(chair, "member", c -> failure(c, insert, t))).isNull();
    exec(owner, insert, t);
    assertThat(failure(owner, insert, t)).isEqualTo("23505"); // one editable model per tender
    UUID model = uuid(one(owner, "SELECT id FROM scoring_models WHERE tender_id = ?", t));
    // points must not rise as the grade falls
    assertThat(failure(owner, "UPDATE scoring_models SET points_partially = 2 WHERE id = ?", model))
        .isEqualTo("23514");
    exec(
        owner,
        "INSERT INTO scoring_category_weights (scoring_model_id, category_id, weight) VALUES (?, ?, 2)",
        model,
        category);
    assertThat(
            failure(
                owner,
                "UPDATE scoring_category_weights SET weight = 0 WHERE scoring_model_id = ?",
                model))
        .isEqualTo("23514");

    UUID b =
        uuid(
            one(
                owner,
                "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by)"
                    + " VALUES (?, 1, 0, ?) RETURNING id",
                t,
                chair));
    UUID locked =
        uuid(
            one(
                owner,
                "INSERT INTO scoring_models (tender_id, baseline_id) VALUES (?, ?) RETURNING id",
                t,
                b));
    exec(
        owner,
        "INSERT INTO scoring_category_weights (scoring_model_id, category_id, weight) VALUES (?, ?, 1)",
        locked,
        category);
    assertThat(failure(owner, "UPDATE scoring_models SET points_fully = 3 WHERE id = ?", locked))
        .isEqualTo("23000");
    assertThat(
            failure(
                owner,
                "UPDATE scoring_category_weights SET weight = 5 WHERE scoring_model_id = ?",
                locked))
        .isEqualTo("23000");
    assertThat(
            failure(
                owner, "DELETE FROM scoring_category_weights WHERE scoring_model_id = ?", locked))
        .isEqualTo("23000");
  }

  // ---- the AI-ingest connection ----------------------------------------------------------------

  @Test
  void theIngestRoleStoresExtractedRequirementsAndNothingElse() {
    UUID job =
        uuid(
            one(
                owner,
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id) VALUES (?, 'requirement_extraction', 'tender', ?) RETURNING id",
                tender,
                tender));
    UUID run =
        uuid(
            one(
                ingest,
                "INSERT INTO ai_runs (job_id, run_type, model_name, prompt_version) VALUES (?, 'extraction', 'm', 'extraction-1.0.0') RETURNING id",
                job));
    UUID item = UUID.randomUUID();
    String insert =
        "INSERT INTO requirements (tender_id, text, importance, origin, source_item_id, extraction_ai_run_id)"
            + " VALUES (?, 'The supplier shall log access.', 'mandatory', 'ai', ?, ?)";
    assertThat(failure(ingest, insert, tender, item, run)).isNull();
    // redelivery: the producer's item id makes the second insert a conflict, not a second row
    assertThat(failure(ingest, insert, tender, item, run)).isEqualTo("23505");
    // it cannot insert a manual row, edit a row, or touch baselines
    assertThat(
            failure(
                ingest,
                "INSERT INTO requirements (tender_id, text, importance, origin) VALUES (?, 'x', 'optional', 'manual')",
                tender))
        .isNotNull();
    assertThat(
            failure(
                ingest, "UPDATE requirements SET text = 'rewritten' WHERE tender_id = ?", tender))
        .isNotNull();
    assertThat(
            failure(
                ingest,
                "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by) VALUES (?, 1, 0, ?)",
                tender,
                chair))
        .isNotNull();
    // a finished run is immutable
    exec(ingest, "UPDATE ai_runs SET finished_at = now(), outcome = 'succeeded' WHERE id = ?", run);
    assertThat(failure(owner, "UPDATE ai_runs SET warning_count = 3 WHERE id = ?", run))
        .isEqualTo("23000");
  }
}
