package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.StorageFixture;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;

/** UX-VND-01..05: vendors, vendors of a tender, proposal versions. */
class VendorsApiIT extends AbstractApiIT {

  private String createVendor(TestUser admin, String name, String site) {
    byte[] body =
        post("/vendors", admin)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", name, "website", site))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectHeader()
            .exists("ETag")
            .expectBody()
            .returnResult()
            .getResponseBody();
    return JsonPath.read(new String(body), "$.id");
  }

  private static byte[] pdf(int seed) {
    return ("%PDF-1.7\n" + "proposal-" + seed + "-" + "x".repeat(500))
        .getBytes(StandardCharsets.US_ASCII);
  }

  private MultipartBodyBuilder files(String[] names, String[] roles, String reason) {
    MultipartBodyBuilder b = new MultipartBodyBuilder();
    for (int i = 0; i < names.length; i++) {
      final String n = names[i];
      b.part(
              "files",
              new ByteArrayResource(pdf(i)) {
                @Override
                public String getFilename() {
                  return n;
                }
              })
          .contentType(MediaType.APPLICATION_OCTET_STREAM);
    }
    for (String r : roles) {
      b.part("file_roles", r);
    }
    if (reason != null) {
      b.part("change_reason", reason);
    }
    return b;
  }

  @Test
  void vendorsAreCreatedUpdatedAndChecked() {
    TestUser admin = TestUsers.create("admin");
    String unique = "Zephyr" + UUID.randomUUID().toString().substring(0, 8);
    String id =
        createVendor(admin, unique + " Ltd.", "https://www." + unique.toLowerCase() + ".example");

    // the same company written differently is a possible duplicate until confirmed
    post("/vendors", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("name", unique.toUpperCase() + " Limited"))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("possible_duplicate")
        .jsonPath("$.matches[0].id")
        .isEqualTo(id);
    get(
            "/vendors/duplicate-check?name="
                + unique
                + "&website=https://"
                + unique.toLowerCase()
                + ".example",
            admin)
        .exchange()
        .expectBody()
        .jsonPath("$.matches[0].id")
        .isEqualTo(id);
    post("/vendors", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("name", unique + " Limited", "confirm_duplicate", true))
        .exchange()
        .expectStatus()
        .isCreated();

    var got =
        get("/vendors/" + id, admin).exchange().expectStatus().isOk().expectBody().returnResult();
    String etag = got.getResponseHeaders().getETag();
    patch("/vendors/" + id, admin)
        .header("If-Match", etag)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("contact_person", "A. Person"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.contact_person")
        .isEqualTo("A. Person");
    patch("/vendors/" + id, admin)
        .header("If-Match", etag) // stale now
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("contact_person", "B"))
        .exchange()
        .expectStatus()
        .isEqualTo(412);
    get("/vendors?q=" + unique, admin).exchange().expectBody().jsonPath("$.total").isEqualTo(2);
  }

