package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** UX-REQ-13, UX-REQ-14, UX-SCO-01..04: pre-check, confirm, reopen and the scoring model. */
class BaselineApiIT extends AbstractApiIT {

  private record World(
      TestUser admin, TestUser chair, TestUser member, UUID tender, UUID category) {}

  private World world() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser member = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, member);
    TestUsers.sql("UPDATE tenders SET status = 'requirements' WHERE id = ?", tender);
    UUID category =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO requirement_categories (name) VALUES (?) RETURNING id",
                "Cat " + UUID.randomUUID());
    return new World(admin, chair, member, tender, category);
  }

  private UUID requirement(World w, String state) {
    return (UUID)
        TestUsers.scalar(
            "INSERT INTO requirements (tender_id, text, category_id, importance, origin, source_item_id,"
                + " review_state) VALUES (?, 'The supplier shall keep logs.', ?, 'mandatory', 'ai', ?, ?)"
                + " RETURNING id",
            w.tender(),
            w.category(),
            UUID.randomUUID(),
            state);
  }

  @Test
  void theChairConfirmsOnlyWhenEverythingIsReadyAndTheListIsSnapshotted() {
    World w = world();
    UUID r1 = requirement(w, "reviewed");
    UUID r2 = requirement(w, "unreviewed");
    UUID flagged = requirement(w, "reviewed");
    TestUsers.sql(
        "INSERT INTO requirement_flags (requirement_id, flag_type) VALUES (?, 'possible_duplicate')",
        flagged);

    get("/tenders/" + w.tender() + "/baselines/precheck", w.chair())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.ready")
        .isEqualTo(false)
        .jsonPath("$.failures.length()")
        .isEqualTo(2)
        .jsonPath("$.counts.total")
        .isEqualTo(3)
        .jsonPath("$.next_version_no")
        .isEqualTo(1);
    // a refused confirmation names what is missing and changes nothing
    post("/tenders/" + w.tender() + "/baselines", w.chair())
        .header("Idempotency-Key", "confirm-" + UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("precheck_failed")
        .jsonPath("$.failures[0].code")
        .isEqualTo("unreviewed");
    assertThat(TestUsers.scalar("SELECT count(*) FROM baselines WHERE tender_id = ?", w.tender()))
        .isEqualTo(0L);

    TestUsers.sql("UPDATE requirements SET review_state = 'reviewed' WHERE id = ?", r2);
    TestUsers.sql(
        "UPDATE requirement_flags SET resolved_at = now(), resolved_by = ? WHERE requirement_id = ?",
        w.chair().id(),
        flagged);

    // a member who is not the Chair is refused
    post("/tenders/" + w.tender() + "/baselines", w.member())
        .header("Idempotency-Key", "confirm-" + UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isForbidden();

    String key = "confirm-" + UUID.randomUUID();
    post("/tenders/" + w.tender() + "/baselines", w.chair())
        .header("Idempotency-Key", key)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.version_no")
        .isEqualTo(1)
        .jsonPath("$.status")
        .isEqualTo("confirmed")
        .jsonPath("$.requirement_count")
        .isEqualTo(3);
    // the same key again replays, it does not confirm a second time
    post("/tenders/" + w.tender() + "/baselines", w.chair())
        .header("Idempotency-Key", key)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectHeader()
        .valueEquals("Idempotent-Replay", "true");
    assertThat(TestUsers.scalar("SELECT count(*) FROM baselines WHERE tender_id = ?", w.tender()))
        .isEqualTo(1L);
    assertThat(TestUsers.scalar("SELECT status FROM tenders WHERE id = ?", w.tender()))
        .isEqualTo("evaluation");
    assertThat(TestUsers.scalar("SELECT review_state FROM requirements WHERE id = ?", r1))
        .isEqualTo("confirmed");
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM baseline_requirements br JOIN baselines b ON b.id = br.baseline_id"
                    + " WHERE b.tender_id = ?",
                w.tender()))
        .isEqualTo(3L);

    get("/tenders/" + w.tender() + "/baselines/1/requirements", w.member())
        .exchange()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(3)
        .jsonPath("$.items[0].display_no")
        .isEqualTo("REQ-001");
    get("/tenders/" + w.tender() + "/baselines", w.member())
        .exchange()
        .expectBody()
        .jsonPath("$[0].confirmed_by.id")
        .isEqualTo(w.chair().id().toString());
  }

  @Test
  void reopeningNeedsTheChairAndAReasonAndTheNextConfirmationIsVersionTwo() {
    World w = world();
    UUID r = requirement(w, "reviewed");
    post("/tenders/" + w.tender() + "/baselines", w.chair())
        .header("Idempotency-Key", "k-" + UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isCreated();

    post("/tenders/" + w.tender() + "/baselines/current/reopen", w.member())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("reason", "A scope change from the sponsor"))
        .exchange()
        .expectStatus()
        .isForbidden();
    post("/tenders/" + w.tender() + "/baselines/current/reopen", w.chair())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("reason", "short"))
        .exchange()
        .expectStatus()
        .is4xxClientError();
    post("/tenders/" + w.tender() + "/baselines/current/reopen", w.chair())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("reason", "A scope change from the sponsor"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("reopened")
        .jsonPath("$.reopen_reason")
        .isEqualTo("A scope change from the sponsor");
    assertThat(TestUsers.scalar("SELECT status FROM tenders WHERE id = ?", w.tender()))
        .isEqualTo("requirements");

    // editable again; a change shows in the impact preview
    String etag =
        get("/requirements/" + r, w.member())
            .exchange()
            .expectBody()
            .returnResult()
            .getResponseHeaders()
            .getETag();
    patch("/requirements/" + r, w.member())
        .header("If-Match", etag)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            Map.of(
                "note",
                "Tightened after the change",
                "text",
                "The supplier shall keep tamper-proof logs."))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.review_state")
        .isEqualTo("reviewed");
    get("/tenders/" + w.tender() + "/baselines/current/impact", w.chair())
        .exchange()
        .expectBody()
        .jsonPath("$.changed_requirements")
        .isEqualTo(1);

    post("/tenders/" + w.tender() + "/baselines", w.chair())
        .header("Idempotency-Key", "k-" + UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.version_no")
        .isEqualTo(2);
    assertThat(
            TestUsers.scalar(
                "SELECT status FROM baselines WHERE tender_id = ? AND version_no = 1", w.tender()))
        .isEqualTo("superseded");
    // version one still holds the text it was confirmed with
    get("/tenders/" + w.tender() + "/baselines/1/requirements", w.chair())
        .exchange()
        .expectBody()
        .jsonPath("$.items[0].text")
        .isEqualTo("The supplier shall keep logs.");
  }

  @Test
  void theScoringModelIsTheChairsAndLockedWithTheBaseline() {
    World w = world();
    var first =
        get("/tenders/" + w.tender() + "/scoring-model", w.member())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.points_fully")
            .isEqualTo(1.0)
            .jsonPath("$.points_partially")
            .isEqualTo(0.5)
            .jsonPath("$.locked")
            .isEqualTo(false)
            .returnResult();
    assertThat(first.getResponseHeaders().getETag()).isEqualTo("\"0\"");

    Map<String, Object> body =
        Map.of(
            "points_fully", 3,
            "points_partially", 1,
            "points_non", 0,
            "weights", List.of(Map.of("category_id", w.category(), "weight", 2)));
    put("/tenders/" + w.tender() + "/scoring-model", w.member(), "\"0\"", body)
        .expectStatus()
        .isForbidden();
    put("/tenders/" + w.tender() + "/scoring-model", w.chair(), "\"0\"", body)
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("ETag", "\"1\"")
        .expectBody()
        .jsonPath("$.points_fully")
        .isEqualTo(3.0)
        .jsonPath("$.weights[0].weight")
        .isEqualTo(2.0);
    // a stale version is refused
    put("/tenders/" + w.tender() + "/scoring-model", w.chair(), "\"0\"", body)
        .expectStatus()
        .isEqualTo(412);
    // points may not rise as the grade falls
    put(
            "/tenders/" + w.tender() + "/scoring-model",
            w.chair(),
            "\"1\"",
            Map.of("points_fully", 1, "points_partially", 2, "points_non", 0, "weights", List.of()))
        .expectStatus()
        .isEqualTo(422);

    requirement(w, "reviewed");
    post("/tenders/" + w.tender() + "/baselines", w.chair())
        .header("Idempotency-Key", "s-" + UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isCreated();
    get("/tenders/" + w.tender() + "/scoring-model", w.member())
        .exchange()
        .expectBody()
        .jsonPath("$.locked")
        .isEqualTo(true)
        .jsonPath("$.points_fully")
        .isEqualTo(3.0);
    put("/tenders/" + w.tender() + "/scoring-model", w.chair(), "\"1\"", body)
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("baseline_locked");
  }

  private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec put(
      String uri, TestUser user, String ifMatch, Map<String, Object> body) {
    return web.put()
        .uri(uri)
        .header("Authorization", "Bearer " + tokenFor(user))
        .header("If-Match", ifMatch)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }
}
