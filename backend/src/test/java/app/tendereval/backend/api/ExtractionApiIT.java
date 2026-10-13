package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import io.nats.client.JetStream;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** UX-EXT-02..07: starting an extraction, its scope rules, overlap and coverage. */
class ExtractionApiIT extends AbstractApiIT {

  @Autowired JetStream js;

  @BeforeEach
  void publishWorks() throws Exception {
    when(js.publish(any(Message.class), any(PublishOptions.class)))
        .thenReturn(mock(PublishAck.class));
    clearInvocations(js);
  }

  private record World(
      TestUser admin,
      TestUser chair,
      TestUser member,
      UUID tender,
      UUID version,
      UUID s1,
      UUID s2) {}

  private World world(String tenderStatus) {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser member = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, member);
    if (!"draft".equals(tenderStatus)) {
      TestUsers.sql("UPDATE tenders SET status = ? WHERE id = ?", tenderStatus, tender);
    }
    UUID doc =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'rfp', 'RFP') RETURNING id",
                tender);
    UUID v = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format, size_bytes,"
            + " sha256, storage_key, status, uploaded_by) VALUES (?, ?, 1, 'rfp.pdf', 'pdf', 10,"
            + " repeat('a', 64), ?, 'uploading', ?)",
        v,
        doc,
        "originals/%s/%s/%s.pdf".formatted(tender, doc, v),
        admin.id());
    TestUsers.sql("UPDATE document_versions SET status = 'processing' WHERE id = ?", v);
    TestUsers.sql("UPDATE document_versions SET status = 'ready', page_count = 20 WHERE id = ?", v);
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO document_sections (id, document_version_id, title, level, sort_order, start_page, end_page)"
            + " VALUES (?, ?, 'Scope', 1, 0, 1, 8), (?, ?, 'Security', 1, 1, 9, 20)",
        s1,
        v,
        s2,
        v);
    return new World(admin, chair, member, tender, v, s1, s2);
  }

  private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec start(
      World w, TestUser user, Map<String, Object> body, String key) {
    return web.post()
        .uri("/tenders/" + w.tender() + "/extractions")
        .header("Authorization", "Bearer " + tokenFor(user))
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  private Map<String, Object> scope(World w, UUID... sections) {
    return Map.of(
        "scope",
        List.of(Map.of("document_version_id", w.version(), "section_ids", List.of(sections))));
  }

  @Test
  void aMemberStartsAnExtractionAndTheCommandIsPublishedAfterTheCommit() throws Exception {
    World w = world("requirements");
    byte[] out =
        start(w, w.member(), scope(w, w.s1()), "x-" + UUID.randomUUID())
            .expectStatus()
            .isAccepted()
            .expectHeader()
            .exists("Location")
            .expectBody()
            .jsonPath("$.job_type")
            .isEqualTo("requirement_extraction")
            .jsonPath("$.status")
            .isEqualTo("queued")
            .jsonPath("$.can_cancel")
            .isEqualTo(true)
            .returnResult()
            .getResponseBody();
    String job = JsonPath.read(new String(out), "$.id");
    assertThat(TestUsers.scalar("SELECT count(*) FROM job_scope_items WHERE job_id = ?::uuid", job))
        .isEqualTo(1L);

    ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
    verify(js, atLeastOnce()).publish(sent.capture(), any(PublishOptions.class));
    Message m =
        sent.getAllValues().stream()
            .filter(x -> new String(x.getData()).contains(job))
            .findFirst()
            .orElseThrow();
    assertThat(m.getSubject()).isEqualTo("documents.analysis.requested.requirement_extraction");
    String body = new String(m.getData());
    assertThat(JsonPath.<String>read(body, "$.job_type")).isEqualTo("requirement_extraction");
    assertThat(JsonPath.<String>read(body, "$.target.type")).isEqualTo("tender");
    assertThat(JsonPath.<String>read(body, "$.input.scope[0].document_version_id"))
        .isEqualTo(w.version().toString());
    assertThat(JsonPath.<String>read(body, "$.input.scope[0].section_id"))
        .isEqualTo(w.s1().toString());
  }

  @Test
  void theRulesOfTheTenderStageAndTheScopeAreEnforced() {
    World draft = world("draft");
    start(draft, draft.member(), scope(draft, draft.s1()), "k-" + UUID.randomUUID())
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("wrong_stage");

    World w = world("requirements");
    // not a member of the committee
    start(w, TestUsers.create("member"), scope(w, w.s1()), "k-" + UUID.randomUUID())
        .expectStatus()
        .is4xxClientError();
    // a section of another document, a page outside the document, a version that is not there
    start(
            w,
            w.member(),
            Map.of("scope", List.of(Map.of("document_version_id", UUID.randomUUID()))),
            "k-" + UUID.randomUUID())
        .expectStatus()
        .isEqualTo(422);
    start(
            w,
            w.member(),
            Map.of(
                "scope",
                List.of(Map.of("document_version_id", w.version(), "page_from", 5, "page_to", 99))),
            "k-" + UUID.randomUUID())
        .expectStatus()
        .isEqualTo(422);
    // an Idempotency-Key is required
    web.post()
        .uri("/tenders/" + w.tender() + "/extractions")
        .header("Authorization", "Bearer " + tokenFor(w.member()))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(scope(w, w.s1()))
        .exchange()
        .expectStatus()
        .is4xxClientError();
  }

  @Test
  void anOverlapWithARunningExtractionIsRefusedUnlessAcknowledged() {
    World w = world("requirements");
    start(w, w.member(), scope(w, w.s1()), "a-" + UUID.randomUUID()).expectStatus().isAccepted();
    // the same section again, and a page range inside it: overlap
    start(w, w.chair(), scope(w, w.s1()), "b-" + UUID.randomUUID())
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("job_overlap");
    start(
            w,
            w.chair(),
            Map.of(
                "scope",
                List.of(Map.of("document_version_id", w.version(), "page_from", 2, "page_to", 4))),
            "c-" + UUID.randomUUID())
        .expectStatus()
        .isEqualTo(409);
    // another section does not overlap
    start(w, w.chair(), scope(w, w.s2()), "d-" + UUID.randomUUID()).expectStatus().isAccepted();
    // acknowledged
    start(
            w,
            w.chair(),
            Map.of(
                "scope",
                List.of(Map.of("document_version_id", w.version(), "section_ids", List.of(w.s1()))),
                "acknowledge_overlap",
                true),
            "e-" + UUID.randomUUID())
        .expectStatus()
        .isAccepted();
  }

  @Test
  void theSameKeyReplaysAndCoverageShowsWhoIsExtractingWhat() {
    World w = world("requirements");
    String key = "same-" + UUID.randomUUID();
    start(w, w.member(), scope(w, w.s1()), key).expectStatus().isAccepted();
    start(w, w.member(), scope(w, w.s1()), key)
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals("Idempotent-Replay", "true");
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM jobs WHERE tender_id = ? AND job_type = 'requirement_extraction'",
                w.tender()))
        .isEqualTo(1L);

    get("/tenders/" + w.tender() + "/extractions/coverage", w.chair())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].document_version_id")
        .isEqualTo(w.version().toString())
        .jsonPath("$.items[0].section_id")
        .isEqualTo(w.s1().toString())
        .jsonPath("$.items[0].status")
        .isEqualTo("running")
        .jsonPath("$.items[0].by.id")
        .isEqualTo(w.member().id().toString());
  }
}
