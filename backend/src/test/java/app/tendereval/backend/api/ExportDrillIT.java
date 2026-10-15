package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * P84, P5-7 drills: an export whose writer was killed is shown as failed and is built again by a
 * new request; a ready export whose file is gone answers "expired" and is built again too.
 */
class ExportDrillIT extends AbstractApiIT {

  private WebTestClient.RequestBodySpec call(String method, String uri, TestUser u) {
    return web.method(HttpMethod.valueOf(method))
        .uri(uri)
        .header("Authorization", "Bearer " + tokenFor(u))
        .header("Idempotency-Key", "dr-" + UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON);
  }

  private String status(String id, TestUser u) {
    byte[] out =
        call("GET", "/exports/" + id, u)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult()
            .getResponseBody();
    return JsonPath.read(new String(out), "$.status");
  }

  private String regenerate(TestUser u) {
    byte[] out =
        call("POST", "/audit-events/export", u)
            .bodyValue(Map.of())
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody()
            .returnResult()
            .getResponseBody();
    String id = JsonPath.read(new String(out), "$.id");
    for (int i = 0; i < 100; i++) {
      if ("ready".equals(status(id, u))) {
        return id;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    throw new AssertionError("export never became ready");
  }

  @Test
  void anExportWhoseWriterWasKilledIsFailedAndIsBuiltAgainByANewRequest() {
    TestUser admin = TestUsers.create("admin");
    UUID dead =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO exports (export_type, source_type, format, requested_by, status,"
                    + " created_at) VALUES ('audit_log', 'audit_filter', 'csv', ?, 'pending',"
                    + " now() - interval '11 minutes') RETURNING id",
                admin.id());
    assertThat(status(dead.toString(), admin)).isEqualTo("failed");
    assertThat(TestUsers.scalar("SELECT failure_code FROM exports WHERE id = ?", dead))
        .isEqualTo("interrupted");
    // the file of the dead row never appears; a new request makes a new, working export
    String again = regenerate(admin);
    assertThat(again).isNotEqualTo(dead.toString());
    call("GET", "/exports/" + again + "/file", admin).exchange().expectStatus().isOk();
    assertThat(status(dead.toString(), admin)).isEqualTo("failed");
  }

  @Test
  void aReadyExportWhoseFileIsGoneAnswersExpiredAndIsBuiltAgain() {
    TestUser admin = TestUsers.create("admin");
    UUID id = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO exports (id, export_type, source_type, format, requested_by)"
            + " VALUES (?, 'audit_log', 'audit_filter', 'csv', ?)",
        id,
        admin.id());
    TestUsers.sql(
        "UPDATE exports SET status = 'ready', storage_key = ? WHERE id = ?",
        "exports/" + admin.id() + "/" + id + ".csv",
        id);
    call("GET", "/exports/" + id + "/file", admin)
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("export_expired");
    String again = regenerate(admin);
    call("GET", "/exports/" + again + "/file", admin).exchange().expectStatus().isOk();
  }
}
