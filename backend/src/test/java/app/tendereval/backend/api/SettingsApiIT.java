package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SettingsApiIT extends AbstractApiIT {

  @AfterEach
  void restore() {
    TestUsers.sql("UPDATE settings SET value = '10' WHERE key = 'note.min_length'");
    TestUsers.sql("UPDATE settings SET value = '0.6' WHERE key = 'ai.low_confidence_threshold'");
  }

  private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec put(
      String key, TestUser user, Object value) {
    return web.put()
        .uri("/settings/" + key)
        .header("Authorization", "Bearer " + tokenFor(user))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("value", value))
        .exchange();
  }

  @Test
  void theAdministratorListsAndChangesSettings() {
    TestUser admin = TestUsers.create("admin");
    get("/settings", admin)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[?(@.key=='note.min_length')].value")
        .isEqualTo(java.util.List.of(10))
        .jsonPath("$[?(@.key=='ai.low_confidence_threshold')].value")
        .isEqualTo(java.util.List.of(0.6));
    put("note.min_length", admin, 20)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.value")
        .isEqualTo(20)
        .jsonPath("$.changed_by.id")
        .isEqualTo(admin.id().toString());
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM audit_events WHERE action = 'setting_changed'"
                    + " AND (after ->> 'note.min_length') = '20' AND actor_user_id = ?",
                admin.id()))
        .isEqualTo(1L);
    // and the client sees it
    get("/me", admin)
        .exchange()
        .expectBody()
        .jsonPath("$.client_config.min_note_length")
        .isEqualTo(20);
  }

  @Test
  void valuesOutsideTheirRangeAreRefused() {
    TestUser admin = TestUsers.create("admin");
    put("note.min_length", admin, 0)
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.errors[0].code")
        .isEqualTo("out_of_range");
    put("note.min_length", admin, "ten").expectStatus().isEqualTo(422);
    put("note.min_length", admin, 10.5).expectStatus().isEqualTo(422);
    // The upload limit must stay below the gateway's body limit (PI Q13).
    put("upload.max_bytes", admin, 200L * 1024 * 1024).expectStatus().isEqualTo(422);
    put("ai.low_confidence_threshold", admin, 1.5).expectStatus().isEqualTo(422);
    put("no.such.key", admin, 1).expectStatus().isNotFound();
  }

  @Test
  void anUnchangedValueWritesNoEvent() {
    TestUser admin = TestUsers.create("admin");
    long before =
        ((Number)
                TestUsers.scalar(
                    "SELECT count(*) FROM audit_events WHERE action = 'setting_changed'"))
            .longValue();
    put("note.min_length", admin, 10).expectStatus().isOk();
    assertThat(
            ((Number)
                    TestUsers.scalar(
                        "SELECT count(*) FROM audit_events WHERE action = 'setting_changed'"))
                .longValue())
        .isEqualTo(before);
  }

  @Test
  void onlyTheAdministratorReadsOrChangesSettings() {
    TestUser member = TestUsers.create("member");
    TestUser observer = TestUsers.create("observer");
    for (TestUser u : java.util.List.of(member, observer)) {
      get("/settings", u).exchange().expectStatus().isForbidden();
      put("note.min_length", u, 20).expectStatus().isForbidden();
    }
  }
}
