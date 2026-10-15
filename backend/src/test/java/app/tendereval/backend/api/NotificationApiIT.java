package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.ReportWorld;
import app.tendereval.backend.testsupport.ReportWorld.World;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** UX-NOT-01..03: who is told what, reading and marking, preferences, reminders. */
class NotificationApiIT extends AbstractApiIT {

  private WebTestClient.RequestBodySpec call(String method, String uri, TestUser u) {
    return web.method(HttpMethod.valueOf(method))
        .uri(uri)
        .header("Authorization", "Bearer " + tokenFor(u))
        .header("Idempotency-Key", "n-" + UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON);
  }

  private void finalizeReport(World w) {
    call("PUT", "/tenders/" + w.tender() + "/report/grades/" + w.tv() + "/" + w.r1(), w.chair())
        .bodyValue(
            Map.of(
                "final_grade", "partially_compliant", "justification", "Backups only is partial."))
        .exchange()
        .expectStatus()
        .isOk();
    call("POST", "/tenders/" + w.tender() + "/report/finalize", w.chair())
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void finalizingTellsTheCommitteeAndEachPersonSeesOnlyTheirOwn() {
    World w = ReportWorld.create();
    finalizeReport(w);
    call("GET", "/notifications?unread=true", w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].notification_type")
        .isEqualTo("report_finalized")
        .jsonPath("$.items[0].link")
        .isEqualTo("/tenders/" + w.tender() + "/report");
    call("GET", "/notifications/unread-count", w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.count")
        .isEqualTo(1)
        .jsonPath("$.by_tender[0].count")
        .isEqualTo(1);
    // the Observer and the administrator are not on the committee: nothing for them
    call("GET", "/notifications", w.observer())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(0);
    byte[] out =
        call("GET", "/notifications", w.m1())
            .exchange()
            .expectBody()
            .returnResult()
            .getResponseBody();
    String id = JsonPath.read(new String(out), "$.items[0].id");
    // somebody else cannot mark it
    call("POST", "/notifications/" + id + "/read", w.m2()).exchange().expectStatus().isNotFound();
    call("POST", "/notifications/" + id + "/read", w.m1()).exchange().expectStatus().isNoContent();
    call("GET", "/notifications/unread-count", w.m1())
        .exchange()
        .expectBody()
        .jsonPath("$.count")
        .isEqualTo(0);
    call("POST", "/notifications/read-all", w.m2()).exchange().expectStatus().isNoContent();
    call("GET", "/notifications/unread-count", w.m2())
        .exchange()
        .expectBody()
        .jsonPath("$.count")
        .isEqualTo(0);
  }

  @Test
  void aPersonWhoAskedForEmailGetsThePendingDeliveryStatus() {
    World w = ReportWorld.create();
    call("PUT", "/notification-preferences", w.m1())
        .bodyValue(
            Map.of(
                "items",
                List.of(Map.of("notification_type", "report_finalized", "email_enabled", true))))
        .exchange()
        .expectStatus()
        .isOk();
    call("GET", "/notification-preferences", w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[?(@.notification_type=='report_finalized')].email_enabled")
        .isEqualTo(List.of(true));
    finalizeReport(w);
    assertThat(
            TestUsers.scalar(
                "SELECT email_status FROM notifications WHERE user_id = ? AND notification_type ="
                    + " 'report_finalized'",
                w.m1().id()))
        .isEqualTo("pending");
    assertThat(
            TestUsers.scalar(
                "SELECT email_status FROM notifications WHERE user_id = ? AND notification_type ="
                    + " 'report_finalized'",
                w.m2().id()))
        .isEqualTo("not_requested");
  }

  @Test
  void theChairRemindsOnlyThoseWhoHaveNotSubmitted() {
    World w = ReportWorld.create();
    TestUsers.sql(
        "UPDATE member_evaluations SET status = 'reopened', reopened_at = now(),"
            + " reopen_reason = 'Please look again at this.', reopened_by = ?"
            + " WHERE tender_vendor_id = ? AND user_id = ?",
        w.chair().id(),
        w.tv(),
        w.m2().id());
    String path = "/tenders/" + w.tender() + "/report/reminders";
    call("POST", path, w.m1()).bodyValue(Map.of()).exchange().expectStatus().isForbidden();
    call("POST", path, w.chair()).bodyValue(Map.of()).exchange().expectStatus().isNoContent();
    call("GET", "/notifications", w.m2())
        .exchange()
        .expectBody()
        .jsonPath("$.items[0].notification_type")
        .isEqualTo("manual_reminder");
    call("GET", "/notifications", w.m1())
        .exchange()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(0);
  }

  @Test
  void aDueDateReminderGoesOutOnceForEachDueDate() {
    World w = ReportWorld.create();
    TestUsers.sql(
        "INSERT INTO tender_stage_dates (tender_id, stage, due_date, reminder_lead_days)"
            + " VALUES (?, 'consolidation', current_date + 2, 3)",
        w.tender());
    assertThat(TestUsers.scalar("SELECT count(*) FROM app_run_due_reminders()"))
        .isEqualTo(3L); // one for each active member
    assertThat(TestUsers.scalar("SELECT count(*) FROM app_run_due_reminders()")).isEqualTo(0L);
    call("GET", "/notifications", w.m1())
        .exchange()
        .expectBody()
        .jsonPath("$.items[0].notification_type")
        .isEqualTo("due_date_reminder")
        .jsonPath("$.items[0].body")
        .value(b -> assertThat(String.valueOf(b)).contains("consolidation"));
  }
}
