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
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * UX-SCR-01..07, PG-14: checklist, checks, recommendations, the Chair's decision, reinstatement.
 */
class ScreeningApiIT extends AbstractApiIT {

  @Autowired JetStream js;

  @BeforeEach
  void publishWorks() throws Exception {
    when(js.publish(any(Message.class), any(PublishOptions.class)))
        .thenReturn(mock(PublishAck.class));
    clearInvocations(js);
  }

  private record World(
      TestUser admin, TestUser chair, TestUser member, UUID tender, UUID tv, UUID pv) {}

  private World world() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser member = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, member);
    TestUsers.sql("UPDATE tenders SET status = 'requirements' WHERE id = ?", tender);
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
    UUID pv =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by) VALUES (?, 1, ?) RETURNING id",
                tv,
                admin.id());
    return new World(admin, chair, member, tender, tv, pv);
  }

  private static void declare(World w, TestUser u) {
    TestUsers.sql(
        "INSERT INTO conflict_declarations (tender_vendor_id, user_id, has_conflict) VALUES (?, ?, false)",
        w.tv(),
        u.id());
  }

  private WebTestClient.RequestBodySpec call(String method, String uri, TestUser u) {
    return web.method(org.springframework.http.HttpMethod.valueOf(method))
        .uri(uri)
        .header("Authorization", "Bearer " + tokenFor(u))
        .contentType(MediaType.APPLICATION_JSON);
  }

  @Test
  void theChecklistHasFiveDefaultsAndTheChairEditsItOnlyBeforeScreeningStarts() {
    World w = world();
    String uri = "/tenders/" + w.tender() + "/screening/checklist";
    byte[] out =
        call("GET", uri, w.member())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult()
            .getResponseBody();
    List<Map<String, Object>> items = JsonPath.read(new String(out), "$");
    assertThat(items).hasSize(5);
    String first = (String) items.get(0).get("id");

    call("PUT", uri, w.member())
        .bodyValue(Map.of("items", List.of(Map.of("label", "Mine"))))
        .exchange()
        .expectStatus()
        .isForbidden();
    call("PUT", uri, w.chair())
        .bodyValue(
            Map.of(
                "items",
                List.of(
                    Map.of(
                        "id",
                        first,
                        "label",
                        "All mandatory documents present",
                        "is_active",
                        false),
                    Map.of("label", "Written in plain language"))))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.length()")
        .isEqualTo(6);

    // a member's check starts screening: the checklist locks
    declare(w, w.member());
    call("PUT", "/tender-vendors/" + w.tv() + "/screening/checks", w.member())
        .bodyValue(
            Map.of("checks", List.of(Map.of("checklist_item_id", first, "is_checked", true))))
        .exchange()
        .expectStatus()
        .isOk();
    call("PUT", uri, w.chair())
        .bodyValue(Map.of("items", List.of(Map.of("id", first, "label", "Changed after start"))))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("checklist_locked");
  }

  @Test
  void aMemberMustHaveDeclaredBeforeScreeningAndTheirChecksAreTheirOwn() {
    World w = world();
    String checks = "/tender-vendors/" + w.tv() + "/screening/checks";
    String item =
        JsonPath.read(
            new String(
                call("GET", "/tenders/" + w.tender() + "/screening/checklist", w.member())
                    .exchange()
                    .expectBody()
                    .returnResult()
                    .getResponseBody()),
            "$[0].id");
    Map<String, Object> body =
        Map.of("checks", List.of(Map.of("checklist_item_id", item, "is_checked", true)));
    call("PUT", checks, w.member())
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("declaration_required");
    declare(w, w.member());
    declare(w, w.chair());
    call("PUT", checks, w.member())
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.checklist[0].my_check")
        .isEqualTo(true)
        .jsonPath("$.proposal_version.status")
        .isEqualTo("in_screening");
    // the Chair sees their own marks, not the member's
    call("GET", "/tender-vendors/" + w.tv() + "/screening", w.chair())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.checklist[0].my_check")
        .isEqualTo(false);
    // the administrator and an outsider do not screen
    call("PUT", checks, w.admin()).bodyValue(body).exchange().expectStatus().isForbidden();
    call("PUT", checks, TestUsers.create("member"))
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void recommendationsNeedAReasonToRejectAndAreVisibleToTheCommittee() {
    World w = world();
    declare(w, w.member());
    declare(w, w.chair());
    String uri = "/tender-vendors/" + w.tv() + "/screening/recommendation";
    call("PUT", uri, w.member())
        .bodyValue(Map.of("recommendation", "reject"))
        .exchange()
        .expectStatus()
        .isEqualTo(422);
    call("PUT", uri, w.member())
        .bodyValue(Map.of("recommendation", "reject", "reason", "The mandatory forms are missing"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.my_recommendation.recommendation")
        .isEqualTo("reject");
    call("GET", "/tender-vendors/" + w.tv() + "/screening", w.chair())
        .exchange()
        .expectBody()
        .jsonPath("$.recommendations.length()")
        .isEqualTo(1)
        .jsonPath("$.my_recommendation")
        .isEmpty();
  }

  @Test
  void onlyTheChairDecidesWithAReasonAndAcceptMovesTheVendorOnAndReplaysWithTheSameKey() {
    World w = world();
    declare(w, w.member());
    declare(w, w.chair());
    String uri = "/tender-vendors/" + w.tv() + "/screening/decision";
    // not yet in screening
    call("POST", uri, w.chair())
        .header("Idempotency-Key", "d-" + UUID.randomUUID())
        .bodyValue(Map.of("decision", "accept"))
        .exchange()
        .expectStatus()
        .isEqualTo(409);
    call("PUT", "/tender-vendors/" + w.tv() + "/screening/recommendation", w.member())
        .bodyValue(Map.of("recommendation", "accept"))
        .exchange()
        .expectStatus()
        .isOk();
    call("POST", uri, w.member())
        .header("Idempotency-Key", "d-" + UUID.randomUUID())
        .bodyValue(Map.of("decision", "accept"))
        .exchange()
        .expectStatus()
        .isForbidden();
    call("POST", uri, w.chair())
        .header("Idempotency-Key", "d-" + UUID.randomUUID())
        .bodyValue(Map.of("decision", "reject", "reason", "short"))
        .exchange()
        .expectStatus()
        .isEqualTo(422);
    String key = "d-" + UUID.randomUUID();
    call("POST", uri, w.chair())
        .header("Idempotency-Key", key)
        .bodyValue(Map.of("decision", "accept"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.proposal_version.status")
        .isEqualTo("accepted")
        .jsonPath("$.decision.decision")
        .isEqualTo("accept");
    call("POST", uri, w.chair())
        .header("Idempotency-Key", key)
        .bodyValue(Map.of("decision", "accept"))
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM screening_decisions WHERE proposal_version_id = ?", w.pv()))
        .isEqualTo(1L);
    // screening is closed for members now
    call("PUT", "/tender-vendors/" + w.tv() + "/screening/recommendation", w.member())
        .bodyValue(Map.of("recommendation", "reject", "reason", "second thoughts"))
        .exchange()
        .expectStatus()
        .isEqualTo(409);
  }

  @Test
  void aRejectedVendorIsReinstatedWithANote() {
    World w = world();
    declare(w, w.chair());
    TestUsers.sql("UPDATE proposal_versions SET status = 'in_screening' WHERE id = ?", w.pv());
    String uri = "/tender-vendors/" + w.tv() + "/screening/decision";
    call("POST", uri, w.chair())
        .header("Idempotency-Key", "d-" + UUID.randomUUID())
        .bodyValue(Map.of("decision", "reject", "reason", "The required certificates are missing"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.proposal_version.status")
        .isEqualTo("rejected");
    String re = "/tender-vendors/" + w.tv() + "/screening/reinstate";
    call("POST", re, w.member())
        .bodyValue(Map.of("note", "Please reconsider this vendor"))
        .exchange()
        .expectStatus()
        .isForbidden();
    call("POST", re, w.chair())
        .bodyValue(Map.of("note", "short"))
        .exchange()
        .expectStatus()
        .isEqualTo(422);
    call("POST", re, w.chair())
        .bodyValue(Map.of("note", "The certificates arrived by post"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.proposal_version.status")
        .isEqualTo("accepted")
        .jsonPath("$.decision.is_reinstatement")
        .isEqualTo(true);
  }

  @Test
  void aClearedMemberStartsAPrecheckAndTheCommandIsPublishedAfterTheCommit() throws Exception {
    World w = world();
    // an RFP and one proposal file, both ready
    UUID rfp = readyDocument(w, "rfp", "RFP");
    UUID file = readyDocument(w, "proposal", "Proposal");
    TestUsers.sql(
        "INSERT INTO proposal_version_files (proposal_version_id, document_version_id, file_role)"
            + " VALUES (?, ?, 'main')",
        w.pv(),
        file);
    String uri = "/tender-vendors/" + w.tv() + "/screening/precheck";
    call("POST", uri, w.member())
        .header("Idempotency-Key", "p-" + UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("declaration_required");
    declare(w, w.member());
    String key = "p-" + UUID.randomUUID();
    byte[] out =
        call("POST", uri, w.member())
            .header("Idempotency-Key", key)
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody()
            .jsonPath("$.job_type")
            .isEqualTo("screening_precheck")
            .jsonPath("$.status")
            .isEqualTo("queued")
            .returnResult()
            .getResponseBody();
    String job = JsonPath.read(new String(out), "$.id");
    ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
    verify(js, atLeastOnce()).publish(sent.capture(), any(PublishOptions.class));
    Message m =
        sent.getAllValues().stream()
            .filter(x -> new String(x.getData()).contains(job))
            .findFirst()
            .orElseThrow();
    assertThat(m.getSubject()).isEqualTo("documents.analysis.requested.screening_precheck");
    String body = new String(m.getData());
    assertThat(JsonPath.<String>read(body, "$.target.type")).isEqualTo("proposal_version");
    assertThat(JsonPath.<String>read(body, "$.target.id")).isEqualTo(w.pv().toString());
    assertThat(JsonPath.<List<String>>read(body, "$.input.rfp_document_version_ids"))
        .containsExactly(rfp.toString());
    assertThat(JsonPath.<List<String>>read(body, "$.input.proposal_document_version_ids"))
        .containsExactly(file.toString());
    // one at a time per proposal
    call("POST", uri, w.member())
        .header("Idempotency-Key", "p-" + UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("job_overlap");
    // a replay of the first key answers with the same job and publishes nothing again
    clearInvocations(js);
    call("POST", uri, w.member())
        .header("Idempotency-Key", key)
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.id")
        .isEqualTo(job);
    verify(js, org.mockito.Mockito.never()).publish(any(Message.class), any(PublishOptions.class));
    // the screening state names the latest run, even before any finding is stored
    call("GET", "/tender-vendors/" + w.tv() + "/screening", w.member())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.precheck_run.job_id")
        .isEqualTo(job)
        .jsonPath("$.precheck_run.status")
        .isEqualTo("queued")
        .jsonPath("$.precheck_run.warnings.length()")
        .isEqualTo(0);
    // a failed pre-check is retried as a new job that points back
    TestUsers.sql("UPDATE jobs SET status = 'running', started_at = now() WHERE id = ?::uuid", job);
    TestUsers.sql(
        "UPDATE jobs SET status = 'failed', failure_code = 'llm_unavailable', finished_at = now()"
            + " WHERE id = ?::uuid",
        job);
    call("POST", "/jobs/" + job + "/retry", w.member())
        .header("Idempotency-Key", "p-" + UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.retry_of_job_id")
        .isEqualTo(job)
        .jsonPath("$.job_type")
        .isEqualTo("screening_precheck");
  }

  private UUID readyDocument(World w, String type, String title) {
    UUID doc =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, ?, ?) RETURNING id",
                w.tender(),
                type,
                title);
    UUID v = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format, size_bytes,"
            + " sha256, storage_key, status, uploaded_by) VALUES (?, ?, 1, 'f.pdf', 'pdf', 10,"
            + " repeat('a', 64), ?, 'uploading', ?)",
        v,
        doc,
        "originals/%s/%s/%s.pdf".formatted(w.tender(), doc, v),
        w.admin().id());
    TestUsers.sql("UPDATE document_versions SET status = 'processing' WHERE id = ?", v);
    TestUsers.sql("UPDATE document_versions SET status = 'ready', page_count = 3 WHERE id = ?", v);
    return v;
  }
}
