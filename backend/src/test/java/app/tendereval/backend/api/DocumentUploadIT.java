package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.StorageFixture;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;

/** UX-DOC-01..05, PI 4.4: the upload streams into object storage and records its job. */
class DocumentUploadIT extends AbstractApiIT {

  @AfterEach
  void restoreLimit() {
    TestUsers.sql("UPDATE settings SET value = '104857600' WHERE key = 'upload.max_bytes'");
  }

  static byte[] pdf(int size, long seed) {
    byte[] b = new byte[size];
    new Random(seed).nextBytes(b);
    byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(head, 0, b, 0, head.length);
    return b;
  }

  static byte[] docx(int size, long seed) {
    byte[] b = new byte[size];
    new Random(seed).nextBytes(b);
    b[0] = 'P';
    b[1] = 'K';
    b[2] = 3;
    b[3] = 4;
    return b;
  }

  static String sha(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }

  private MultipartBodyBuilder form(
      String docType, String title, String parent, String fileName, byte[] data) {
    MultipartBodyBuilder b = new MultipartBodyBuilder();
    if (docType != null) {
      b.part("doc_type", docType);
    }
    if (title != null) {
      b.part("title", title);
    }
    if (parent != null) {
      b.part("parent_document_id", parent);
    }
    b.part(
            "file",
            new ByteArrayResource(data) {
              @Override
              public String getFilename() {
                return fileName;
              }
            })
        .contentType(MediaType.APPLICATION_OCTET_STREAM);
    return b;
  }

  private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec upload(
      TestUser user, UUID tender, MultipartBodyBuilder body) {
    return web.post()
        .uri("/tenders/" + tender + "/documents")
        .header("Authorization", "Bearer " + tokenFor(user))
        .body(BodyInserters.fromMultipartData(body.build()))
        .exchange();
  }

  private org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>>
      versionForm(String fileName, byte[] data, String note) {
    MultipartBodyBuilder b = form(null, null, null, fileName, data);
    b.part("note", note);
    return b.build();
  }

  private UUID newTender(TestUser admin) {
    return Fixtures.tender(admin, TestUsers.create("member"));
  }

  // ---- the upload path --------------------------------------------------------------------------

