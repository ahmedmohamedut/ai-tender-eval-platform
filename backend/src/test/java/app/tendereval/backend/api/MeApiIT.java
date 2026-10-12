package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** UX-AUTH-01, UX-ROL-04, PI 3.4, PI 3.7: who is calling, and what they may be told. */
class MeApiIT extends AbstractApiIT {

  @Test
  void aKnownUserGetsProfileAndClientConfig() {
    TestUser member = TestUsers.create("member");
    get("/me", member)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.user.id")
        .isEqualTo(member.id().toString())
        .jsonPath("$.user.email")
        .isEqualTo(member.email())
        .jsonPath("$.user.role")
        .isEqualTo("member")
        .jsonPath("$.user.status")
        .isEqualTo("active")
        .jsonPath("$.client_config.min_note_length")
        .isEqualTo(10)
        .jsonPath("$.client_config.upload_max_bytes")
        .isEqualTo(104857600)
        .jsonPath("$.client_config.accepted_formats[0]")
        .isEqualTo("pdf")
        .jsonPath("$.client_config.accepted_formats[1]")
        .isEqualTo("docx")
        .jsonPath("$.client_config.session_warning_seconds")
        .isEqualTo(120)
        .jsonPath("$.client_config.session_idle_timeout_seconds")
        .isEqualTo(1800)
        .jsonPath("$.assigned_tender_count")
        .isEqualTo(0);
  }

  @Test
  void assignedTenderCountCountsOnlyTendersTheMemberSitsOn() {
    TestUser admin = TestUsers.create("admin");
    TestUser member = TestUsers.create("member");
    UUID mine = tender(admin);
    tender(admin); // not theirs
    TestUsers.sql(
        "INSERT INTO tender_members (tender_id, user_id) VALUES (?, ?)", mine, member.id());
    get("/me", member).exchange().expectBody().jsonPath("$.assigned_tender_count").isEqualTo(1);
  }

  @Test
  void settingsChangesReachTheClient() {
    TestUser member = TestUsers.create("member");
    TestUsers.sql("UPDATE settings SET value = '25' WHERE key = 'note.min_length'");
    try {
      get("/me", member)
          .exchange()
          .expectBody()
          .jsonPath("$.client_config.min_note_length")
          .isEqualTo(25);
    } finally {
      TestUsers.sql("UPDATE settings SET value = '10' WHERE key = 'note.min_length'");
    }
  }

  @Test
  void firstSignInActivatesAnInvitedUser() {
    TestUser invited = TestUsers.create("member", "invited");
    get("/me", invited)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.user.status")
        .isEqualTo("active");
    assertThat(TestUsers.scalar("SELECT status FROM users WHERE id = ?", invited.id()))
        .isEqualTo("active");
    assertThat(
            TestUsers.scalar(
                "SELECT last_sign_in_at IS NOT NULL FROM users WHERE id = ?", invited.id()))
        .isEqualTo(true);
  }

  @Test
  void anUnknownSubjectIsRefusedAsNotInvited() {
    String token = tokenFor(UUID.randomUUID().toString(), "member", "stranger@example.test");
    web.get()
        .uri("/me")
        .header("Authorization", "Bearer " + token)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .contentTypeCompatibleWith("application/problem+json")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("not_invited")
        .jsonPath("$.trace_id")
        .isNotEmpty();
  }

  @Test
  void aDifferentEmailCannotBecomeAdministrator() {
    String token = tokenFor(UUID.randomUUID().toString(), "admin", "someone.else@example.test");
    web.get()
        .uri("/me")
        .header("Authorization", "Bearer " + token)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("not_invited");
  }

  @Test
  void aDeactivatedUserIsRefusedEvenWithAValidToken() {
    TestUser user = TestUsers.create("member", "deactivated");
    get("/me", user)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("user_deactivated");
  }

  @Test
  void theRoleComesFromTheDatabaseNotFromTheToken() {
    // The token still says admin five minutes after the role was changed (PI Q10).
    TestUser user = TestUsers.create("member");
    String token = tokenFor(user.sub(), "admin", user.email());
    web.get()
        .uri("/me")
        .header("Authorization", "Bearer " + token)
        .exchange()
        .expectBody()
        .jsonPath("$.user.role")
        .isEqualTo("member");
  }

  @Test
  void patchMeChangesTimeZoneAndRefusesUnknownZones() {
    TestUser user = TestUsers.create("member");
    patch("/me", user)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("time_zone", "Europe/Berlin"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.user.time_zone")
        .isEqualTo("Europe/Berlin");
    patch("/me", user)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("time_zone", "Mars/Olympus"))
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("validation_failed")
        .jsonPath("$.errors[0].field")
        .isEqualTo("time_zone");
  }

  @Test
  void anOperationNotBuiltYetAnswers501AsProblemJson() {
    TestUser admin = TestUsers.create("admin");
    get("/audit-events", admin)
        .exchange()
        .expectStatus()
        .isEqualTo(501)
        .expectHeader()
        .contentTypeCompatibleWith("application/problem+json")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("not_implemented")
        .jsonPath("$.trace_id")
        .isNotEmpty();
  }

  @Test
  void errorsNeverCarryTheCallersDataOrInternals() {
    TestUser member = TestUsers.create("member");
    String body =
        get("/audit-events?q=secret-vendor-name", member)
            .exchange()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    assertThat(body)
        .doesNotContain("secret-vendor-name")
        .doesNotContain("Exception")
        .doesNotContain("SELECT");
  }

  private static UUID tender(TestUser admin) {
    return (UUID)
        TestUsers.scalar(
            "INSERT INTO tenders (name, created_by) VALUES (?, ?) RETURNING id",
            "T " + UUID.randomUUID(),
            admin.id());
  }
}
