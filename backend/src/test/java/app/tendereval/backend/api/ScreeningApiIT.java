package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * UX-SCR-01..07, PG-14: checklist, checks, recommendations, the Chair's decision, reinstatement.
 */
class ScreeningApiIT extends AbstractApiIT {

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
}
