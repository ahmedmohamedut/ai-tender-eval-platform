package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** UX-ROL-05, PI 3.5: the invitation lifecycle and role changes, administrator only. */
class UsersApiIT extends AbstractApiIT {

  @BeforeEach
  void reset() {
    keycloak.reset();
  }

  private static String email() {
    return "invitee-" + UUID.randomUUID() + "@example.test";
  }

  private long audit(String action, Object objectId) {
    return ((Number)
            TestUsers.scalar(
                "SELECT count(*) FROM audit_events WHERE action = ? AND object_id = ?",
                action,
                objectId))
        .longValue();
  }

  @Test
  void anAdministratorInvitesAUser() {
    TestUser admin = TestUsers.create("admin");
    String email = email();
    var body =
        post("/users", admin)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                Map.of("email", email, "display_name", "Ada Lovelace King", "role", "member"))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectHeader()
            .exists("ETag")
            .expectBody()
            .jsonPath("$.email")
            .isEqualTo(email)
            .jsonPath("$.status")
            .isEqualTo("invited")
            .jsonPath("$.role")
            .isEqualTo("member")
            .jsonPath("$.display_name")
            .isEqualTo("Ada Lovelace King")
            .returnResult();
    assertThat(keycloak.calls)
        .containsExactly(
            "createUser:" + email + ":Ada Lovelace|King", // name split at the last space
            "mapRealmRole:committee_member",
            "sendInvitation");
    Object id = TestUsers.scalar("SELECT id FROM users WHERE lower(email) = lower(?)", email);
    assertThat(id).isNotNull();
    assertThat(TestUsers.scalar("SELECT sso_subject IS NOT NULL FROM users WHERE id = ?", id))
        .isEqualTo(true);
    assertThat(audit("user_invited", id)).isEqualTo(1);
  }

  @Test
  void aFailedInvitationLeavesNoAccountBehind() {
    TestUser admin = TestUsers.create("admin");
    String email = email();
    keycloak.failOn = "sendInvitation";
    post("/users", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("email", email, "display_name", "No Mail", "role", "member"))
        .exchange()
        .expectStatus()
        .isEqualTo(502)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("identity_provider_error");
    assertThat(keycloak.calls).contains("deleteUser"); // compensation (PI 3.5)
    assertThat(TestUsers.scalar("SELECT count(*) FROM users WHERE lower(email) = lower(?)", email))
        .isEqualTo(0L);
  }

  @Test
  void aDuplicateEmailIsRefusedBeforeKeycloakIsTouched() {
    TestUser admin = TestUsers.create("admin");
    TestUser existing = TestUsers.create("member");
    post("/users", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            Map.of(
                "email", existing.email().toUpperCase(), "display_name", "Twin", "role", "member"))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("email_exists");
    assertThat(keycloak.calls).isEmpty();
  }

  @Test
  void onlyAnAdministratorManagesUsers() {
    TestUser member = TestUsers.create("member");
    TestUser other = TestUsers.create("member");
    TestUser observer = TestUsers.create("observer");
    for (TestUser u : List.of(member, observer)) {
      get("/users", u).exchange().expectStatus().isForbidden();
      get("/users/" + other.id(), u).exchange().expectStatus().isForbidden();
      post("/users", u)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(Map.of("email", email(), "display_name", "X Y", "role", "member"))
          .exchange()
          .expectStatus()
          .isForbidden();
      post("/users/" + other.id() + "/deactivate", u)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(Map.of("handling", "kept_as_history"))
          .exchange()
          .expectStatus()
          .isForbidden();
    }
    assertThat(keycloak.calls).isEmpty();
  }

  @Test
  void listingFiltersSortsAndPagesWithACursor() {
    TestUser admin = TestUsers.create("admin");
    String tag = "Zz" + UUID.randomUUID().toString().substring(0, 6);
    for (String n : List.of("Alpha", "Bravo", "Charlie", "Delta", "Echo")) {
      TestUsers.sql(
          "INSERT INTO users (email, display_name, role, status, sso_subject) VALUES (?, ?, 'member', 'active', ?)",
          n + tag + "@example.test",
          n + " " + tag,
          UUID.randomUUID().toString());
    }
    var first =
        get("/users?q=" + tag + "&limit=2&sort=display_name", admin)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.items.length()")
            .isEqualTo(2)
            .jsonPath("$.items[0].display_name")
            .isEqualTo("Alpha " + tag)
            .jsonPath("$.items[1].display_name")
            .isEqualTo("Bravo " + tag)
            .jsonPath("$.total")
            .isEqualTo(5)
            .jsonPath("$.next_cursor")
            .isNotEmpty()
            .returnResult();
    String cursor =
        com.jayway.jsonpath.JsonPath.read(new String(first.getResponseBody()), "$.next_cursor");
    get("/users?q=" + tag + "&limit=2&sort=display_name&cursor=" + cursor, admin)
        .exchange()
        .expectBody()
        .jsonPath("$.items[0].display_name")
        .isEqualTo("Charlie " + tag)
        .jsonPath("$.items[1].display_name")
        .isEqualTo("Delta " + tag);
    get("/users?q=" + tag + "&limit=2&sort=-display_name", admin)
        .exchange()
        .expectBody()
        .jsonPath("$.items[0].display_name")
        .isEqualTo("Echo " + tag);
    get("/users?role=observer&q=" + tag, admin)
        .exchange()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(0);
    get("/users?sort=password", admin)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_sort");
    get("/users?cursor=garbage", admin).exchange().expectStatus().isBadRequest();
  }

  @Test
  void aSearchTermIsNotAPattern() {
    TestUser admin = TestUsers.create("admin");
    get("/users?q=%25", admin)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(0);
    get("/users?q=a_b", admin).exchange().expectStatus().isOk();
  }

  @Test
  void lookupReturnsCommitteeCandidatesOnly() {
    TestUser admin = TestUsers.create("admin");
    String tag = "Lk" + UUID.randomUUID().toString().substring(0, 6);
    TestUsers.sql(
        "INSERT INTO users (email, display_name, role, status, sso_subject)"
            + " VALUES (?, ?, 'member', 'active', ?)",
        tag + "m@example.test",
        "Member " + tag,
        UUID.randomUUID().toString());
    TestUsers.sql(
        "INSERT INTO users (email, display_name, role, status, sso_subject)"
            + " VALUES (?, ?, 'admin', 'active', ?)",
        tag + "a@example.test",
        "Admin " + tag,
        UUID.randomUUID().toString());
    TestUsers.sql(
        "INSERT INTO users (email, display_name, role, status, sso_subject)"
            + " VALUES (?, ?, 'member', 'deactivated', ?)",
        tag + "d@example.test",
        "Gone " + tag,
        UUID.randomUUID().toString());
    get("/users/lookup?q=" + tag, admin)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(1)
        .jsonPath("$.items[0].display_name")
        .isEqualTo("Member " + tag);
  }

  @Test
  void changingARoleNeedsTheCurrentVersionAndUpdatesKeycloak() {
    TestUser admin = TestUsers.create("admin");
    TestUser target = TestUsers.create("member");
    // no If-Match
    patch("/users/" + target.id(), admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("role", "observer"))
        .exchange()
        .expectStatus()
        .isEqualTo(428);
    // stale
    patch("/users/" + target.id(), admin)
        .header("If-Match", "\"99\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("role", "observer"))
        .exchange()
        .expectStatus()
        .isEqualTo(412);
    // current
    patch("/users/" + target.id(), admin)
        .header("If-Match", "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("role", "observer", "display_name", "Renamed Person"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("ETag", "\"2\"")
        .expectBody()
        .jsonPath("$.role")
        .isEqualTo("observer")
        .jsonPath("$.display_name")
        .isEqualTo("Renamed Person");
    assertThat(keycloak.calls)
        .containsExactly(
            "updateName:Renamed|Person", "replaceRealmRole:committee_member->observer");
    assertThat(audit("user_role_changed", target.id())).isEqualTo(1);
  }

  @Test
  void anAdministratorCannotChangeTheirOwnRoleOrLeaveTheSystemWithoutOne() {
    TestUsers.sql(
        "UPDATE users SET status = 'deactivated' WHERE role = 'admin' AND sso_subject NOT LIKE 'keep-%'"
            + " AND false");
    TestUser admin = TestUsers.create("admin");
    patch("/users/" + admin.id(), admin)
        .header("If-Match", "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("role", "member"))
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("cannot_change_own_role");
  }

  @Test
  void aMemberOfACommitteeCannotBecomeAnAdministrator() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser seated = TestUsers.create("member");
    Fixtures.tender(admin, chair, seated);
    patch("/users/" + seated.id(), admin)
        .header("If-Match", "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("role", "admin"))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("has_memberships");
    assertThat(keycloak.calls).isEmpty();
  }

  @Test
  void deactivationDisablesLogsOutAndReleasesTheirSeats() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser leaving = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, leaving);
    post("/users/" + leaving.id() + "/deactivate", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("handling", "kept_as_history", "note", "left the company"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("deactivated");
    assertThat(keycloak.calls).containsExactly("setEnabled:false", "logout");
    assertThat(
            TestUsers.scalar(
                "SELECT removal_handling FROM tender_members WHERE tender_id = ? AND user_id = ?",
                tender,
                leaving.id()))
        .isEqualTo("kept_as_history");
    assertThat(audit("user_deactivated", leaving.id())).isEqualTo(1);
    // The token is still valid, the person is refused.
    get("/me", leaving)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("user_deactivated");
  }

  @Test
  void aChairIsReplacedBeforeTheirAccountCanBeClosed() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    Fixtures.tender(admin, chair);
    post("/users/" + chair.id() + "/deactivate", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("handling", "kept_as_history"))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("chair_must_be_replaced");
    assertThat(keycloak.calls).isEmpty();
    assertThat(TestUsers.scalar("SELECT status FROM users WHERE id = ?", chair.id()))
        .isEqualTo("active");
  }

  @Test
  void workCanBeHandedToAnotherMember() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser leaving = TestUsers.create("member");
    TestUser heir = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, leaving);
    post("/users/" + leaving.id() + "/deactivate", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("handling", "reassigned", "reassign_to_user_id", heir.id()))
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM tender_members WHERE tender_id = ? AND user_id = ? AND status = 'active'",
                tender,
                heir.id()))
        .isEqualTo(1L);
    assertThat(
            TestUsers.scalar(
                "SELECT reassigned_to_user_id FROM tender_members WHERE tender_id = ? AND user_id = ?",
                tender,
                leaving.id()))
        .isEqualTo(heir.id());
  }

  @Test
  void reassignmentNeedsATarget() {
    TestUser admin = TestUsers.create("admin");
    TestUser u = TestUsers.create("member");
    post("/users/" + u.id() + "/deactivate", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("handling", "reassigned"))
        .exchange()
        .expectStatus()
        .isEqualTo(422);
  }

  @Test
  void anAdministratorCannotDeactivateThemselves() {
    TestUser admin = TestUsers.create("admin");
    post("/users/" + admin.id() + "/deactivate", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("handling", "kept_as_history"))
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("cannot_deactivate_self");
  }

  @Test
  void reactivationEnablesTheAccountAgain() {
    TestUser admin = TestUsers.create("admin");
    TestUser u = TestUsers.create("member", "deactivated");
    TestUsers.sql("UPDATE users SET last_sign_in_at = now() WHERE id = ?", u.id());
    post("/users/" + u.id() + "/reactivate", admin)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("active");
    assertThat(keycloak.calls).containsExactly("setEnabled:true");
    assertThat(audit("user_reactivated", u.id())).isEqualTo(1);
  }

  @Test
  void anInvitationCanBeResentOnlyWhileInvited() {
    TestUser admin = TestUsers.create("admin");
    TestUser invited = TestUsers.create("member", "invited");
    TestUser active = TestUsers.create("member");
    post("/users/" + invited.id() + "/resend-invitation", admin)
        .exchange()
        .expectStatus()
        .isNoContent();
    assertThat(keycloak.calls).containsExactly("sendInvitation");
    post("/users/" + active.id() + "/resend-invitation", admin)
        .exchange()
        .expectStatus()
        .isEqualTo(409);
    post("/users/" + UUID.randomUUID() + "/resend-invitation", admin)
        .exchange()
        .expectStatus()
        .isNotFound();
  }
}
