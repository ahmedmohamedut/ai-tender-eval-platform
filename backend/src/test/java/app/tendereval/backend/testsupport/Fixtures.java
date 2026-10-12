package app.tendereval.backend.testsupport;

import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.util.List;
import java.util.UUID;

/** Rows the tests arrange directly (as the owner), so the behaviour under test is the API's. */
public final class Fixtures {

  private Fixtures() {}

  public static UUID tender(TestUser admin, TestUser chair, TestUser... others) {
    UUID id =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO tenders (name, created_by) VALUES (?, ?) RETURNING id",
                "Tender " + UUID.randomUUID(),
                admin.id());
    TestUsers.sql(
        "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
        id,
        chair.id());
    for (TestUser o : others) {
      TestUsers.sql("INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", id, o.id());
    }
    return id;
  }

  /** An RFP with one stored version (status ready), enough for the readiness check. */
  public static UUID rfp(UUID tender, TestUser uploader) {
    UUID doc =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'rfp', 'RFP') RETURNING id",
                tender);
    UUID dv = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format, size_bytes,"
            + " sha256, storage_key, status, uploaded_by) VALUES (?, ?, 1, 'rfp.pdf', 'pdf', 100,"
            + " repeat('a', 64), ?, 'uploading', ?)",
        dv,
        doc,
        "originals/%s/%s/%s.pdf".formatted(tender, doc, dv),
        uploader.id());
    TestUsers.sql("UPDATE document_versions SET status = 'processing' WHERE id = ?", dv);
    TestUsers.sql("UPDATE document_versions SET status = 'ready', page_count = 1 WHERE id = ?", dv);
    return dv;
  }

  public static List<UUID> ids(TestUser... users) {
    return java.util.Arrays.stream(users).map(TestUser::id).toList();
  }
}
