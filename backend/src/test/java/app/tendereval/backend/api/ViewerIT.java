package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.StorageFixture;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** UX-DOC-06, UX-GEN-03, UX-NFR-07, rule 9: the viewer reads, and nothing downloads. */
class ViewerIT extends AbstractApiIT {

  private record Doc(UUID tender, UUID version, UUID p1, UUID p2, byte[] pdf) {}

  private Doc ready(UUID tender, TestUser admin) {
    UUID doc =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'brd', 'Terms') RETURNING id",
                tender);
    UUID v = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format,"
            + " size_bytes, sha256, storage_key, status, uploaded_by) VALUES (?, ?, 1, 'a.pdf',"
            + " 'pdf', 10, repeat('a', 64), ?, 'uploading', ?)",
        v,
        doc,
        "originals/%s/%s/%s.pdf".formatted(tender, doc, v),
        admin.id());
    TestUsers.sql("UPDATE document_versions SET status = 'processing' WHERE id = ?", v);
    String key = "rendered/%s/%s/document.pdf".formatted(tender, v);
    byte[] pdf = ("%PDF-1.7\n" + "x".repeat(2000)).getBytes(StandardCharsets.US_ASCII);
    StorageFixture.put(StorageFixture.DERIVED, key, pdf);
    for (int p = 1; p <= 2; p++) {
      TestUsers.sql(
          "INSERT INTO document_pages (document_version_id, page_no, width, height, render_key)"
              + " VALUES (?, ?, 595.2, 841.9, ?)",
          v,
          p,
          key);
    }
    UUID section = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO document_sections (id, document_version_id, title, level, sort_order,"
            + " start_page, end_page) VALUES (?, ?, '1. Scope', 1, 0, 1, 2)",
        section,
        v);
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO passages (id, document_version_id, section_id, page_no, passage_no, text, bbox,"
            + " char_start, char_end, content_hash) VALUES (?, ?, ?, 1, 1, ?, '[[72,100,300,112]]'::jsonb,"
            + " 0, 40, repeat('b', 64)), (?, ?, ?, 2, 1, ?, '[[72,200,300,212],[72,214,300,226]]'::jsonb,"
            + " 0, 60, repeat('c', 64))",
        p1,
        v,
        section,
        "The supplier shall encrypt all data at rest.",
        p2,
        v,
        section,
        "Availability is measured per calendar month.");
    TestUsers.sql("UPDATE document_versions SET status = 'ready', page_count = 2 WHERE id = ?", v);
    return new Doc(tender, v, p1, p2, pdf);
  }

  @Test
  void aMemberReadsOutlinePagesPassagesAndSearch() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    Doc d = ready(Fixtures.tender(admin, chair), admin);

    get("/document-versions/" + d.version() + "/outline", chair)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.sections[0].title")
        .isEqualTo("1. Scope")
        .jsonPath("$.sections[0].end_page")
        .isEqualTo(2);
    get("/document-versions/" + d.version() + "/pages", chair)
        .exchange()
        .expectBody()
        .jsonPath("$[1].page_no")
        .isEqualTo(2)
        .jsonPath("$[0].width")
        .isEqualTo(595.2);
    get("/document-versions/" + d.version() + "/passages?page_from=2&page_to=2", chair)
        .exchange()
        .expectBody()
        .jsonPath("$[0].id")
        .isEqualTo(d.p2().toString())
        .jsonPath("$[0].bbox[1][1]")
        .isEqualTo(214)
        .jsonPath("$.length()")
        .isEqualTo(1);
    get("/document-versions/" + d.version() + "/passages?ids=" + d.p1(), chair)
        .exchange()
        .expectBody()
        .jsonPath("$[0].text")
        .isEqualTo("The supplier shall encrypt all data at rest.");
    get("/passages/" + d.p1(), chair)
        .exchange()
        .expectBody()
        .jsonPath("$.page_no")
        .isEqualTo(1)
        .jsonPath("$.bbox[0][2]")
        .isEqualTo(300);
    get("/document-versions/" + d.version() + "/search?q=encrypt", chair)
        .exchange()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(1)
        .jsonPath("$.hits[0].passage_id")
        .isEqualTo(d.p1().toString())
        .jsonPath("$.hits[0].page_no")
        .isEqualTo(1);
    get("/document-versions/" + d.version() + "/search?q=", chair)
        .exchange()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(0);
  }

  @Test
  void aPersonOutsideTheTenderSeesNothingOfIt() {
    TestUser admin = TestUsers.create("admin");
    Doc d = ready(Fixtures.tender(admin, TestUsers.create("member")), admin);
    TestUser outsider = TestUsers.create("member");
    for (String path :
        new String[] {
          "/outline", "/pages", "/passages", "/search?q=data", "/render",
        }) {
      get("/document-versions/" + d.version() + path, outsider)
          .exchange()
          .expectStatus()
          .isNotFound();
    }
    get("/passages/" + d.p1(), outsider).exchange().expectStatus().isNotFound();
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM document_access_events WHERE document_version_id = ?",
                d.version()))
        .isEqualTo(0L);
  }

  @Test
  void theDocumentIsStreamedWithRangeNoStoreAndOneAccessRowPerViewing() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    Doc d = ready(Fixtures.tender(admin, chair), admin);

    var whole =
        get("/document-versions/" + d.version() + "/render", chair)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("Cache-Control", "no-store")
            .expectHeader()
            .contentType("application/pdf")
            .expectHeader()
            .value("Content-Disposition", v -> assertThat(v).startsWith("inline"))
            .expectBody()
            .returnResult();
    assertThat(whole.getResponseBody()).isEqualTo(d.pdf());

    // a later range of the same viewing is a 206 and is not logged again
    web.get()
        .uri("/document-versions/" + d.version() + "/render")
        .header("Authorization", "Bearer " + tokenFor(chair))
        .header("Range", "bytes=100-199")
        .exchange()
        .expectStatus()
        .isEqualTo(206)
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody(byte[].class)
        .value(b -> assertThat(b).hasSize(100));
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM document_access_events WHERE document_version_id = ?"
                    + " AND user_id = ?",
                d.version(),
                chair.id()))
        .isEqualTo(1L);
  }

  @Test
  void aVersionStillProcessingCannotBeRendered() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair);
    UUID dv = Fixtures.rfp(tender, admin);
    TestUsers.sql("UPDATE document_versions SET status = 'ready' WHERE id = ?", dv);
    get("/document-versions/" + dv + "/render", chair)
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("document_not_ready");
  }

  @Test
  void thereIsNoDownloadOrStorageUrlAnywhereInTheContract() throws Exception {
    String spec =
        java.nio.file.Files.readString(java.nio.file.Path.of("..", "api", "openapi.yaml"));
    // the only route that returns document bytes is render; no path names a download or a URL
    assertThat(spec).doesNotContainIgnoringCase("/download");
    assertThat(spec).doesNotContainIgnoringCase("presign");
    assertThat(spec).doesNotContainIgnoringCase("signed_url");
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("application/pdf|application/octet-stream").matcher(spec);
    int count = 0;
    while (m.find()) {
      count++;
    }
    // render answers 200 and 206, and the report export file (Phase 5) is the only other file
    assertThat(count).isEqualTo(3);
  }
}
