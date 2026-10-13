package app.tendereval.backend.db;

import static app.tendereval.backend.db.Sql.exec;
import static app.tendereval.backend.db.Sql.failure;
import static app.tendereval.backend.db.Sql.one;
import static app.tendereval.backend.db.Sql.rows;
import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.SchemaFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** P1-1: the schema enforces the data model's own rules (DM Integrity rules, status changes). */
@Tag("integration")
class SchemaIT {

  static Connection owner;
  static Connection app;

  static UUID admin;
  static UUID member;
  static UUID member2;
  static UUID tender;

  @BeforeAll
  static void setUp() {
    SchemaFixture.ensureStarted();
    owner = SchemaFixture.owner();
    app = SchemaFixture.app();
    admin = user("admin");
    member = user("member");
    member2 = user("member");
    tender =
        uuid(
            one(
                owner,
                "INSERT INTO tenders (name, created_by) VALUES ('Schema tender', ?) RETURNING id",
                admin));
  }

  @AfterAll
  static void tearDown() throws Exception {
    owner.close();
    app.close();
  }

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
            "User " + n.substring(0, 6),
            role,
            n));
  }

  // ---- status transitions ---------------------------------------------------------------

  private static final Pattern ROW =
      Pattern.compile("^\\|\\s*([a-z_]+)\\.([a-z_]+)\\s*\\|([^|]*)\\|([^|]*)\\|");

  /**
   * Moves listed in the data model's "Allowed status changes" table, for the tables built so far.
   */
  static Set<String> documentedTransitions() throws Exception {
    Set<String> tables =
        Set.of(
            "tenders",
            "document_versions",
            "proposal_versions",
            "jobs",
            "requirements",
            "baselines");
    Map<String, Set<String>> statuses = new LinkedHashMap<>();
    Set<String> moves = new LinkedHashSet<>();
    List<String> lines =
        Files.readAllLines(Path.of("..", "docs", "design", "03-data-model-design.md"));
    boolean inTable = false;
    for (String line : lines) {
      if (line.startsWith("### Allowed status changes")) {
        inTable = true;
        continue;
      }
      if (inTable && line.startsWith("## ")) {
        break;
      }
      if (!inTable) {
        continue;
      }
      Matcher m = ROW.matcher(line);
      if (!m.find() || !tables.contains(m.group(1))) {
        continue;
      }
      String key = m.group(1) + "." + m.group(2);
      for (String from : split(m.group(3))) {
        for (String to : split(m.group(4))) {
          if (from.equals("any other value")) {
            statuses.computeIfAbsent(key, k -> new LinkedHashSet<>()).add("*" + to);
          } else {
            moves.add(m.group(1) + "|" + from + "|" + to);
          }
        }
      }
    }
    // "any other value -> superseded" for proposal versions: every value except superseded.
    Set<String> proposal =
        Set.of(
            "uploaded",
            "in_screening",
            "accepted",
            "rejected",
            "resubmission_requested",
            "evaluating",
            "evaluated");
    for (String from : proposal) {
      moves.add("proposal_versions|" + from + "|superseded");
    }
    return moves;
  }

  private static List<String> split(String cell) {
    List<String> out = new ArrayList<>();
    for (String s : cell.split(",")) {
      out.add(s.trim());
    }
    return out;
  }

  @Test
  void statusTransitionTableMatchesTheDataModelDocument() throws Exception {
    Set<String> documented = documentedTransitions();
    assertThat(documented).hasSizeGreaterThan(20);

    Set<String> stored = new LinkedHashSet<>();
    for (List<Object> r :
        rows(
            owner,
            "SELECT table_name, from_value, to_value FROM status_transitions WHERE from_value IS NOT NULL")) {
      stored.add(r.get(0) + "|" + r.get(1) + "|" + r.get(2));
    }
    Set<String> extra = new LinkedHashSet<>(stored);
    extra.removeAll(documented);
    assertThat(extra).isEmpty();
    Set<String> missing = new LinkedHashSet<>(documented);
    missing.removeAll(stored);
    assertThat(missing).isEmpty();
  }

  private static final Map<String, List<String>> STATES =
      Map.of(
          "tenders", List.of("draft", "requirements", "evaluation", "consolidation", "finalized"),
          "document_versions", List.of("uploading", "processing", "ready", "failed"),
          "proposal_versions",
              List.of(
                  "uploaded",
                  "in_screening",
                  "accepted",
                  "rejected",
                  "resubmission_requested",
                  "evaluating",
                  "evaluated",
                  "superseded"),
          "jobs",
              List.of(
                  "queued",
                  "running",
                  "completed",
                  "completed_with_warnings",
                  "failed",
                  "cancelled"));

  @Test
  void everyAllowedMoveIsAcceptedAndEveryOtherMoveIsRefused() throws Exception {
    Set<String> allowed = new LinkedHashSet<>();
    for (List<Object> r :
        rows(
            owner,
            "SELECT table_name, from_value, to_value FROM status_transitions WHERE from_value IS NOT NULL")) {
      allowed.add(r.get(0) + "|" + r.get(1) + "|" + r.get(2));
    }
    int checked = 0;
    for (var e : STATES.entrySet()) {
      String table = e.getKey();
      for (String from : e.getValue()) {
        for (String to : e.getValue()) {
          if (from.equals(to)) {
            continue;
          }
          boolean ok = allowed.contains(table + "|" + from + "|" + to);
          String state = moveOnce(table, from, to);
          if (ok) {
            assertThat(state).as("%s %s -> %s", table, from, to).isNull();
          } else {
            assertThat(state).as("%s %s -> %s must be refused", table, from, to).isEqualTo("23514");
          }
          checked++;
        }
      }
    }
    // 5*4 + 4*3 + 8*7 + 6*5
    assertThat(checked).isEqualTo(20 + 12 + 56 + 30);
  }

  /** Places a row in `from` (trigger bypassed, as an owner arranging data) and tries the move. */
  private String moveOnce(String table, String from, String to) {
    UUID id = insertInState(table, from);
    return failure(owner, setStatusSql(table, to), id);
  }

  private String setStatusSql(String table, String to) {
    return switch (table) {
      case "tenders" -> "UPDATE tenders SET status = '" + to + "' WHERE id = ?";
      // Leaving "failed" clears the reason in the same statement (a retry does the same).
      case "document_versions" ->
          "UPDATE document_versions SET status = '"
              + to
              + "'"
              + (to.equals("failed") ? "" : ", failure_reason = NULL")
              + " WHERE id = ?";
      case "proposal_versions" ->
          "UPDATE proposal_versions SET status = '"
              + to
              + "'"
              + (to.equals("superseded") ? ", superseded_at = now()" : "")
              + " WHERE id = ?";
      case "jobs" ->
          "UPDATE jobs SET status = '"
              + to
              + "'"
              + (to.equals("failed") ? ", failure_code = 'other'" : "")
              + (to.equals("cancelled") ? ", cancelled_by = '" + admin + "'" : "")
              + " WHERE id = ?";
      default -> throw new IllegalArgumentException(table);
    };
  }

  private UUID insertInState(String table, String state) {
    exec(owner, "ALTER TABLE " + table + " DISABLE TRIGGER " + table + "_status");
    try {
      return switch (table) {
        case "tenders" ->
            uuid(
                one(
                    owner,
                    "INSERT INTO tenders (name, status, created_by) VALUES (?, ?, ?) RETURNING id",
                    "T " + UUID.randomUUID(),
                    state,
                    admin));
        case "document_versions" -> {
          UUID doc =
              uuid(
                  one(
                      owner,
                      "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'other', 'd') RETURNING id",
                      tender));
          UUID id = UUID.randomUUID();
          yield uuid(
              one(
                  owner,
                  "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format, size_bytes,"
                      + " sha256, storage_key, status, failure_reason, uploaded_by)"
                      + " VALUES (?, ?, 1, 'f.pdf', 'pdf', 10, repeat('a', 64), ?, ?, ?, ?) RETURNING id",
                  id,
                  doc,
                  "originals/%s/%s/%s.pdf".formatted(tender, doc, id),
                  state,
                  state.equals("failed") ? "other" : null,
                  admin));
        }
        case "proposal_versions" -> {
          UUID vendor =
              uuid(
                  one(
                      owner,
                      "INSERT INTO vendors (name, normalized_name) VALUES (?, ?) RETURNING id",
                      "V " + UUID.randomUUID(),
                      "v"));
          UUID tv =
              uuid(
                  one(
                      owner,
                      "INSERT INTO tender_vendors (tender_id, vendor_id, added_by) VALUES (?, ?, ?) RETURNING id",
                      tender,
                      vendor,
                      admin));
          yield uuid(
              one(
                  owner,
                  "INSERT INTO proposal_versions (tender_vendor_id, version_no, status, uploaded_by, superseded_at)"
                      + " VALUES (?, 1, ?, ?, "
                      + (state.equals("superseded") ? "now()" : "NULL")
                      + ") RETURNING id",
                  tv,
                  state,
                  admin));
        }
        case "jobs" ->
            uuid(
                one(
                    owner,
                    "INSERT INTO jobs (tender_id, job_type, target_type, target_id, status, failure_code, cancelled_by)"
                        + " VALUES (?, 'evaluation', 'tender', ?, ?, ?, ?) RETURNING id",
                    tender,
                    UUID.randomUUID(),
                    state,
                    state.equals("failed") ? "other" : null,
                    state.equals("cancelled") ? admin : null));
        default -> throw new IllegalArgumentException(table);
      };
    } finally {
      exec(owner, "ALTER TABLE " + table + " ENABLE TRIGGER " + table + "_status");
    }
  }

  @Test
  void newRowsMayOnlyStartInTheirInitialState() {
    assertThat(
            failure(
                owner,
                "INSERT INTO tenders (name, status, created_by) VALUES ('Started late', 'evaluation', ?)",
                admin))
        .isEqualTo("23514");
    assertThat(
            failure(
                owner,
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id, status) VALUES (?, 'evaluation', 'tender', ?, 'running')",
                tender,
                UUID.randomUUID()))
        .isEqualTo("23514");
  }

  // ---- integrity rules ------------------------------------------------------------------

  @Test
  void oneActiveChairPerTenderAndTheChairIsAnActiveMember() {
    UUID t =
        uuid(
            one(
                owner,
                "INSERT INTO tenders (name, created_by) VALUES (?, ?) RETURNING id",
                "Chairs " + UUID.randomUUID(),
                admin));
    exec(
        owner,
        "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
        t,
        member);
    // a second active Chair
    assertThat(
            failure(
                owner,
                "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
                t,
                member2))
        .isEqualTo("23505");
    // a removed member cannot be the Chair
    assertThat(
            failure(
                owner,
                "INSERT INTO tender_members (tender_id, user_id, is_chair, status, removed_at) VALUES (?, ?, true, 'removed', now())",
                t,
                member2))
        .isEqualTo("23514");
    // the same person twice as an active member
    assertThat(
            failure(
                owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", t, member))
        .isEqualTo("23505");
  }

  @Test
  void anAdministratorCannotJoinACommittee() {
    UUID t =
        uuid(
            one(
                owner,
                "INSERT INTO tenders (name, created_by) VALUES (?, ?) RETURNING id",
                "Admin member " + UUID.randomUUID(),
                admin));
    assertThat(
            failure(
                owner, "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", t, admin))
        .isEqualTo("23514");
  }

  @Test
  void tenderNamesAreUniqueIgnoringCaseUntilSoftDeleted() {
    String name = "Unique " + UUID.randomUUID();
    UUID first =
        uuid(
            one(
                owner,
                "INSERT INTO tenders (name, created_by) VALUES (?, ?) RETURNING id",
                name,
                admin));
    assertThat(
            failure(
                owner,
                "INSERT INTO tenders (name, created_by) VALUES (?, ?)",
                name.toUpperCase(),
                admin))
        .isEqualTo("23505");
    exec(owner, "UPDATE tenders SET deleted_at = now(), deleted_by = ? WHERE id = ?", admin, first);
    assertThat(failure(owner, "INSERT INTO tenders (name, created_by) VALUES (?, ?)", name, admin))
        .isNull();
  }

  @Test
  void oneActiveRfpPerTenderAndOnlyAddendaHaveAParent() {
    UUID t =
        uuid(
            one(
                owner,
                "INSERT INTO tenders (name, created_by) VALUES (?, ?) RETURNING id",
                "Rfp " + UUID.randomUUID(),
                admin));
    UUID rfp =
        uuid(
            one(
                owner,
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'rfp', 'RFP') RETURNING id",
                t));
    assertThat(
            failure(
                owner,
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'rfp', 'RFP 2')",
                t))
        .isEqualTo("23505");
    assertThat(
            failure(
                owner,
                "INSERT INTO documents (tender_id, doc_type, title, parent_document_id) VALUES (?, 'addendum', 'A1', ?)",
                t,
                rfp))
        .isNull();
    assertThat(
            failure(
                owner,
                "INSERT INTO documents (tender_id, doc_type, title, parent_document_id) VALUES (?, 'brd', 'B', ?)",
                t,
                rfp))
        .isEqualTo("23514");
  }

  @Test
  void oneOpenProcessingJobPerDocumentVersion() {
    UUID target = UUID.randomUUID();
    exec(
        owner,
        "INSERT INTO jobs (tender_id, job_type, target_type, target_id) VALUES (?, 'document_processing', 'document_version', ?)",
        tender,
        target);
    assertThat(
            failure(
                owner,
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id) VALUES (?, 'document_processing', 'document_version', ?)",
                tender,
                target))
        .isEqualTo("23505");
    // a finished job does not block a retry
    exec(owner, "UPDATE jobs SET status = 'running' WHERE target_id = ?", target);
    exec(
        owner,
        "UPDATE jobs SET status = 'failed', failure_code = 'other' WHERE target_id = ?",
        target);
    assertThat(
            failure(
                owner,
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id) VALUES (?, 'document_processing', 'document_version', ?)",
                tender,
                target))
        .isNull();
  }

  @Test
  void aVendorHasOneProposalVersionThatIsNotSuperseded() {
    UUID vendor =
        uuid(
            one(
                owner,
                "INSERT INTO vendors (name, normalized_name) VALUES ('Acme', 'acme') RETURNING id"));
    UUID tv =
        uuid(
            one(
                owner,
                "INSERT INTO tender_vendors (tender_id, vendor_id, added_by) VALUES (?, ?, ?) RETURNING id",
                tender,
                vendor,
                admin));
    exec(
        owner,
        "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by) VALUES (?, 1, ?)",
        tv,
        admin);
    assertThat(
            failure(
                owner,
                "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by) VALUES (?, 2, ?)",
                tv,
                admin))
        .isEqualTo("23505");
  }

  @Test
  void passagesPagesAndDeclarationsAreImmutable() {
    UUID doc =
        uuid(
            one(
                owner,
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'other', 'Immutable') RETURNING id",
                tender));
    UUID dv = UUID.randomUUID();
    exec(
        owner,
        "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format, size_bytes, sha256, storage_key, uploaded_by)"
            + " VALUES (?, ?, 1, 'f.pdf', 'pdf', 10, repeat('b', 64), ?, ?)",
        dv,
        doc,
        "originals/%s/%s/%s.pdf".formatted(tender, doc, dv),
        admin);
    exec(
        owner,
        "INSERT INTO passages (id, document_version_id, page_no, passage_no, text, bbox, char_start, char_end, content_hash)"
            + " VALUES (?, ?, 1, 1, 'text', '[]'::jsonb, 0, 4, repeat('c', 64))",
        UUID.randomUUID(),
        dv);
    assertThat(
            failure(
                owner, "UPDATE passages SET text = 'changed' WHERE document_version_id = ?", dv))
        .isEqualTo("23000");
    assertThat(failure(owner, "DELETE FROM passages WHERE document_version_id = ?", dv))
        .isEqualTo("23000");
  }

  // ---- audit trail ------------------------------------------------------------------------

  @Test
  void auditEventsAreAppendOnlyForTheApplicationRole() {
    exec(
        app,
        "SELECT set_config('app.user_id', ?, false), set_config('app.role', 'admin', false)",
        admin.toString());
    exec(
        app,
        "INSERT INTO audit_events (source, actor_user_id, actor_role, object_type, action) VALUES ('person', ?, 'admin', 'tender', 'tender_created')",
        admin);
    assertThat(failure(app, "UPDATE audit_events SET note = 'x'")).isEqualTo("42501");
    assertThat(failure(app, "DELETE FROM audit_events")).isEqualTo("42501");
    assertThat(failure(app, "TRUNCATE audit_events")).isEqualTo("42501");
    // The partition addressed by name is closed too.
    String part = (String) one(owner, "SELECT tableoid::regclass::text FROM audit_events LIMIT 1");
    assertThat(failure(app, "UPDATE " + part + " SET note = 'x'")).isEqualTo("42501");
    assertThat(failure(app, "DELETE FROM " + part)).isEqualTo("42501");
    assertThat(failure(app, "UPDATE document_access_events SET watermark_shown = true"))
        .isEqualTo("42501");
  }

  @Test
  void auditActionsThatNeedANoteRefuseWithoutOne() {
    exec(
        app,
        "SELECT set_config('app.user_id', ?, false), set_config('app.role', 'member', false)",
        member.toString());
    String base =
        "INSERT INTO audit_events (source, actor_user_id, actor_role, object_type, action, note) VALUES ('person', ?, 'member', 'requirement', ?, ?)";
    assertThat(failure(app, base, member, "requirement_edited", null)).isEqualTo("23514");
    assertThat(failure(app, base, member, "requirement_edited", "short")).isEqualTo("23514");
    assertThat(failure(app, base, member, "requirement_edited", "a justified change")).isNull();
    assertThat(failure(app, base, member, "requirement_created", null)).isNull();
    assertThat(
            failure(
                app,
                "INSERT INTO audit_events (source, actor_user_id, actor_role, object_type, action, after) VALUES ('person', ?, 'chair', 'screening', 'screening_decided', '{\"decision\":\"reject\"}'::jsonb)",
                member))
        .isEqualTo("23514");
  }

  @Test
  void aSystemEventHasNoActorAndAPersonEventHasOne() {
    assertThat(
            failure(
                owner,
                "INSERT INTO audit_events (source, object_type, action) VALUES ('person', 'tender', 'tender_created')"))
        .isEqualTo("23514");
    assertThat(
            failure(
                owner,
                "INSERT INTO audit_events (source, object_type, action) VALUES ('system', 'job', 'job_status_changed')"))
        .isNull();
    assertThat(
            failure(
                owner,
                "INSERT INTO audit_events (source, actor_user_id, object_type, action) VALUES ('ai', ?, 'job', 'ai_run_completed')",
                admin))
        .isEqualTo("23514");
  }

  @Test
  void monthlyPartitionsAreCreatedAheadAndTheFunctionIsRepeatable() {
    Object parts =
        one(
            owner,
            "SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhparent WHERE c.relname = 'audit_events'");
    assertThat(((Number) parts).intValue()).isEqualTo(26); // 25 months + default
    assertThat(((Number) one(app, "SELECT ensure_monthly_partitions(24)")).intValue()).isZero();
    assertThat(((Number) one(app, "SELECT ensure_monthly_partitions(26)")).intValue()).isEqualTo(4);
  }

  @Test
  void settingsAreSeeded() {
    assertThat(one(owner, "SELECT value FROM settings WHERE key = 'note.min_length'"))
        .isEqualTo("10");
    assertThat(one(owner, "SELECT value FROM settings WHERE key = 'upload.max_bytes'"))
        .isEqualTo("104857600");
  }

  @Test
  void rowVersionRisesOnEveryUpdate() {
    UUID t =
        uuid(
            one(
                owner,
                "INSERT INTO tenders (name, created_by) VALUES (?, ?) RETURNING id",
                "Versions " + UUID.randomUUID(),
                admin));
    assertThat(one(owner, "SELECT row_version FROM tenders WHERE id = ?", t)).isEqualTo(1);
    exec(owner, "UPDATE tenders SET description = 'a' WHERE id = ?", t);
    exec(owner, "UPDATE tenders SET description = 'b' WHERE id = ?", t);
    assertThat(one(owner, "SELECT row_version FROM tenders WHERE id = ?", t)).isEqualTo(3);
  }
}
