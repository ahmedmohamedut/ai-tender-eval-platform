package app.tendereval.backend.db;

import static app.tendereval.backend.db.Sql.exec;
import static app.tendereval.backend.db.Sql.failure;
import static app.tendereval.backend.db.Sql.one;
import static app.tendereval.backend.db.Sql.rows;
import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.SchemaFixture;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P1-2: one positive and one negative test for every read and write rule of DM "Independence and
 * access" that the tables built so far can express. Requests run as the application role with
 * app.user_id and app.role set for the transaction, exactly as the backend does.
 */
@Tag("integration")
class RlsIT {

  static Connection owner;
  static Connection app;

  static UUID admin, observer, chair, m1, m3, m2, outsider;
  static UUID t1, t2;
  static UUID dvRfp, dvProposal, dvOther;
  static UUID passageRfp, passageProposal, passageOther;
  static UUID tv1, vendor;
  static UUID job1, job2;

  @BeforeAll
  static void world() {
    SchemaFixture.ensureStarted();
    owner = SchemaFixture.owner();
    app = SchemaFixture.app();

    admin = user("admin");
    observer = user("observer");
    chair = user("member");
    m1 = user("member");
    m3 = user("member");
    m2 = user("member");
    outsider = user("member");

    t1 = tender("Tender one");
    t2 = tender("Tender two");
    exec(
        owner,
        "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
        t1,
        chair);
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", t1, m1);
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", t1, m3);
    exec(
        owner,
        "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
        t2,
        m2);

    UUID rfp = document(t1, "rfp", "RFP");
    dvRfp = version(rfp);
    UUID proposal = document(t1, "proposal", "Vendor A proposal");
    dvProposal = version(proposal);
    UUID other = document(t2, "rfp", "Other RFP");
    dvOther = version(other);

    vendor =
        uuid(
            one(
                owner,
                "INSERT INTO vendors (name, normalized_name) VALUES ('Vendor A', 'vendor a') RETURNING id"));
    tv1 =
        uuid(
            one(
                owner,
                "INSERT INTO tender_vendors (tender_id, vendor_id, added_by) VALUES (?, ?, ?) RETURNING id",
                t1,
                vendor,
                admin));
    UUID pv =
        uuid(
            one(
                owner,
                "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by) VALUES (?, 1, ?) RETURNING id",
                tv1,
                admin));
    exec(
        owner,
        "INSERT INTO proposal_version_files (proposal_version_id, document_version_id, file_role) VALUES (?, ?, 'main')",
        pv,
        dvProposal);

    // m1 declared no conflict; m3 declared a conflict; the Chair has declared nothing.
    exec(
        owner,
        "INSERT INTO conflict_declarations (tender_vendor_id, user_id, has_conflict) VALUES (?, ?, false)",
        tv1,
        m1);
    exec(
        owner,
        "INSERT INTO conflict_declarations (tender_vendor_id, user_id, has_conflict, statement) VALUES (?, ?, true, 'I worked there')",
        tv1,
        m3);

    passageRfp = passage(dvRfp);
    passageProposal = passage(dvProposal);
    passageOther = passage(dvOther);
    for (UUID dv : List.of(dvRfp, dvProposal, dvOther)) {
      exec(
          owner,
          "INSERT INTO document_pages (document_version_id, page_no, width, height, render_key) VALUES (?, 1, 595, 842, 'k')",
          dv);
      exec(
          owner,
          "INSERT INTO document_sections (id, document_version_id, title, level, sort_order, start_page, end_page)"
              + " VALUES (?, ?, 'S', 1, 1, 1, 1)",
          UUID.randomUUID(),
          dv);
    }

    job1 =
        uuid(
            one(
                owner,
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id) VALUES (?, 'document_processing', 'document_version', ?) RETURNING id",
                t1,
                dvRfp));
    job2 =
        uuid(
            one(
                owner,
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id) VALUES (?, 'document_processing', 'document_version', ?) RETURNING id",
                t2,
                dvOther));
    exec(
        owner,
        "INSERT INTO job_steps (job_id, step_no, name) VALUES (?, 1, 'Reading document')",
        job1);

    exec(
        owner,
        "INSERT INTO audit_events (source, actor_user_id, actor_role, object_type, action, tender_id)"
            + " VALUES ('person', ?, 'admin', 'tender', 'tender_created', ?)",
        admin,
        t1);
    exec(
        owner,
        "INSERT INTO document_access_events (user_id, document_version_id) VALUES (?, ?)",
        m1,
        dvProposal);
    exec(
        owner,
        "INSERT INTO drafts (user_id, object_type, object_id, field, body) VALUES (?, 'requirement', ?, 'note', 'draft')",
        m1,
        UUID.randomUUID());
    exec(
        owner,
        "INSERT INTO idempotency_keys (user_id, idempotency_key, operation, request_hash) VALUES (?, 'key-0000001', 'x', 'h')",
        m1);
  }

  @AfterAll
  static void close() throws SQLException {
    owner.close();
    app.close();
  }

  // ---- helpers ------------------------------------------------------------------------------

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

  static UUID tender(String name) {
    return uuid(
        one(
            owner,
            "INSERT INTO tenders (name, created_by) VALUES (?, ?) RETURNING id",
            name + " " + UUID.randomUUID(),
            admin));
  }

  static UUID document(UUID tender, String type, String title) {
    return uuid(
        one(
            owner,
            "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, ?, ?) RETURNING id",
            tender,
            type,
            title));
  }

  static UUID version(UUID document) {
    UUID id = UUID.randomUUID();
    UUID tender = uuid(one(owner, "SELECT tender_id FROM documents WHERE id = ?", document));
    exec(
        owner,
        "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format, size_bytes, sha256, storage_key, status, uploaded_by)"
            + " VALUES (?, ?, 1, 'f.pdf', 'pdf', 10, repeat('a', 64), ?, 'uploading', ?)",
        id,
        document,
        "originals/%s/%s/%s.pdf".formatted(tender, document, id),
        admin);
    exec(owner, "UPDATE document_versions SET status = 'processing' WHERE id = ?", id);
    exec(owner, "UPDATE document_versions SET status = 'ready', page_count = 1 WHERE id = ?", id);
    return id;
  }

  static UUID passage(UUID dv) {
    UUID id = UUID.randomUUID();
    exec(
        owner,
        "INSERT INTO passages (id, document_version_id, page_no, passage_no, text, bbox, char_start, char_end, content_hash)"
            + " VALUES (?, ?, 1, 1, 'Requirement text', '[]'::jsonb, 0, 16, repeat('d', 64))",
        id,
        dv);
    return id;
  }

  /** Runs `fn` in a transaction as the application role with the identity set, then rolls back. */
  static <T> T as(UUID user, String role, Function<Connection, T> fn) {
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

  static long count(UUID user, String role, String sql, Object... args) {
    return as(
        user,
        role,
        c -> ((Number) one(c, "SELECT count(*) FROM (" + sql + ") q", args)).longValue());
  }

  static String fails(UUID user, String role, String sql, Object... args) {
    return as(user, role, c -> failure(c, sql, args));
  }

  static long updated(UUID user, String role, String sql, Object... args) {
    return as(
        user,
        role,
        c -> {
          try (var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
              ps.setObject(i + 1, args[i]);
            }
            return (long) ps.executeUpdate();
          } catch (SQLException e) {
            return -1L;
          }
        });
  }

  // ---- reading tenders and what hangs on them --------------------------------------------

  @Test
  void aMemberReadsOnlyTendersTheyAreAssignedTo() {
    assertThat(count(m1, "member", "SELECT id FROM tenders WHERE id = ?", t1)).isEqualTo(1);
    assertThat(count(m1, "member", "SELECT id FROM tenders WHERE id = ?", t2)).isZero();
    assertThat(count(m2, "member", "SELECT id FROM tenders WHERE id = ?", t1)).isZero();
    assertThat(count(outsider, "member", "SELECT id FROM tenders")).isZero();
  }

  @Test
  void theAdminReadsEveryTenderAndTheObserverReadsTheTenderList() {
    assertThat(count(admin, "admin", "SELECT id FROM tenders WHERE id IN (?, ?)", t1, t2))
        .isEqualTo(2);
    assertThat(count(observer, "observer", "SELECT id FROM tenders WHERE id IN (?, ?)", t1, t2))
        .isEqualTo(2);
  }

  @Test
  void theObserverReadsNothingThatHangsOnATenderExceptTheCommitteeAndVendorCounts() {
    assertThat(count(observer, "observer", "SELECT id FROM documents")).isZero();
    assertThat(count(observer, "observer", "SELECT id FROM document_versions")).isZero();
    assertThat(count(observer, "observer", "SELECT id FROM passages")).isZero();
    assertThat(count(observer, "observer", "SELECT id FROM jobs")).isZero();
    assertThat(count(observer, "observer", "SELECT id FROM vendors")).isZero();
    assertThat(count(observer, "observer", "SELECT id FROM tender_members WHERE tender_id = ?", t1))
        .isEqualTo(3);
    assertThat(count(observer, "observer", "SELECT id FROM tender_vendors WHERE tender_id = ?", t1))
        .isEqualTo(1);
  }

  @Test
  void documentsJobsAndStagesFollowTenderMembership() {
    assertThat(count(m1, "member", "SELECT id FROM documents WHERE tender_id = ?", t1))
        .isEqualTo(2);
    assertThat(count(m1, "member", "SELECT id FROM documents WHERE tender_id = ?", t2)).isZero();
    assertThat(count(m1, "member", "SELECT id FROM jobs WHERE id = ?", job1)).isEqualTo(1);
    assertThat(count(m1, "member", "SELECT id FROM jobs WHERE id = ?", job2)).isZero();
    assertThat(count(m1, "member", "SELECT id FROM job_steps WHERE job_id = ?", job1)).isEqualTo(1);
    assertThat(count(m2, "member", "SELECT id FROM job_steps WHERE job_id = ?", job1)).isZero();
    assertThat(count(admin, "admin", "SELECT id FROM jobs WHERE id IN (?, ?)", job1, job2))
        .isEqualTo(2);
  }

  @Test
  void removedMembersLoseAccess() {
    UUID t = tender("Removal");
    exec(owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", t, outsider);
    assertThat(count(outsider, "member", "SELECT id FROM tenders WHERE id = ?", t)).isEqualTo(1);
    exec(
        owner,
        "UPDATE tender_members SET status = 'removed', removed_at = now() WHERE tender_id = ? AND user_id = ?",
        t,
        outsider);
    assertThat(count(outsider, "member", "SELECT id FROM tenders WHERE id = ?", t)).isZero();
  }

  // ---- proposal files, pages and passages ------------------------------------------------

  @Test
  void aTenderDocumentIsReadByEveryActiveMemberOfThatTender() {
    for (UUID u : List.of(chair, m1, m3)) {
      assertThat(count(u, "member", "SELECT id FROM document_versions WHERE id = ?", dvRfp))
          .isEqualTo(1);
      assertThat(count(u, "member", "SELECT id FROM passages WHERE id = ?", passageRfp))
          .isEqualTo(1);
    }
    assertThat(count(m2, "member", "SELECT id FROM passages WHERE id = ?", passageRfp)).isZero();
  }

  @Test
  void aProposalIsReadOnlyAfterADeclarationOfNoConflict() {
    // m1 declared no conflict: reads the files, pages and passages.
    assertThat(count(m1, "member", "SELECT id FROM document_versions WHERE id = ?", dvProposal))
        .isEqualTo(1);
    assertThat(
            count(
                m1,
                "member",
                "SELECT id FROM document_pages WHERE document_version_id = ?",
                dvProposal))
        .isEqualTo(1);
    assertThat(count(m1, "member", "SELECT id FROM passages WHERE id = ?", passageProposal))
        .isEqualTo(1);
    // The Chair has not declared yet: nothing.
    assertThat(count(chair, "member", "SELECT id FROM document_versions WHERE id = ?", dvProposal))
        .isZero();
    assertThat(count(chair, "member", "SELECT id FROM passages WHERE id = ?", passageProposal))
        .isZero();
    // m3 declared a conflict: nothing.
    assertThat(count(m3, "member", "SELECT id FROM document_versions WHERE id = ?", dvProposal))
        .isZero();
    assertThat(count(m3, "member", "SELECT id FROM passages WHERE id = ?", passageProposal))
        .isZero();
    // The admin always reads.
    assertThat(count(admin, "admin", "SELECT id FROM passages WHERE id = ?", passageProposal))
        .isEqualTo(1);
  }

  @Test
  void aDeclarationOfAnotherTenderDoesNotOpenAProposal() {
    assertThat(count(m2, "member", "SELECT id FROM document_versions WHERE id = ?", dvProposal))
        .isZero();
  }

  // ---- writes ---------------------------------------------------------------------------------

  @Test
  void onlyTheAdminCreatesAndEditsTendersDocumentsAndVendors() {
    String newTender = "INSERT INTO tenders (name, created_by) VALUES (?, ?)";
    assertThat(fails(m1, "member", newTender, "By member " + UUID.randomUUID(), m1))
        .isEqualTo("42501");
    assertThat(fails(observer, "observer", newTender, "By observer " + UUID.randomUUID(), observer))
        .isEqualTo("42501");
    assertThat(fails(admin, "admin", newTender, "By admin " + UUID.randomUUID(), admin)).isNull();

    assertThat(updated(m1, "member", "UPDATE tenders SET description = 'x' WHERE id = ?", t1))
        .isZero();
    assertThat(updated(admin, "admin", "UPDATE tenders SET description = 'x' WHERE id = ?", t1))
        .isEqualTo(1);

    assertThat(
            fails(
                m1,
                "member",
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'other', 'x')",
                t1))
        .isEqualTo("42501");
    assertThat(
            fails(
                admin,
                "admin",
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'other', 'x')",
                t1))
        .isNull();
    assertThat(fails(m1, "member", "INSERT INTO vendors (name, normalized_name) VALUES ('V', 'v')"))
        .isEqualTo("42501");
  }

  @Test
  void committeeChangesAreTheAdminsAndAnAdminCannotBeAMember() {
    assertThat(
            fails(
                m1,
                "member",
                "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)",
                t1,
                outsider))
        .isEqualTo("42501");
    assertThat(
            fails(
                admin,
                "admin",
                "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)",
                t1,
                outsider))
        .isNull();
    assertThat(
            fails(
                admin,
                "admin",
                "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)",
                t1,
                admin))
        .isEqualTo("23514");
  }

  @Test
  void theRequestRoleNeverWritesPagesSectionsOrPassages() {
    for (UUID u : List.of(admin, m1)) {
      String role = u.equals(admin) ? "admin" : "member";
      assertThat(
              fails(
                  u,
                  role,
                  "INSERT INTO passages (id, document_version_id, page_no, passage_no, text, bbox, char_start, char_end, content_hash)"
                      + " VALUES (gen_random_uuid(), ?, 2, 1, 't', '[]'::jsonb, 0, 1, repeat('e', 64))",
                  dvRfp))
          .isEqualTo("42501");
      assertThat(
              fails(
                  u,
                  role,
                  "INSERT INTO document_pages (document_version_id, page_no, width, height, render_key) VALUES (?, 9, 1, 1, 'k')",
                  dvRfp))
          .isEqualTo("42501");
    }
  }

  @Test
  void aDeclarationIsWrittenByItsOwnerForTheirOwnTenderAndNeverChanged() {
    String insert =
        "INSERT INTO conflict_declarations (tender_vendor_id, user_id, has_conflict) VALUES (?, ?, false)";
    // the Chair declares for themselves
    assertThat(fails(chair, "member", insert, tv1, chair)).isNull();
    // nobody declares for someone else
    assertThat(fails(chair, "member", insert, tv1, m2)).isEqualTo("42501");
    // a member of another tender cannot declare for this vendor
    assertThat(fails(m2, "member", insert, tv1, m2)).isEqualTo("42501");
    // an existing declaration cannot be changed or removed (trigger), even by the owner
    assertThat(
            updated(
                m1,
                "member",
                "UPDATE conflict_declarations SET has_conflict = true WHERE user_id = ?",
                m1))
        .isZero();
    assertThat(
            failure(
                owner,
                "UPDATE conflict_declarations SET has_conflict = true WHERE user_id = ?",
                m1))
        .isEqualTo("23000");
    assertThat(fails(m1, "member", "DELETE FROM conflict_declarations WHERE user_id = ?", m1))
        .isIn("42501", "23000");
  }

  @Test
  void declarationsAreReadByTheirOwnerTheAdminAndTheChairOnly() {
    assertThat(count(m1, "member", "SELECT id FROM conflict_declarations")).isEqualTo(1);
    assertThat(count(m3, "member", "SELECT id FROM conflict_declarations")).isEqualTo(1);
    assertThat(count(chair, "member", "SELECT id FROM conflict_declarations")).isEqualTo(2);
    assertThat(count(admin, "admin", "SELECT id FROM conflict_declarations")).isEqualTo(2);
    assertThat(count(m2, "member", "SELECT id FROM conflict_declarations")).isZero();
  }

  // ---- drafts, idempotency, logs ----------------------------------------------------------------

  @Test
  void aDraftIsReadAndWrittenByItsAuthorOnly() {
    assertThat(count(m1, "member", "SELECT id FROM drafts")).isEqualTo(1);
    assertThat(count(m3, "member", "SELECT id FROM drafts")).isZero();
    assertThat(count(admin, "admin", "SELECT id FROM drafts")).isZero();
    assertThat(
            fails(
                m3,
                "member",
                "INSERT INTO drafts (user_id, object_type, object_id, field, body) VALUES (?, 'x', gen_random_uuid(), 'f', 'b')",
                m1))
        .isEqualTo("42501");
  }

  @Test
  void idempotencyKeysBelongToTheirUser() {
    assertThat(count(m1, "member", "SELECT id FROM idempotency_keys")).isEqualTo(1);
    assertThat(count(m3, "member", "SELECT id FROM idempotency_keys")).isZero();
  }

  @Test
  void theAuditTrailIsReadByTheAdminAndTheObserverOnly() {
    assertThat(count(admin, "admin", "SELECT id FROM audit_events")).isGreaterThanOrEqualTo(1);
    assertThat(count(observer, "observer", "SELECT id FROM audit_events"))
        .isGreaterThanOrEqualTo(1);
    assertThat(count(m1, "member", "SELECT id FROM audit_events")).isZero();
    assertThat(count(chair, "member", "SELECT id FROM audit_events")).isZero();
    assertThat(count(admin, "admin", "SELECT id FROM document_access_events"))
        .isGreaterThanOrEqualTo(1);
    assertThat(count(m1, "member", "SELECT id FROM document_access_events")).isZero();
  }

  @Test
  void anAuditEventNamesTheCallerOrTheSystemAndCannotBeChanged() {
    String insert =
        "INSERT INTO audit_events (source, actor_user_id, actor_role, object_type, action) VALUES (?, ?, 'member', 'tender', 'tender_updated')";
    assertThat(fails(m1, "member", insert, "person", m1)).isNull();
    assertThat(fails(m1, "member", insert, "person", m3)).isEqualTo("42501"); // someone else's name
    assertThat(fails(m1, "member", "UPDATE audit_events SET note = 'x'")).isEqualTo("42501");
    assertThat(fails(m1, "member", "DELETE FROM audit_events")).isEqualTo("42501");
  }

  @Test
  void aReaderOnlyOpensRecordsOfTheirOwnUser() {
    String insert =
        "INSERT INTO document_access_events (user_id, document_version_id) VALUES (?, ?)";
    assertThat(fails(m1, "member", insert, m1, dvProposal)).isNull();
    assertThat(fails(m1, "member", insert, m3, dvProposal)).isEqualTo("42501");
  }

  // ---- users
  // -------------------------------------------------------------------------------------

  @Test
  void usersAreVisibleToThemselvesTheirCommitteeMatesAndTheAdmin() {
    assertThat(count(m1, "member", "SELECT id FROM users WHERE id IN (?, ?)", m1, chair))
        .isEqualTo(2);
    assertThat(count(m1, "member", "SELECT id FROM users WHERE id = ?", m2)).isZero();
    assertThat(count(outsider, "member", "SELECT id FROM users")).isEqualTo(1);
    assertThat(count(admin, "admin", "SELECT id FROM users WHERE id IN (?, ?)", m1, m2))
        .isEqualTo(2);
  }

  @Test
  void aUserChangesTheirTimeZoneButNeverTheirOwnRoleOrStatus() {
    assertThat(
            updated(m1, "member", "UPDATE users SET time_zone = 'Europe/Berlin' WHERE id = ?", m1))
        .isEqualTo(1);
    assertThat(fails(m1, "member", "UPDATE users SET role = 'admin' WHERE id = ?", m1))
        .isEqualTo("42501");
    assertThat(fails(m1, "member", "UPDATE users SET status = 'deactivated' WHERE id = ?", m1))
        .isEqualTo("42501");
    assertThat(fails(m1, "member", "UPDATE users SET email = 'x@example.test' WHERE id = ?", m1))
        .isEqualTo("42501");
    assertThat(updated(m1, "member", "UPDATE users SET time_zone = 'UTC' WHERE id = ?", m3))
        .isZero();
    assertThat(updated(admin, "admin", "UPDATE users SET role = 'observer' WHERE id = ?", outsider))
        .isEqualTo(1);
  }

  @Test
  void firstSignInMayActivateAnInvitedUserAndNothingElse() {
    UUID invited =
        uuid(
            one(
                owner,
                "INSERT INTO users (email, display_name, role, status, sso_subject) VALUES (?, 'Invited', 'member', 'invited', ?) RETURNING id",
                UUID.randomUUID() + "@example.test",
                UUID.randomUUID().toString()));
    assertThat(
            updated(
                invited,
                "member",
                "UPDATE users SET status = 'active', last_sign_in_at = now() WHERE id = ?",
                invited))
        .isEqualTo(1);
  }

  // ---- failing closed
  // ---------------------------------------------------------------------------------

  @Test
  void withoutAnIdentityTheApplicationRoleSeesNothing() {
    for (String table :
        List.of(
            "users",
            "tenders",
            "tender_members",
            "documents",
            "document_versions",
            "passages",
            "vendors",
            "tender_vendors",
            "proposal_versions",
            "jobs",
            "job_steps",
            "audit_events",
            "conflict_declarations",
            "drafts",
            "document_access_events",
            "idempotency_keys",
            "settings")) {
      assertThat(count(null, null, "SELECT 1 FROM " + table)).as(table).isZero();
    }
  }

  @Test
  void anUnknownRoleValueGrantsNothing() {
    assertThat(count(m1, "superuser", "SELECT id FROM tenders")).isZero();
    assertThat(count(m1, "system", "SELECT id FROM documents")).isZero();
    assertThat(
            fails(m1, "ADMIN", "INSERT INTO tenders (name, created_by) VALUES ('Shouting', ?)", m1))
        .isEqualTo("42501");
  }

  @Test
  void theApplicationRoleCannotWeakenPoliciesOnTheRealTables() {
    for (String table :
        List.of(
            "tenders",
            "tender_members",
            "documents",
            "document_versions",
            "passages",
            "proposal_versions",
            "jobs",
            "audit_events",
            "users")) {
      assertThat(failure(app, "ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY"))
          .as(table)
          .isEqualTo("42501");
      assertThat(failure(app, "ALTER TABLE " + table + " NO FORCE ROW LEVEL SECURITY"))
          .as(table)
          .isEqualTo("42501");
    }
    assertThat(failure(app, "DROP POLICY tenders_read ON tenders")).isEqualTo("42501");
    assertThat(failure(app, "CREATE POLICY open ON tenders FOR SELECT USING (true)"))
        .isEqualTo("42501");
    assertThat(failure(app, "ALTER POLICY tenders_read ON tenders USING (true)"))
        .isEqualTo("42501");
    assertThat(failure(app, "SET ROLE tender_owner")).isEqualTo("42501");
    assertThat(failure(app, "ALTER ROLE tender_app BYPASSRLS")).isEqualTo("42501");
    assertThat(
            failure(
                app,
                "CREATE OR REPLACE FUNCTION app_role() RETURNS text LANGUAGE sql AS $$ SELECT 'admin' $$"))
        .isEqualTo("42501");
    assertThat(failure(app, "DROP TRIGGER tenders_status ON tenders")).isEqualTo("42501");
    assertThat(
            failure(
                app,
                "INSERT INTO status_transitions VALUES ('tenders','status','draft','finalized','x')"))
        .isEqualTo("42501");
  }

  @Test
  void rowSecurityOffMakesQueriesFailInsteadOfBypassingPolicies() {
    String state =
        as(
            m1,
            "member",
            c -> {
              exec(c, "SET LOCAL row_security = off");
              return failure(c, "SELECT * FROM tenders");
            });
    assertThat(state).isEqualTo("42501");
  }

  // ---- the AI-ingest connection
  // ---------------------------------------------------------------------

  @Test
  void theIngestRoleWritesResultsAndMovesStatusColumnsOnly() throws Exception {
    try (Connection ingest = SchemaFixture.ingest()) {
      UUID dv = dvRfp;
      assertThat(
              failure(
                  ingest,
                  "INSERT INTO passages (id, document_version_id, page_no, passage_no, text, bbox, char_start, char_end, content_hash)"
                      + " VALUES (gen_random_uuid(), ?, 3, 1, 'new', '[]'::jsonb, 0, 3, repeat('f', 64))",
                  dv))
          .isNull();
      assertThat(
              failure(
                  ingest,
                  "INSERT INTO document_pages (document_version_id, page_no, width, height, render_key) VALUES (?, 3, 1, 1, 'k')",
                  dv))
          .isNull();
      assertThat(
              failure(
                  ingest,
                  "UPDATE jobs SET status = 'running', started_at = now(), last_status_seq = 1 WHERE id = ?",
                  job1))
          .isNull();
      assertThat(
              failure(
                  ingest, "UPDATE document_versions SET failure_reason = NULL WHERE id = ?", dv))
          .isNull();
      // not allowed: what the job is about, who started it, the object key
      assertThat(failure(ingest, "UPDATE jobs SET tender_id = ? WHERE id = ?", t2, job1))
          .isEqualTo("42501");
      assertThat(failure(ingest, "UPDATE jobs SET started_by = NULL WHERE id = ?", job1))
          .isEqualTo("42501");
      assertThat(
              failure(
                  ingest,
                  "UPDATE document_versions SET storage_key = 'originals/x' WHERE id = ?",
                  dv))
          .isEqualTo("42501");
      // passages are immutable even for the role that writes them
      assertThat(failure(ingest, "UPDATE passages SET text = 'x' WHERE id = ?", passageRfp))
          .isEqualTo("42501");
      // nothing about people or committees
      assertThat(failure(ingest, "SELECT * FROM users")).isEqualTo("42501");
      assertThat(failure(ingest, "SELECT * FROM tender_members")).isEqualTo("42501");
      assertThat(failure(ingest, "SELECT * FROM conflict_declarations")).isEqualTo("42501");
      assertThat(failure(ingest, "SELECT * FROM drafts")).isEqualTo("42501");
      // audit: a system event yes, a person's name no
      assertThat(
              failure(
                  ingest,
                  "INSERT INTO audit_events (source, object_type, action) VALUES ('system', 'job', 'job_status_changed')"))
          .isNull();
      assertThat(
              failure(
                  ingest,
                  "INSERT INTO audit_events (source, actor_user_id, object_type, action) VALUES ('person', ?, 'job', 'job_status_changed')",
                  admin))
          .isEqualTo("42501");
    }
  }

  @Test
  void theIngestRoleCannotUseTheRequestPathPolicies() throws Exception {
    try (Connection ingest = SchemaFixture.ingest()) {
      // Even with an identity claimed, tender policies do not apply to this role.
      exec(ingest, "SELECT set_config('app.role', 'admin', false)");
      assertThat(failure(ingest, "SELECT * FROM tenders")).isEqualTo("42501");
      assertThat(failure(ingest, "SELECT * FROM vendors")).isEqualTo("42501");
    }
  }
}