  @Test
  void aMemberCannotManageVendorsButSeesThoseOfTheirTender() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair);
    String vendor = createVendor(admin, "Quill" + UUID.randomUUID(), "https://quill.example");
    post("/vendors", chair)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("name", "Nope"))
        .exchange()
        .expectStatus()
        .isForbidden();
    post("/tenders/" + tender + "/vendors", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("vendor_id", vendor))
        .exchange()
        .expectStatus()
        .isCreated();
    post("/tenders/" + tender + "/vendors", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("vendor_id", vendor))
        .exchange()
        .expectStatus()
        .isEqualTo(409);
    get("/tenders/" + tender + "/vendors", chair)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(1)
        .jsonPath("$.items[0].vendor.id")
        .isEqualTo(vendor)
        .jsonPath("$.items[0].file_count")
        .isEqualTo(0)
        .jsonPath("$.items[0].actions.upload_proposal.allowed")
        .isEqualTo(false);
    get("/tenders/" + tender + "/vendors", admin)
        .exchange()
        .expectBody()
        .jsonPath("$.items[0].actions.upload_proposal.allowed")
        .isEqualTo(true);
    TestUser outsider = TestUsers.create("member");
    get("/tenders/" + tender + "/vendors", outsider)
        .exchange()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(0);
  }

  @Test
  void aNewVendorCanBeAddedInOneStep() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = Fixtures.tender(admin, TestUsers.create("member"));
    post("/tenders/" + tender + "/vendors", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("new_vendor", Map.of("name", "Fresh" + UUID.randomUUID())))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.vendor.name")
        .value(n -> assertThat((String) n).startsWith("Fresh"));
  }

  @Test
  void aProposalVersionHoldsSeveralFilesAndANewOneSupersedesIt() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = Fixtures.tender(admin, TestUsers.create("member"));
    String vendor = createVendor(admin, "Lumen" + UUID.randomUUID(), "https://lumen.example");
    byte[] tv =
        post("/tenders/" + tender + "/vendors", admin)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("vendor_id", vendor))
            .exchange()
            .expectBody()
            .returnResult()
            .getResponseBody();
    String tvId = JsonPath.read(new String(tv), "$.id");

    var first =
        web.post()
            .uri("/tender-vendors/" + tvId + "/proposal-versions")
            .header("Authorization", "Bearer " + tokenFor(admin))
            .body(
                BodyInserters.fromMultipartData(
                    files(
                            new String[] {"technical.pdf", "pricing.pdf"},
                            new String[] {"main", "annex"},
                            null)
                        .build()))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody()
            .jsonPath("$.version_no")
            .isEqualTo(1)
            .jsonPath("$.status")
            .isEqualTo("uploaded")
            .jsonPath("$.files.length()")
            .isEqualTo(2)
            .jsonPath("$.files[0].role")
            .isEqualTo("main")
            .jsonPath("$.files[0].file_name")
            .isEqualTo("technical.pdf")
            .jsonPath("$.files[0].status")
            .isEqualTo("processing")
            .returnResult();
    String v1Main =
        JsonPath.read(new String(first.getResponseBody()), "$.files[0].document_version_id");
    String v1Annex =
        JsonPath.read(new String(first.getResponseBody()), "$.files[1].document_version_id");
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM jobs WHERE target_id IN (?::uuid, ?::uuid)"
                    + " AND job_type = 'document_processing' AND status = 'queued'",
                v1Main,
                v1Annex))
        .isEqualTo(2L);
    assertThat(
            TestUsers.scalar(
                "SELECT doc_type FROM documents d JOIN document_versions v ON v.document_id = d.id"
                    + " WHERE v.id = ?::uuid",
                v1Main))
        .isEqualTo("proposal");

    // version 2: a new main file, the old annex kept
    var reuse = files(new String[] {"technical-v2.pdf"}, new String[] {"main"}, "Corrected scope");
    reuse.part("reuse_document_version_ids", v1Annex);
    web.post()
        .uri("/tender-vendors/" + tvId + "/proposal-versions")
        .header("Authorization", "Bearer " + tokenFor(admin))
        .body(BodyInserters.fromMultipartData(reuse.build()))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.version_no")
        .isEqualTo(2)
        .jsonPath("$.change_reason")
        .isEqualTo("Corrected scope")
        .jsonPath("$.files.length()")
        .isEqualTo(2);

    get("/tender-vendors/" + tvId + "/proposal-versions", admin)
        .exchange()
        .expectBody()
        .jsonPath("$[0].version_no")
        .isEqualTo(2)
        .jsonPath("$[1].status")
        .isEqualTo("superseded");
    get("/tender-vendors/" + tvId, admin)
        .exchange()
        .expectBody()
        .jsonPath("$.proposal_version_no")
        .isEqualTo(2)
        .jsonPath("$.file_count")
        .isEqualTo(2);
    get("/tender-vendors/" + tvId + "/proposal-versions/impact", admin)
        .exchange()
        .expectBody()
        .jsonPath("$.rows_to_outdate")
        .isEqualTo(0);
    assertThat(
            StorageFixture.size(
                StorageFixture.DOCUMENTS,
                (String)
                    TestUsers.scalar(
                        "SELECT storage_key FROM document_versions WHERE id = ?::uuid", v1Main)))
        .isGreaterThan(0);
  }

  @Test
  void anIncompleteOrInvalidUploadLeavesNothingBehind() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = Fixtures.tender(admin, TestUsers.create("member"));
    String vendor = createVendor(admin, "Nimbus" + UUID.randomUUID(), "https://nimbus.example");
    byte[] tv =
        post("/tenders/" + tender + "/vendors", admin)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("vendor_id", vendor))
            .exchange()
            .expectBody()
            .returnResult()
            .getResponseBody();
    String tvId = JsonPath.read(new String(tv), "$.id");

    // two main files
    web.post()
        .uri("/tender-vendors/" + tvId + "/proposal-versions")
        .header("Authorization", "Bearer " + tokenFor(admin))
        .body(
            BodyInserters.fromMultipartData(
                files(new String[] {"a.pdf", "b.pdf"}, new String[] {"main", "main"}, null)
                    .build()))
        .exchange()
        .expectStatus()
        .isEqualTo(422);
    // a file that is not a PDF or DOCX
    MultipartBodyBuilder bad = new MultipartBodyBuilder();
    bad.part(
            "files",
            new ByteArrayResource("not a document".getBytes()) {
              @Override
              public String getFilename() {
                return "x.pdf";
              }
            })
        .contentType(MediaType.APPLICATION_OCTET_STREAM);
    bad.part("file_roles", "main");
    web.post()
        .uri("/tender-vendors/" + tvId + "/proposal-versions")
        .header("Authorization", "Bearer " + tokenFor(admin))
        .body(BodyInserters.fromMultipartData(bad.build()))
        .exchange()
        .expectStatus()
        .isEqualTo(415);

    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM proposal_versions WHERE tender_vendor_id = ?::uuid", tvId))
        .isEqualTo(0L);
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM documents WHERE tender_id = ? AND doc_type = 'proposal'"
                    + " AND deleted_at IS NULL",
                tender))
        .isEqualTo(0L);
  }

  @Test
  void onlyTheAdministratorUploadsAProposal() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair);
    String vendor = createVendor(admin, "Orbit" + UUID.randomUUID(), "https://orbit.example");
    byte[] tv =
        post("/tenders/" + tender + "/vendors", admin)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("vendor_id", vendor))
            .exchange()
            .expectBody()
            .returnResult()
            .getResponseBody();
    String tvId = JsonPath.read(new String(tv), "$.id");
    web.post()
        .uri("/tender-vendors/" + tvId + "/proposal-versions")
        .header("Authorization", "Bearer " + tokenFor(chair))
        .body(
            BodyInserters.fromMultipartData(
                files(new String[] {"a.pdf"}, new String[] {"main"}, null).build()))
        .exchange()
        .expectStatus()
        .isForbidden();
  }
}
