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

/** UX-REQ-01..14: the shared list, its edits, conflicts, bulk, merge, split and history. */
class RequirementsApiIT extends AbstractApiIT {

  private record World(
      TestUser admin, TestUser chair, TestUser member, UUID tender, UUID category) {}

  private World world() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser member = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, member);
    UUID category =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO requirement_categories (name) VALUES (?) RETURNING id",
                "Security " + UUID.randomUUID());
    return new World(admin, chair, member, tender, category);
  }

  private UUID ai(World w, String text) {
    UUID id =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO requirements (tender_id, text, category_id, importance, origin,"
                    + " source_item_id) VALUES (?, ?, ?, 'mandatory', 'ai', ?) RETURNING id",
                w.tender(),
                text,
                w.category(),
                UUID.randomUUID());
    return id;
  }

  private String body(
      org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec spec) {
    return new String(spec.expectBody().returnResult().getResponseBody());
  }

  @Test
  void membersListFilterAndSearchTheSharedList() {
    World w = world();
    UUID a = ai(w, "The supplier shall encrypt all data at rest.");
    UUID b = ai(w, "The supplier shall provide monthly availability reports.");
    TestUsers.sql("UPDATE requirements SET importance = 'optional' WHERE id = ?", b);
    TestUsers.sql(
        "INSERT INTO requirement_flags (requirement_id, flag_type, detail) VALUES (?, 'compound', 'two duties')",
        a);

    get("/tenders/" + w.tender() + "/requirements", w.member())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(2)
        .jsonPath("$.items[0].display_no")
        .isEqualTo("REQ-001")
        .jsonPath("$.items[0].flags[0].flag_type")
        .isEqualTo("compound")
        .jsonPath("$.items[0].category.id")
        .isEqualTo(w.category().toString())
        .jsonPath("$.items[0].actions.edit.allowed")
        .isEqualTo(true);
    get("/tenders/" + w.tender() + "/requirements?importance=optional", w.member())
        .exchange()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(1);
    get("/tenders/" + w.tender() + "/requirements?q=encrypt", w.member())
        .exchange()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(1);
    get("/tenders/" + w.tender() + "/requirements?flagged=true", w.member())
        .exchange()
        .expectBody()
        .jsonPath("$.items[0].id")
        .isEqualTo(a.toString());
    get("/tenders/" + w.tender() + "/requirements/summary", w.member())
        .exchange()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(2)
        .jsonPath("$.mandatory")
        .isEqualTo(1)
        .jsonPath("$.flagged")
        .isEqualTo(1)
        .jsonPath("$.reviewed")
        .isEqualTo(0);
    // an outsider sees nothing, and an administrator only reads
    get("/tenders/" + w.tender() + "/requirements", TestUsers.create("member"))
        .exchange()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(0);
    get("/tenders/" + w.tender() + "/requirements", w.admin())
        .exchange()
        .expectBody()
        .jsonPath("$.items[0].actions.edit.allowed")
        .isEqualTo(false);
  }

  @Test
  void anEditNeedsTheVersionAndANoteAndTwoEditorsGetAClearConflict() {
    World w = world();
    UUID id = ai(w, "The supplier shall log every administrative action.");
    String etag =
        get("/requirements/" + id, w.member())
            .exchange()
            .expectBody()
            .returnResult()
            .getResponseHeaders()
            .getETag();

    // no If-Match: 428
    patch("/requirements/" + id, w.member())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("note", "Clarified the wording", "text", "Changed text"))
        .exchange()
        .expectStatus()
        .isEqualTo(428);
    // a note shorter than the minimum is refused
    patch("/requirements/" + id, w.member())
        .header("If-Match", etag)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("note", "short", "text", "Changed text"))
        .exchange()
        .expectStatus()
        .isEqualTo(422);

    // the first editor saves
    patch("/requirements/" + id, w.chair())
        .header("If-Match", etag)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            Map.of(
                "note",
                "Clarified the wording",
                "text",
                "The supplier shall log all admin actions."))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.review_state")
        .isEqualTo("reviewed")
        .jsonPath("$.ai_original.text")
        .isEqualTo("The supplier shall log every administrative action.")
        .jsonPath("$.last_changed_by.id")
        .isEqualTo(w.chair().id().toString());

    // the second editor still holds the old version: 412 with who, when and what
    patch("/requirements/" + id, w.member())
        .header("If-Match", etag)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("note", "Made it shorter please", "text", "Log admin actions."))
        .exchange()
        .expectStatus()
        .isEqualTo(412)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("precondition_failed")
        .jsonPath("$.current.text")
        .isEqualTo("The supplier shall log all admin actions.")
        .jsonPath("$.changed_by.id")
        .isEqualTo(w.chair().id().toString())
        .jsonPath("$.changed_fields[0]")
        .isEqualTo("text");
  }

  @Test
  void deleteNeedsAReasonAndRestoreBringsItBack() {
    World w = world();
    UUID keep = ai(w, "The supplier shall support SSO.");
    UUID dup = ai(w, "The supplier shall support single sign-on.");

    post("/requirements/" + dup + "/delete", w.member())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("reason", "duplicate", "note", "Same as the other one"))
        .exchange()
        .expectStatus()
        .isEqualTo(422); // a duplicate names the requirement that is kept
    post("/requirements/" + dup + "/delete", w.member())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            Map.of("reason", "duplicate", "duplicate_of_id", keep, "note", "Same as REQ-001"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.deleted")
        .isEqualTo(true)
        .jsonPath("$.duplicate_of_id")
        .isEqualTo(keep.toString());
    get("/tenders/" + w.tender() + "/requirements", w.member())
        .exchange()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(1);
    get("/tenders/" + w.tender() + "/requirements?deleted=true", w.member())
        .exchange()
        .expectBody()
        .jsonPath("$.items[0].id")
        .isEqualTo(dup.toString());
    post("/requirements/" + dup + "/restore", w.member())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("note", "Kept after all, different scope"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.deleted")
        .isEqualTo(false);
    // history carries every event with its note
    get("/requirements/" + dup + "/history", w.member())
        .exchange()
        .expectBody()
        .jsonPath("$.events.length()")
        .isEqualTo(2)
        .jsonPath("$.events[0].action")
        .isEqualTo("requirement_restored")
        .jsonPath("$.events[1].note")
        .isEqualTo("Same as REQ-001");
  }

  @Test
  void bulkMarksRowsAndNamesTheOnesItSkipped() {
    World w = world();
    UUID a = ai(w, "Requirement one is long enough.");
    UUID b = ai(w, "Requirement two is long enough.");
    UUID gone = ai(w, "Requirement three is long enough.");
    TestUsers.sql("UPDATE requirements SET review_state = 'reviewed' WHERE id = ?", b);
    TestUsers.sql(
        "UPDATE requirements SET deleted_at = now(), delete_reason = 'other' WHERE id = ?", gone);

    post("/tenders/" + w.tender() + "/requirements/bulk", w.member())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            Map.of(
                "ids",
                List.of(a, b, gone, UUID.randomUUID()),
                "action",
                "mark_reviewed",
                "note",
                "Reviewed together"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.updated")
        .isEqualTo(1)
        .jsonPath("$.skipped.length()")
        .isEqualTo(3);
    assertThat(TestUsers.scalar("SELECT review_state FROM requirements WHERE id = ?", a))
        .isEqualTo("reviewed");
    // one audit event for the updated row, carrying the batch
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM audit_events WHERE object_id = ? AND action = 'requirement_reviewed'"
                    + " AND batch_id IS NOT NULL",
                a))
        .isEqualTo(1L);
  }

  @Test
  void mergeAndSplitKeepTheSourcesAndLinkOldToNew() {
    World w = world();
    UUID a = ai(w, "Passwords shall be at least twelve characters.");
    UUID b = ai(w, "Passwords shall contain no dictionary words.");
    Map<String, Object> part =
        Map.of(
            "text",
            "Passwords shall be twelve characters or longer and contain no dictionary words.",
            "category_id",
            w.category(),
            "importance",
            "mandatory",
            "acceptance_criteria",
            "");
    byte[] merged =
        post("/tenders/" + w.tender() + "/requirements/merge", w.member())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                Map.of(
                    "requirement_ids", List.of(a, b), "result", part, "note", "One password rule"))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody()
            .jsonPath("$.display_no")
            .isEqualTo("REQ-003")
            .returnResult()
            .getResponseBody();
    UUID mergedId = UUID.fromString(JsonPath.read(new String(merged), "$.id"));
    assertThat(
            TestUsers.scalar("SELECT merged_into_requirement_id FROM requirements WHERE id = ?", a))
        .isEqualTo(mergedId);
    assertThat(TestUsers.scalar("SELECT deleted_at IS NOT NULL FROM requirements WHERE id = ?", b))
        .isEqualTo(true);

    post("/requirements/" + mergedId + "/split", w.member())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            Map.of(
                "parts",
                List.of(
                    Map.of(
                        "text",
                        "Passwords shall be twelve characters or longer.",
                        "category_id",
                        w.category(),
                        "importance",
                        "mandatory",
                        "acceptance_criteria",
                        ""),
                    Map.of(
                        "text",
                        "Passwords shall contain no dictionary words.",
                        "category_id",
                        w.category(),
                        "importance",
                        "optional",
                        "acceptance_criteria",
                        "")),
                "note",
                "Two separate obligations"))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.length()")
        .isEqualTo(2)
        .jsonPath("$[0].display_no")
        .isEqualTo("REQ-004");
  }

  @Test
  void aConfirmedBaselineLocksTheList() {
    World w = world();
    UUID a = ai(w, "A requirement that will be locked.");
    TestUsers.sql("UPDATE requirements SET review_state = 'reviewed' WHERE id = ?", a);
    TestUsers.sql(
        "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by) VALUES (?, 1, 1, ?)",
        w.tender(),
        w.chair().id());
    String etag =
        get("/requirements/" + a, w.member())
            .exchange()
            .expectBody()
            .returnResult()
            .getResponseHeaders()
            .getETag();
    patch("/requirements/" + a, w.member())
        .header("If-Match", etag)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("note", "Trying after the lock", "text", "Changed"))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("baseline_locked");
    get("/requirements/" + a, w.member())
        .exchange()
        .expectBody()
        .jsonPath("$.actions.edit.allowed")
        .isEqualTo(false)
        .jsonPath("$.actions.edit.reason")
        .value(r -> assertThat((String) r).contains("baseline is confirmed"));
  }

  @Test
  void aMemberAddsARequirementAndFlagsAreResolvedNotDeleted() {
    World w = world();
    String created =
        body(
            post("/tenders/" + w.tender() + "/requirements", w.member())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    Map.of(
                        "text", "The supplier shall provide an exit plan.",
                        "category_id", w.category(),
                        "importance", "mandatory",
                        "acceptance_criteria", "A written plan within 30 days"))
                .exchange()
                .expectStatus()
                .isCreated());
    assertThat((String) JsonPath.read(created, "$.origin")).isEqualTo("manual");
    assertThat((String) JsonPath.read(created, "$.display_no")).isEqualTo("REQ-001");

    UUID r = ai(w, "A flagged requirement for the test.");
    UUID flag =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO requirement_flags (requirement_id, flag_type) VALUES (?, 'possible_duplicate') RETURNING id",
                r);
    post("/requirements/" + r + "/flags/" + flag + "/resolve", w.member())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("note", "Checked, they differ"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.flags[0].resolved_at")
        .isNotEmpty();
    assertThat(TestUsers.scalar("SELECT count(*) FROM requirement_flags WHERE id = ?", flag))
        .isEqualTo(1L);
  }

  @Test
  void onlyTheAdministratorManagesCategories() {
    World w = world();
    String name = "Hosting " + UUID.randomUUID();
    post("/categories", w.member())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("name", name))
        .exchange()
        .expectStatus()
        .isForbidden();
    post("/categories", w.admin())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("name", name, "sort_order", 3))
        .exchange()
        .expectStatus()
        .isCreated();
    get("/categories", w.member())
        .exchange()
        .expectBody()
        .jsonPath("$[?(@.name=='" + name + "')].sort_order")
        .isEqualTo(3);
  }
}
