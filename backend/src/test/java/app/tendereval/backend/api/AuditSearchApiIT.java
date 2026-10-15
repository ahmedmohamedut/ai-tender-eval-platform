package app.tendereval.backend.api;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.ReportWorld;
import app.tendereval.backend.testsupport.ReportWorld.World;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** UX-AUD-01, UX-AUD-04: the trail is searched by the administrator and the Observer only. */
class AuditSearchApiIT extends AbstractApiIT {

  private WebTestClient.RequestBodySpec call(String method, String uri, TestUser u) {
    return web.method(HttpMethod.valueOf(method))
        .uri(uri)
        .header("Authorization", "Bearer " + tokenFor(u))
        .header("Idempotency-Key", "a-" + UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON);
  }

  @Test
  void filtersByTenderAndActionAndRefusesMembers() {
    World w = ReportWorld.create();
    call("PUT", "/tenders/" + w.tender() + "/report/grades/" + w.tv() + "/" + w.r1(), w.chair())
        .bodyValue(
            Map.of(
                "final_grade", "partially_compliant", "justification", "Backups only is partial."))
        .exchange()
        .expectStatus()
        .isOk();
    String q = "/audit-events?tender_id=" + w.tender() + "&action=report_grade_recorded";
    call("GET", q, w.admin())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(1)
        .jsonPath("$.items[0].action")
        .isEqualTo("report_grade_recorded")
        .jsonPath("$.items[0].actor.id")
        .isEqualTo(w.chair().id().toString())
        // the grade and its justification are not in the trail before release
        .jsonPath("$.items[0].after.final_grade")
        .doesNotExist();
    call("GET", q, w.observer()).exchange().expectStatus().isOk();
    call("GET", q, w.m1()).exchange().expectStatus().isForbidden();
    call("GET", "/audit-events?tender_id=" + w.tender() + "&action=report_finalized", w.admin())
        .exchange()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(0);
  }

  @Test
  void accessLogIsFilteredByTenderAndUserAndRefusesMembers() {
    World w = ReportWorld.create();
    UUID dv =
        (UUID)
            app.tendereval.backend.testsupport.TestUsers.scalar(
                "SELECT dv.id FROM document_versions dv JOIN documents d ON d.id = dv.document_id"
                    + " WHERE d.tender_id = ? LIMIT 1",
                w.tender());
    app.tendereval.backend.testsupport.TestUsers.sql(
        "INSERT INTO document_access_events (user_id, document_version_id, watermark_shown)"
            + " VALUES (?, ?, true)",
        w.m1().id(),
        dv);
    String q = "/document-access-events?tender_id=" + w.tender() + "&user_id=" + w.m1().id();
    call("GET", q, w.admin())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(1)
        .jsonPath("$.items[0].user.id")
        .isEqualTo(w.m1().id().toString())
        .jsonPath("$.items[0].document_version_id")
        .isEqualTo(dv.toString())
        .jsonPath("$.items[0].watermark_shown")
        .isEqualTo(true);
    call("GET", q, w.observer()).exchange().expectStatus().isOk();
    call("GET", q, w.m2()).exchange().expectStatus().isForbidden();
    call("GET", "/document-access-events?user_id=" + w.m2().id(), w.admin())
        .exchange()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(0);
  }
}
