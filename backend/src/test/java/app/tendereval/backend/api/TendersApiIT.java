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

/** UX-TND-01 to 06, UX-ROL-02, UX-ROL-04: tenders, committees, the Chair and opening. */
class TendersApiIT extends AbstractApiIT {

  private static String name() {
    return "Tender " + UUID.randomUUID();
  }

  private Map<String, Object> create(String name, TestUser chair, TestUser... others) {
    List<UUID> ids = new java.util.ArrayList<>();
    ids.add(chair.id());
    for (TestUser o : others) {
      ids.add(o.id());
    }
    return Map.of("name", name, "member_ids", ids, "chair_id", chair.id());
  }

  private long audit(String action, Object tender) {
    return ((Number)
            TestUsers.scalar(
                "SELECT count(*) FROM audit_events WHERE action = ? AND tender_id = ?",
                action,
                tender))
        .longValue();
  }

  // ---- create ---------------------------------------------------------------------------------

  @Test
  void anAdministratorCreatesATenderWithACommitteeAndChair() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser member = TestUsers.create("member");
    String name = name();
    var body = new java.util.LinkedHashMap<String, Object>(create(name, chair, member));
    body.put("description", "Cloud platform tender");
    body.put("reference_no", "REF-1");
    body.put("evaluation_due_date", "2027-03-01");
    var result =
        post("/tenders", admin)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated()
            .expectHeader()
            .exists("Location")
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo(name)
            .jsonPath("$.status")
            .isEqualTo("draft")
            .jsonPath("$.committee_size")
            .isEqualTo(2)
            .jsonPath("$.chair.id")
            .isEqualTo(chair.id().toString())
            .jsonPath("$.due_date")
            .isEqualTo("2027-03-01")
            .jsonPath("$.stage_dates[0].stage")
            .isEqualTo("evaluation")
            .jsonPath("$.actions.open.allowed")
            .isEqualTo(false)
            .jsonPath("$.actions.open.reason")
            .isNotEmpty()
            .jsonPath("$.actions.edit.allowed")
            .isEqualTo(true)
            .jsonPath("$.row_version")
            .isEqualTo(1)
            .returnResult();
    UUID id = UUID.fromString(JsonPath.read(new String(result.getResponseBody()), "$.id"));
    assertThat(audit("tender_created", id)).isEqualTo(1);
  }

  @Test
  void creationRulesAreChecked() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser other = TestUsers.create("member");
    TestUser anotherAdmin = TestUsers.create("admin");
    // no members
    post("/tenders", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("name", name(), "member_ids", List.of(), "chair_id", chair.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.errors[?(@.field=='member_ids')]")
        .exists();
    // Chair is not one of the members
    post("/tenders", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            Map.of("name", name(), "member_ids", List.of(other.id()), "chair_id", chair.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.errors[?(@.field=='chair_id')]")
        .exists();
    // an administrator cannot sit on a committee (UX-TND-02)
    post("/tenders", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(create(name(), chair, anotherAdmin))
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.errors[0].code")
        .isEqualTo("wrong_role");
    // unknown user
    post("/tenders", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            Map.of(
                "name", name(), "member_ids", List.of(UUID.randomUUID()), "chair_id", chair.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(422);
    // blank name
    post("/tenders", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(create("   ", chair))
        .exchange()
        .expectStatus()
        .isEqualTo(422);
  }

  @Test
  void aNameCanOnlyBeUsedOnce() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    String name = name();
    post("/tenders", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(create(name, chair))
        .exchange()
        .expectStatus()
        .isCreated();
    post("/tenders", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(create("  " + name.toUpperCase() + " ", chair))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("name_taken");
  }

  @Test
  void onlyTheAdministratorCreatesAndEdits() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair);
    post("/tenders", chair)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(create(name(), chair))
        .exchange()
        .expectStatus()
        .isForbidden();
    patch("/tenders/" + tender, chair)
        .header("If-Match", "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("name", name()))
        .exchange()
        .expectStatus()
        .isForbidden();
    post("/tenders/" + tender + "/open", chair).exchange().expectStatus().isForbidden();
    post("/tenders/" + tender + "/members", chair)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("user_id", UUID.randomUUID()))
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  // ---- reading --------------------------------------------------------------------------------

  @Test
  void aMemberSeesOnlyTheirTendersAndTheAdminSeesAll() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser outsider = TestUsers.create("member");
    String prefix = "Vis" + UUID.randomUUID().toString().substring(0, 6);
    UUID mine =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO tenders (name, created_by) VALUES (?, ?) RETURNING id",
                prefix + " mine",
                admin.id());
    TestUsers.sql(
        "INSERT INTO tender_members (tender_id, user_id, is_chair) VALUES (?, ?, true)",
        mine,
        chair.id());
    TestUsers.sql(
        "INSERT INTO tenders (name, created_by) VALUES (?, ?)", prefix + " other", admin.id());

    get("/tenders?q=" + prefix, chair)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(1)
        .jsonPath("$.items[0].id")
        .isEqualTo(mine.toString());
    get("/tenders?q=" + prefix, outsider).exchange().expectBody().jsonPath("$.total").isEqualTo(0);
    get("/tenders?q=" + prefix, admin).exchange().expectBody().jsonPath("$.total").isEqualTo(2);
    get("/tenders?q=" + prefix + "&mine=true", admin)
        .exchange()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(0);
  }

  @Test
  void aTenderTheCallerMayNotOpenNamesWhoToAsk() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser outsider = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair);
    get("/tenders/" + tender, outsider)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .contentTypeCompatibleWith("application/problem+json")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("no_access")
        .jsonPath("$.access_contacts[0].name")
        .isNotEmpty()
        .jsonPath("$.access_contacts[0].role")
        .isEqualTo("admin");
    get("/tenders/" + UUID.randomUUID(), outsider).exchange().expectStatus().isNotFound();
  }

  @Test
  void membersSeeNamesButOnlyTheAdminSeesEmails() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser member = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, member);
    get("/tenders/" + tender + "/members", member)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].is_chair")
        .isEqualTo(true)
        .jsonPath("$.items[0].email")
        .doesNotExist();
    get("/tenders/" + tender + "/members", admin)
        .exchange()
        .expectBody()
        .jsonPath("$.items[0].email")
        .isEqualTo(chair.email());
  }

  @Test
  void listingSortsPagesAndFilters() {
    TestUser admin = TestUsers.create("admin");
    String prefix = "Pg" + UUID.randomUUID().toString().substring(0, 6);
    for (String n : List.of("A", "B", "C")) {
      TestUsers.sql(
          "INSERT INTO tenders (name, created_by) VALUES (?, ?)", prefix + " " + n, admin.id());
    }
    var first =
        get("/tenders?q=" + prefix + "&sort=name&limit=2", admin)
            .exchange()
            .expectBody()
            .jsonPath("$.items[0].name")
            .isEqualTo(prefix + " A")
            .jsonPath("$.items[1].name")
            .isEqualTo(prefix + " B")
            .jsonPath("$.total")
            .isEqualTo(3)
            .jsonPath("$.next_cursor")
            .isNotEmpty()
            .returnResult();
    String cursor = JsonPath.read(new String(first.getResponseBody()), "$.next_cursor");
    get("/tenders?q=" + prefix + "&sort=name&limit=2&cursor=" + cursor, admin)
        .exchange()
        .expectBody()
        .jsonPath("$.items[0].name")
        .isEqualTo(prefix + " C")
        .jsonPath("$.next_cursor")
        .doesNotExist();
    get("/tenders?q=" + prefix + "&status=finalized", admin)
        .exchange()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(0);
    // default order is most recent activity first
    get("/tenders?q=" + prefix, admin).exchange().expectStatus().isOk();
  }

  // ---- editing --------------------------------------------------------------------------------

  @Test
  void anEditNeedsTheCurrentVersionAndIsAudited() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair);
    String renamed = name();
    patch("/tenders/" + tender, admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("name", renamed))
        .exchange()
        .expectStatus()
        .isEqualTo(428);
    patch("/tenders/" + tender, admin)
        .header("If-Match", "\"7\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("name", renamed))
        .exchange()
        .expectStatus()
        .isEqualTo(412);
    patch("/tenders/" + tender, admin)
        .header("If-Match", "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("name", renamed, "description", "New text"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("ETag", "\"2\"")
        .expectBody()
        .jsonPath("$.name")
        .isEqualTo(renamed)
        .jsonPath("$.description")
        .isEqualTo("New text");
    // the second editor with the old version sees a conflict, never an overwrite
    patch("/tenders/" + tender, admin)
        .header("If-Match", "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("name", name()))
        .exchange()
        .expectStatus()
        .isEqualTo(412);
    assertThat(audit("tender_updated", tender)).isEqualTo(1);
    assertThat(
            TestUsers.scalar(
                "SELECT before ->> 'name' IS NOT NULL AND (after ->> 'name') = ? FROM audit_events"
                    + " WHERE action = 'tender_updated' AND tender_id = ?",
                renamed,
                tender))
        .isEqualTo(true);
  }

  @Test
  void aNameTakenByAnotherTenderIsRefusedOnEdit() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    UUID one = Fixtures.tender(admin, chair);
    UUID two = Fixtures.tender(admin, TestUsers.create("member"));
    String taken = (String) TestUsers.scalar("SELECT name FROM tenders WHERE id = ?", two);
    patch("/tenders/" + one, admin)
        .header("If-Match", "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("name", taken))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("name_taken");
  }

  // ---- opening and archiving
  // ---------------------------------------------------------------------

  @Test
  void aTenderOpensOnceItHasAnRfpAndACommittee() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair);
    get("/tenders/" + tender + "/readiness", admin)
        .exchange()
        .expectBody()
        .jsonPath("$.ready")
        .isEqualTo(false)
        .jsonPath("$.items[?(@.key=='rfp_uploaded')].done")
        .isEqualTo(List.of(false))
        .jsonPath("$.items[?(@.key=='committee_chosen')].done")
        .isEqualTo(List.of(true));
    post("/tenders/" + tender + "/open", admin)
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("not_ready")
        .jsonPath("$.errors[0].field")
        .isEqualTo("rfp_uploaded");

    Fixtures.rfp(tender, admin);
    post("/tenders/" + tender + "/open", admin)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("requirements")
        .jsonPath("$.opened_at")
        .isNotEmpty()
        .jsonPath("$.actions.open.allowed")
        .isEqualTo(false);
    assertThat(audit("tender_opened", tender)).isEqualTo(1);
    post("/tenders/" + tender + "/open", admin)
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("already_opened");
  }

  @Test
  void onlyAFinalizedTenderCanBeArchived() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = Fixtures.tender(admin, TestUsers.create("member"));
    post("/tenders/" + tender + "/archive", admin)
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("not_finished");
    // walk the status through its allowed moves to finalized
    for (String s : List.of("requirements", "evaluation", "consolidation", "finalized")) {
      TestUsers.sql("UPDATE tenders SET status = ? WHERE id = ?", s, tender);
    }
    post("/tenders/" + tender + "/archive", admin)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.archived")
        .isEqualTo(true);
    get("/tenders?q=" + TestUsers.scalar("SELECT name FROM tenders WHERE id = ?", tender), admin)
        .exchange()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(0); // hidden from the default list
    patch("/tenders/" + tender, admin)
        .header("If-Match", "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("description", "x"))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("tender_archived");
  }

  // ---- committee
  // ----------------------------------------------------------------------------------

  @Test
  void theCommitteeChangesAndTheChairMoves() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser added = TestUsers.create("member");
    TestUser adminToo = TestUsers.create("admin");
    UUID tender = Fixtures.tender(admin, chair);

    post("/tenders/" + tender + "/members", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("user_id", added.id()))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.user.id")
        .isEqualTo(added.id().toString())
        .jsonPath("$.is_chair")
        .isEqualTo(false);
    post("/tenders/" + tender + "/members", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("user_id", added.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("already_member");
    post("/tenders/" + tender + "/members", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("user_id", adminToo.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(422);

    put("/tenders/" + tender + "/chair", admin, Map.of("user_id", added.id()))
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.is_chair")
        .isEqualTo(true);
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM tender_members WHERE tender_id = ? AND is_chair AND status = 'active'",
                tender))
        .isEqualTo(1L);
    put("/tenders/" + tender + "/chair", admin, Map.of("user_id", UUID.randomUUID()))
        .expectStatus()
        .isEqualTo(422);
    assertThat(audit("committee_changed", tender)).isEqualTo(2);
  }

  @Test
  void aMemberLeavesAndTheChairCannotBeRemoved() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser leaver = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, leaver);
    post("/tenders/" + tender + "/members/" + chair.id() + "/remove", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("handling", "kept_as_history"))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("chair_must_be_replaced");
    post("/tenders/" + tender + "/members/" + leaver.id() + "/remove", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("handling", "kept_as_history", "note", "moved to another team"))
        .exchange()
        .expectStatus()
        .isNoContent();
    assertThat(
            TestUsers.scalar(
                "SELECT removal_handling FROM tender_members WHERE tender_id = ? AND user_id = ?",
                tender,
                leaver.id()))
        .isEqualTo("kept_as_history");
    // gone: the removed member no longer sees the tender
    get("/tenders/" + tender, leaver).exchange().expectStatus().isForbidden();
    post("/tenders/" + tender + "/members/" + UUID.randomUUID() + "/remove", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("handling", "kept_as_history"))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  // ---- stage dates, duplicate
  // -------------------------------------------------------------------------

  @Test
  void stageDatesAreReplacedAsASet() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = Fixtures.tender(admin, TestUsers.create("member"));
    put(
            "/tenders/" + tender + "/stage-dates",
            admin,
            Map.of(
                "items",
                List.of(
                    Map.of(
                        "stage", "requirements", "due_date", "2027-01-10", "reminder_lead_days", 3),
                    Map.of("stage", "evaluation", "due_date", "2027-02-10"))))
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].stage")
        .isEqualTo("requirements")
        .jsonPath("$.items[0].reminder_lead_days")
        .isEqualTo(3)
        .jsonPath("$.items[1].due_date")
        .isEqualTo("2027-02-10");
    put(
            "/tenders/" + tender + "/stage-dates",
            admin,
            Map.of("items", List.of(Map.of("stage", "evaluation", "due_date", "2027-03-01"))))
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].due_date")
        .isEqualTo("2027-03-01")
        .jsonPath("$.items[1]")
        .doesNotExist();
    put(
            "/tenders/" + tender + "/stage-dates",
            admin,
            Map.of(
                "items",
                List.of(
                    Map.of("stage", "evaluation", "due_date", "2027-03-01"),
                    Map.of("stage", "evaluation", "due_date", "2027-03-02"))))
        .expectStatus()
        .isEqualTo(422);
    get("/tenders/" + tender, admin)
        .exchange()
        .expectBody()
        .jsonPath("$.due_date")
        .isEqualTo("2027-03-01");
  }

  @Test
  void aTenderIsDuplicatedWithItsCommitteeButWithoutDocuments() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser member = TestUsers.create("member");
    UUID source = Fixtures.tender(admin, chair, member);
    Fixtures.rfp(source, admin);
    String copyName = name();
    var r =
        post("/tenders/" + source + "/duplicate", admin)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", copyName))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo(copyName)
            .jsonPath("$.committee_size")
            .isEqualTo(2)
            .jsonPath("$.chair.id")
            .isEqualTo(chair.id().toString())
            .jsonPath("$.status")
            .isEqualTo("draft")
            .returnResult();
    UUID copy = UUID.fromString(JsonPath.read(new String(r.getResponseBody()), "$.id"));
    assertThat(TestUsers.scalar("SELECT count(*) FROM documents WHERE tender_id = ?", copy))
        .isEqualTo(0L);
    assertThat(audit("tender_duplicated", copy)).isEqualTo(1);
  }

  @Test
  void homeAndProgressAreAvailableToTheCommittee() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair);
    get("/tenders/" + tender + "/home", chair)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.tender.id")
        .isEqualTo(tender.toString())
        .jsonPath("$.requirements_summary.total")
        .isEqualTo(0);
    get("/tenders/" + tender + "/progress", chair)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.stage")
        .isEqualTo("draft")
        .jsonPath("$.members[0].user.id")
        .isEqualTo(chair.id().toString());
  }

  // PUT helper
  private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec put(
      String path, TestUser user, Object body) {
    return web.put()
        .uri(path)
        .header("Authorization", "Bearer " + tokenFor(user))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }
}
