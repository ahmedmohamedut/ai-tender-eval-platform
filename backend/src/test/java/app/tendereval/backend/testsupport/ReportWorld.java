package app.tendereval.backend.testsupport;

import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.util.List;
import java.util.UUID;

/**
 * A tender in consolidation with one evaluated vendor whose three members have all submitted. On
 * the first requirement the Chair and one member grade fully and the other overrides to partially
 * (a disagreement); on the second everybody grades non compliant.
 */
public final class ReportWorld {

  private ReportWorld() {}

  public record World(
      TestUser admin,
      TestUser chair,
      TestUser m1,
      TestUser m2,
      TestUser observer,
      UUID tender,
      UUID tv,
      UUID r1,
      UUID r2) {}

  public static World create() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser m1 = TestUsers.create("member");
    TestUser m2 = TestUsers.create("member");
    TestUser observer = TestUsers.create("observer");
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
                "INSERT INTO vendors (name, normalized_name) VALUES ('Acme', ?) RETURNING id",
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
    for (TestUser u : List.of(chair, m1, m2)) {
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
    TestUsers.sql("UPDATE jobs SET status = 'running', started_at = now() WHERE id = ?", job);
    UUID dv = UUID.randomUUID();
    UUID doc =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'proposal', 'P') RETURNING id",
                tender);
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
    TestUsers.sql(
        "WITH g AS (INSERT INTO ai_grades (source_item_id, ai_run_id, proposal_version_id,"
            + " baseline_id, requirement_id, outcome, grade, rationale_summary, rationale,"
            + " confidence, requirement_hash) VALUES (gen_random_uuid(), ?, ?, ?, ?, 'graded',"
            + " 'fully_compliant', 'Stated', 'The proposal states it.', 0.9, repeat('c', 64))"
            + " RETURNING id) INSERT INTO grade_citations (ai_grade_id, citation_no,"
            + " document_version_id, passage_id, page_no, excerpt)"
            + " SELECT id, 1, ?, ?, 4, 'We encrypt all data at rest.' FROM g",
        run,
        pv,
        baseline,
        r1,
        dv,
        passage);
    TestUsers.sql(
        "INSERT INTO ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id,"
            + " requirement_id, outcome, grade, non_compliance_reason, requirement_hash)"
            + " VALUES (gen_random_uuid(), ?, ?, ?, ?, 'graded', 'non_compliant', 'not_addressed',"
            + " repeat('c', 64))",
        run,
        pv,
        baseline,
        r2);
    TestUsers.sql("UPDATE jobs SET status = 'completed', finished_at = now() WHERE id = ?", job);
    TestUsers.scalar("SELECT count(*) FROM ingest_sync_member_evaluations(?)", pv);
    TestUsers.sql("UPDATE proposal_versions SET status = 'evaluated' WHERE id = ?", pv);
    // r1: chair and m1 agree with the AI; m2 overrides. r2: everybody agrees (non compliant).
    for (TestUser u : List.of(chair, m1, m2)) {
      TestUsers.sql(
          "UPDATE member_evaluation_rows SET review_state = 'agreed', member_grade = g.grade"
              + " FROM member_evaluations e, ai_grades g"
              + " WHERE member_evaluation_rows.member_evaluation_id = e.id AND e.user_id = ?"
              + " AND e.tender_vendor_id = ? AND g.id = member_evaluation_rows.ai_grade_id",
          u.id(),
          tv);
    }
    TestUsers.sql(
        "UPDATE member_evaluation_rows SET review_state = 'overridden',"
            + " member_grade = 'partially_compliant', note = 'Encryption is only for backups.'"
            + " WHERE requirement_id = ? AND member_evaluation_id ="
            + " (SELECT id FROM member_evaluations WHERE tender_vendor_id = ? AND user_id = ?)",
        r1,
        tv,
        m2.id());
    TestUsers.sql(
        "UPDATE member_evaluations SET status = 'submitted', first_submitted_at = now(),"
            + " submitted_at = now() WHERE tender_vendor_id = ?",
        tv);
    TestUsers.sql("UPDATE tenders SET status = 'consolidation' WHERE id = ?", tender);
    return new World(admin, chair, m1, m2, observer, tender, tv, r1, r2);
  }

  private static UUID requirement(UUID tender, String text) {
    return (UUID)
        TestUsers.scalar(
            "INSERT INTO requirements (tender_id, text, importance, origin) VALUES (?, ?, 'mandatory',"
                + " 'manual') RETURNING id",
            tender,
            text);
  }
}