  @Test
  void anRfpIsStoredHashedAndQueuedForProcessing() throws Exception {
    TestUser admin = TestUsers.create("admin");
    UUID tender = newTender(admin);
    byte[] data = pdf(300_000, 1);
    var result =
        upload(admin, tender, form("rfp", "Cloud RFP", null, "rfp.pdf", data))
            .expectStatus()
            .isCreated()
            .expectHeader()
            .exists("Location")
            .expectBody()
            .jsonPath("$.doc_type")
            .isEqualTo("rfp")
            .jsonPath("$.title")
            .isEqualTo("Cloud RFP")
            .jsonPath("$.version_count")
            .isEqualTo(1)
            .jsonPath("$.current_version.version_no")
            .isEqualTo(1)
            .jsonPath("$.current_version.file_format")
            .isEqualTo("pdf")
            .jsonPath("$.current_version.size_bytes")
            .isEqualTo(data.length)
            .jsonPath("$.current_version.status")
            .isEqualTo("processing")
            .jsonPath("$.current_version.job_id")
            .isNotEmpty()
            .returnResult();
    String body = new String(result.getResponseBody());
    UUID version = UUID.fromString(JsonPath.read(body, "$.current_version.id"));
    UUID document = UUID.fromString(JsonPath.read(body, "$.id"));
    String key = "originals/%s/%s/%s.pdf".formatted(tender, document, version);

    assertThat(TestUsers.scalar("SELECT storage_key FROM document_versions WHERE id = ?", version))
        .isEqualTo(key);
    assertThat(TestUsers.scalar("SELECT sha256 FROM document_versions WHERE id = ?", version))
        .isEqualTo(sha(data));
    assertThat(StorageFixture.size(StorageFixture.DOCUMENTS, key)).isEqualTo(data.length);
    assertThat(StorageFixture.read(StorageFixture.DOCUMENTS, key)).isEqualTo(data);
    // one open job, queued, started by the uploader, and an audit event naming the upload
    assertThat(
            TestUsers.scalar(
                "SELECT status || '|' || job_type || '|' || (started_by = ?)::text FROM jobs WHERE target_id = ?",
                admin.id(),
                version))
        .isEqualTo("queued|document_processing|true");
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM audit_events WHERE action = 'document_uploaded' AND object_id = ?",
                version))
        .isEqualTo(1L);
  }

  @Test
  void aLargeFileGoesUpInPartsAndArrivesIntact() throws Exception {
    TestUser admin = TestUsers.create("admin");
    UUID tender = newTender(admin);
    byte[] data = pdf(20 * 1024 * 1024 + 1234, 7); // 8 MiB + 8 MiB + 4 MiB + a little
    var r =
        upload(admin, tender, form("brd", null, null, "big.pdf", data))
            .expectStatus()
            .isCreated()
            .expectBody()
            .returnResult();
    String json = new String(r.getResponseBody());
    String versionId = JsonPath.read(json, "$.current_version.id");
    String key =
        (String)
            TestUsers.scalar(
                "SELECT storage_key FROM document_versions WHERE id = ?::uuid", versionId);
    assertThat(StorageFixture.size(StorageFixture.DOCUMENTS, key)).isEqualTo(data.length);
    assertThat(sha(StorageFixture.read(StorageFixture.DOCUMENTS, key))).isEqualTo(sha(data));
    assertThat(TestUsers.scalar("SELECT sha256 FROM document_versions WHERE storage_key = ?", key))
        .isEqualTo(sha(data));
    // the title defaults to the file name
    assertThat((String) JsonPath.read(json, "$.title")).isEqualTo("big");
  }

  @Test
  void aDocxIsRecognisedByItsSignature() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = newTender(admin);
    upload(admin, tender, form("other", "Notes", null, "notes.docx", docx(5000, 3)))
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.current_version.file_format")
        .isEqualTo("docx");
  }

  @Test
  void otherFormatsAndMismatchedNamesAreRefusedBeforeAnythingIsStored() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = newTender(admin);
    byte[] text = "just some text, not a document".getBytes(StandardCharsets.UTF_8);
    for (var c :
        new Object[][] {
          {"notes.txt", text},
          {"fake.pdf", text}, // a name is not a format
          {"zip.pdf", docx(2000, 1)}, // a ZIP named .pdf
          {"doc.docx", pdf(2000, 1)}, // a PDF named .docx
          {"image.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0}}
        }) {
      upload(admin, tender, form("other", null, null, (String) c[0], (byte[]) c[1]))
          .expectStatus()
          .isEqualTo(415)
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("unsupported_format");
    }
    assertThat(TestUsers.scalar("SELECT count(*) FROM documents WHERE tender_id = ?", tender))
        .isEqualTo(0L);
    assertThat(TestUsers.scalar("SELECT count(*) FROM jobs WHERE tender_id = ?", tender))
        .isEqualTo(0L);
  }

  @Test
  void aFileOverTheLimitIsRefusedAndNothingStays() throws Exception {
    TestUser admin = TestUsers.create("admin");
    UUID tender = newTender(admin);
    TestUsers.sql("UPDATE settings SET value = '1048576' WHERE key = 'upload.max_bytes'");
    // Declared length over the limit: refused before the body is read.
    upload(admin, tender, form("brd", null, null, "big.pdf", pdf(3 * 1024 * 1024, 2)))
        .expectStatus()
        .isEqualTo(413)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("file_too_large")
        .jsonPath("$.max_bytes")
        .isEqualTo(1048576);
    assertThat(TestUsers.scalar("SELECT count(*) FROM documents WHERE tender_id = ?", tender))
        .isEqualTo(0L);
  }

  @Test
  void aStreamThatOutgrowsTheLimitMidwayIsAbortedAndSoftDeleted() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = newTender(admin);
    TestUsers.sql("UPDATE settings SET value = '1048576' WHERE key = 'upload.max_bytes'");
    // No Content-Length (chunked): the limit is found while streaming.
    byte[] data = pdf(10 * 1024 * 1024, 5);
    MultipartBodyBuilder b = new MultipartBodyBuilder();
    b.part("doc_type", "other");
    b.asyncPart(
            "file",
            reactor.core.publisher.Flux.just(
                new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(data)),
            org.springframework.core.io.buffer.DataBuffer.class)
        .filename("big.pdf")
        .contentType(MediaType.APPLICATION_OCTET_STREAM);
    web.post()
        .uri("/tenders/" + tender + "/documents")
        .header("Authorization", "Bearer " + tokenFor(admin))
        .body(
            BodyInserters.fromMultipartAsyncData(
                "file",
                reactor.core.publisher.Flux.just(
                    new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(data)),
                org.springframework.core.io.buffer.DataBuffer.class))
        .exchange()
        .expectStatus()
        .is4xxClientError();
    // whatever the 4xx, no version is left active and no object remains
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM document_versions v JOIN documents d ON d.id = v.document_id"
                    + " WHERE d.tender_id = ? AND v.deleted_at IS NULL",
                tender))
        .isEqualTo(0L);
  }

  // ---- rules of the document types
  // -------------------------------------------------------------------

  @Test
  void aTenderHasOneRfpAndAnAddendumPointsAtIt() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = newTender(admin);
    var rfp =
        upload(admin, tender, form("rfp", "RFP", null, "rfp.pdf", pdf(2000, 1)))
            .expectStatus()
            .isCreated()
            .expectBody()
            .returnResult();
    String rfpId = JsonPath.read(new String(rfp.getResponseBody()), "$.id");

    upload(admin, tender, form("rfp", "Second RFP", null, "rfp2.pdf", pdf(2000, 2)))
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("rfp_exists");
    upload(admin, tender, form("addendum", "Clarification 1", rfpId, "add1.pdf", pdf(2000, 3)))
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.parent_document_id")
        .isEqualTo(rfpId);
    upload(admin, tender, form("addendum", "Orphan", null, "add2.pdf", pdf(2000, 4)))
        .expectStatus()
        .isEqualTo(422);
    upload(
            admin,
            tender,
            form(
                "addendum", "Wrong parent", UUID.randomUUID().toString(), "add3.pdf", pdf(2000, 5)))
        .expectStatus()
        .isEqualTo(422);
    upload(admin, tender, form("proposal", "Vendor file", null, "p.pdf", pdf(2000, 6)))
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.errors[0].field")
        .isEqualTo("doc_type");
    upload(admin, tender, form(null, null, null, "no-type.pdf", pdf(2000, 7)))
        .expectStatus()
        .isEqualTo(422);
  }

  @Test
  void aNewVersionKeepsTheOldOneAndTheImpactIsAskedFor() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = newTender(admin);
    var first =
        upload(admin, tender, form("rfp", "RFP", null, "rfp.pdf", pdf(3000, 1)))
            .expectStatus()
            .isCreated()
            .expectBody()
            .returnResult();
    String doc = JsonPath.read(new String(first.getResponseBody()), "$.id");
    String v1 = JsonPath.read(new String(first.getResponseBody()), "$.current_version.id");

    var second =
        web.post()
            .uri("/documents/" + doc + "/versions")
            .header("Authorization", "Bearer " + tokenFor(admin))
            .body(
                BodyInserters.fromMultipartData(
                    versionForm("rfp-v2.pdf", pdf(4000, 2), "Revised budget")))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody()
            .jsonPath("$.version_no")
            .isEqualTo(2)
            .jsonPath("$.status")
            .isEqualTo("processing")
            .returnResult();
    String v2 = JsonPath.read(new String(second.getResponseBody()), "$.id");

    get("/documents/" + doc, admin)
        .exchange()
        .expectBody()
        .jsonPath("$.version_count")
        .isEqualTo(2)
        .jsonPath("$.current_version.id")
        .isEqualTo(v2)
        .jsonPath("$.versions[1].id")
        .isEqualTo(v1);
    // two processing jobs, one per version; the first version's object is untouched
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM jobs WHERE job_type = 'document_processing'"
                    + " AND target_id IN (?::uuid, ?::uuid)",
                v1,
                v2))
        .isEqualTo(2L);

    get("/document-versions/" + v2 + "/impact", admin)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.previous_version_id")
        .isEqualTo(v1)
        .jsonPath("$.requirement_count")
        .isEqualTo(0)
        .jsonPath("$.decision_required")
        .isEqualTo(false);
    post("/document-versions/" + v2 + "/impact-decision", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("choice", "keep", "note", "No extraction yet"))
        .exchange()
        .expectStatus()
        .isNoContent();
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM audit_events WHERE action = 'document_impact_decided'"
                    + " AND object_id = ?::uuid",
                v2))
        .isEqualTo(1L);
    get("/document-versions/" + v1 + "/impact", admin)
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("no_previous_version");
  }

  // ---- access
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyTheAdministratorUploadsAndCommitteeMembersListAndRead() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser outsider = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair);
    byte[] data = pdf(2000, 9);
    upload(chair, tender, form("rfp", "RFP", null, "rfp.pdf", data)).expectStatus().isForbidden();
    upload(TestUsers.create("observer"), tender, form("rfp", "RFP", null, "rfp.pdf", data))
        .expectStatus()
        .isForbidden();
    upload(admin, UUID.randomUUID(), form("rfp", "RFP", null, "rfp.pdf", data))
        .expectStatus()
        .isNotFound();
    var r =
        upload(admin, tender, form("rfp", "RFP", null, "rfp.pdf", data))
            .expectStatus()
            .isCreated()
            .expectBody()
            .returnResult();
    String doc = JsonPath.read(new String(r.getResponseBody()), "$.id");

    get("/tenders/" + tender + "/documents", chair)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].id")
        .isEqualTo(doc)
        .jsonPath("$.total")
        .isEqualTo(1);
    get("/documents/" + doc, chair).exchange().expectStatus().isOk();
    get("/documents/" + doc, outsider).exchange().expectStatus().isNotFound(); // not visible at all
    get("/tenders/" + tender + "/documents", outsider)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(0);
  }

  @Test
  void anArchivedTenderTakesNoUpload() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = newTender(admin);
    for (String s : java.util.List.of("requirements", "evaluation", "consolidation", "finalized")) {
      TestUsers.sql("UPDATE tenders SET status = ? WHERE id = ?", s, tender);
    }
    TestUsers.sql("UPDATE tenders SET archived_at = now() WHERE id = ?", tender);
    upload(admin, tender, form("rfp", "RFP", null, "rfp.pdf", pdf(2000, 1)))
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("tender_archived");
  }

  @Test
  void aRepeatWithTheSameIdempotencyKeyStoresNothingTwice() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = newTender(admin);
    String key = "upload-" + UUID.randomUUID();
    byte[] data = pdf(4000, 4);
    var first =
        web.post()
            .uri("/tenders/" + tender + "/documents")
            .header("Authorization", "Bearer " + tokenFor(admin))
            .header("Idempotency-Key", key)
            .body(
                BodyInserters.fromMultipartData(
                    form("other", "Once", null, "once.pdf", data).build()))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody()
            .returnResult();
    var second =
        web.post()
            .uri("/tenders/" + tender + "/documents")
            .header("Authorization", "Bearer " + tokenFor(admin))
            .header("Idempotency-Key", key)
            .body(
                BodyInserters.fromMultipartData(
                    form("other", "Once", null, "once.pdf", data).build()))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectHeader()
            .valueEquals("Idempotent-Replay", "true")
            .expectBody()
            .returnResult();
    assertThat((String) JsonPath.read(new String(second.getResponseBody()), "$.id"))
        .isEqualTo(JsonPath.read(new String(first.getResponseBody()), "$.id"));
    assertThat(TestUsers.scalar("SELECT count(*) FROM documents WHERE tender_id = ?", tender))
        .isEqualTo(1L);
  }
}
