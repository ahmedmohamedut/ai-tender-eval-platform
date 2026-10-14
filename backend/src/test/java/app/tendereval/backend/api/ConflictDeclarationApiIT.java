package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import io.nats.client.JetStream;
import io.nats.client.Message;
import io.nats.client.api.PublishAck;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** UX-TND-07, PG-13: the declaration a member makes before opening a vendor's proposal. */
class ConflictDeclarationApiIT extends AbstractApiIT {

  @Autowired JetStream js;

  @BeforeEach
  void publishWorks() throws Exception {
    when(js.publish(any(Message.class))).thenReturn(mock(PublishAck.class));
  }

  private record World(TestUser admin, TestUser chair, TestUser member, UUID tender, UUID vendor) {}

  private World world() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser member = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, member);
    UUID v =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO vendors (name, normalized_name) VALUES ('Acme', ?) RETURNING id",
                UUID.randomUUID().toString());
    UUID tv =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO tender_vendors (tender_id, vendor_id, added_by) VALUES (?, ?, ?) RETURNING id",
                tender,
                v,
                admin.id());
    return new World(admin, chair, member, tender, tv);
  }

  private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec declare(
      World w, TestUser who, Map<String, Object> body) {
    return web.post()
        .uri("/tender-vendors/" + w.vendor() + "/declaration")
        .header("Authorization", "Bearer " + tokenFor(who))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec mine(
      World w, TestUser who) {
    return web.get()
        .uri("/tender-vendors/" + w.vendor() + "/declaration")
        .header("Authorization", "Bearer " + tokenFor(who))
        .exchange();
  }

  @Test
  void aMemberDeclaresNoConflictOnceAndThenSeesItOnlyThemselves() {
    World w = world();
    mine(w, w.member()).expectStatus().isOk().expectBody().isEmpty();
    declare(w, w.member(), Map.of("has_conflict", false))
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.has_conflict")
        .isEqualTo(false)
        .jsonPath("$.user_id")
        .isEqualTo(w.member().id().toString());
    mine(w, w.member())
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.has_conflict")
        .isEqualTo(false);
    // the Chair has not declared: their own is still empty
    mine(w, w.chair()).expectStatus().isOk().expectBody().isEmpty();
    // immutable: a second declaration is refused, whatever it says
    declare(w, w.member(), Map.of("has_conflict", true, "statement", "I changed my mind later"))
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("already_declared");
  }

  @Test
  void aConflictNeedsAStatementAndIsRecorded() {
    World w = world();
    declare(w, w.member(), Map.of("has_conflict", true)).expectStatus().isEqualTo(422);
    declare(w, w.member(), Map.of("has_conflict", true, "statement", "short"))
        .expectStatus()
        .isEqualTo(422);
    declare(
            w,
            w.member(),
            Map.of("has_conflict", true, "statement", "I advised this vendor in 2025"))
        .expectStatus()
        .isCreated();
    // the proposal file is closed to them (clearance needs "no conflict")
    UUID doc =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'proposal', 'P') RETURNING id",
                w.tender());
    UUID dv = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format, size_bytes,"
            + " sha256, storage_key, status, uploaded_by) VALUES (?, ?, 1, 'p.pdf', 'pdf', 10,"
            + " repeat('a', 64), ?, 'uploading', ?)",
        dv,
        doc,
        "originals/%s/%s/%s.pdf".formatted(w.tender(), doc, dv),
        w.admin().id());
    UUID pv =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by) VALUES (?, 1, ?) RETURNING id",
                w.vendor(),
                w.admin().id());
    TestUsers.sql(
        "INSERT INTO proposal_version_files (proposal_version_id, document_version_id, file_role)"
            + " VALUES (?, ?, 'main')",
        pv,
        dv);
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM conflict_declarations WHERE tender_vendor_id = ? AND has_conflict",
                w.vendor()))
        .isEqualTo(1L);
  }

  @Test
  void onlyCommitteeMembersDeclareAndOutsidersFindNothing() {
    World w = world();
    TestUser outsider = TestUsers.create("member");
    TestUser observer = TestUsers.create("observer");
    declare(w, w.admin(), Map.of("has_conflict", false)).expectStatus().isForbidden();
    declare(w, observer, Map.of("has_conflict", false)).expectStatus().isForbidden();
    declare(w, outsider, Map.of("has_conflict", false)).expectStatus().isNotFound();
    mine(w, outsider).expectStatus().isNotFound();
    web.post()
        .uri("/tender-vendors/" + UUID.randomUUID() + "/declaration")
        .header("Authorization", "Bearer " + tokenFor(w.member()))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("has_conflict", false))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  /** The file list must exist before the declaration, or the member has no Open button (PG-13). */
  @Test
  void theFileRowsAreListedBeforeTheDeclarationWithoutTheirNamesOrStatus() {
    World w = world();
    UUID doc =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'proposal', 'P') RETURNING id",
                w.tender());
    UUID dv = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format, size_bytes,"
            + " sha256, storage_key, status, uploaded_by) VALUES (?, ?, 1, 'secret-name.pdf', 'pdf', 10,"
            + " repeat('a', 64), ?, 'uploading', ?)",
        dv,
        doc,
        "originals/%s/%s/%s.pdf".formatted(w.tender(), doc, dv),
        w.admin().id());
    UUID pv =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by) VALUES (?, 1, ?) RETURNING id",
                w.vendor(),
                w.admin().id());
    TestUsers.sql(
        "INSERT INTO proposal_version_files (proposal_version_id, document_version_id, file_role)"
            + " VALUES (?, ?, 'main')",
        pv,
        dv);
    var list =
        (java.util.function.Supplier<
                org.springframework.test.web.reactive.server.WebTestClient.BodyContentSpec>)
            () ->
                web.get()
                    .uri("/tender-vendors/" + w.vendor() + "/proposal-versions")
                    .header("Authorization", "Bearer " + tokenFor(w.member()))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody();

    // before: the row and its id are there, so the screen can offer Open; name and status are not
    list.get()
        .jsonPath("$[0].files.length()")
        .isEqualTo(1)
        .jsonPath("$[0].files[0].document_version_id")
        .isEqualTo(dv.toString())
        .jsonPath("$[0].files[0].role")
        .isEqualTo("main")
        .jsonPath("$[0].files[0].file_name")
        .isEqualTo("Main file")
        .jsonPath("$[0].files[0].status")
        .doesNotExist();

    declare(w, w.member(), Map.of("has_conflict", false)).expectStatus().isCreated();

    // after: the real name and status
    list.get()
        .jsonPath("$[0].files[0].file_name")
        .isEqualTo("secret-name.pdf")
        .jsonPath("$[0].files[0].status")
        .isEqualTo("uploading");
  }
}
