package app.tendereval.backend.api;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.ReportWorld;
import app.tendereval.backend.testsupport.ReportWorld.World;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * UX-RPT-01..13, UX-SCO-03: progress, disagreements, final grades, finalize, reopen, visibility.
 */
class ReportApiIT extends AbstractApiIT {

  @org.springframework.beans.factory.annotation.Autowired io.nats.client.JetStream js;

  private WebTestClient.RequestBodySpec call(String method, String uri, TestUser u) {
    return web.method(HttpMethod.valueOf(method))
        .uri(uri)
        .header("Authorization", "Bearer " + tokenFor(u))
        .header("Idempotency-Key", "r-" + UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON);
  }

  private String base(World w) {
    return "/tenders/" + w.tender() + "/report";
  }

  private void decide(World w, TestUser u, int expect) {
    call("PUT", base(w) + "/grades/" + w.tv() + "/" + w.r1(), u)
        .bodyValue(
            Map.of(
                "final_grade",
                "partially_compliant",
                "justification",
                "Backups only is partial coverage."))
        .exchange()
        .expectStatus()
        .isEqualTo(expect);
  }

  @Test
  void progressIsForTheChairAndTheAdministratorOnly() {
    World w = ReportWorld.create();
    call("GET", base(w) + "/progress", w.chair())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.vendors.length()")
        .isEqualTo(1)
        .jsonPath("$.cells.length()")
        .isEqualTo(3);
    call("GET", base(w) + "/progress", w.admin()).exchange().expectStatus().isOk();
    call("GET", base(w) + "/progress", w.m1()).exchange().expectStatus().isForbidden();
  }

  @Test
  void noOneButTheChairAndTheAdministratorReadsAnUnfinalizedReport() {
    World w = ReportWorld.create();
    for (String path : List.of("", "/overview", "/matrix", "/disagreements", "/appendix")) {
      call("GET", base(w) + path, w.m1()).exchange().expectStatus().isNotFound();
      call("GET", base(w) + path, w.observer()).exchange().expectStatus().isNotFound();
    }
    call("GET", base(w), w.chair())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("in_progress")
        .jsonPath("$.version_no")
        .isEqualTo(1)
        .jsonPath("$.disagreement_count")
        .isEqualTo(1);
    call("GET", base(w) + "/disagreements", w.admin())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(1)
        .jsonPath("$.items[0].member_grades.length()")
        .isEqualTo(3)
        .jsonPath("$.items[0].final_grade")
        .doesNotExist();
  }

  @Test
  void onlyTheChairDecidesAndOnlyWhereMembersDiffer() {
    World w = ReportWorld.create();
    decide(w, w.m1(), 403);
    decide(w, w.admin(), 403);
    call("PUT", base(w) + "/grades/" + w.tv() + "/" + w.r1(), w.chair())
        .bodyValue(Map.of("final_grade", "partially_compliant", "justification", "short"))
        .exchange()
        .expectStatus()
        .isEqualTo(422);
    // r2 is agreed by everybody: the final grade is derived
    call("PUT", base(w) + "/grades/" + w.tv() + "/" + w.r2(), w.chair())
        .bodyValue(
            Map.of("final_grade", "fully_compliant", "justification", "I would like it better."))
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("no_disagreement");
    decide(w, w.chair(), 200);
    call("GET", base(w) + "/matrix/" + w.r1() + "/" + w.tv(), w.chair())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.final_grade")
        .isEqualTo("partially_compliant")
        .jsonPath("$.basis")
        .isEqualTo("chair_decision")
        .jsonPath("$.ai.citations.length()")
        .isEqualTo(1);
    Object events =
        TestUsers.scalar(
            "SELECT count(*) FROM audit_events WHERE tender_id = ? AND action = 'report_grade_recorded'",
            w.tender());
    org.assertj.core.api.Assertions.assertThat(events).isEqualTo(1L);
  }

  @Test
  void finalizeNeedsEveryDisagreementDecidedThenLocksAndScores() {
    World w = ReportWorld.create();
    call("POST", base(w) + "/finalize", w.chair())
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.failures[0].code")
        .isEqualTo("disagreements_undecided");
    call("POST", base(w) + "/finalize", w.m1()).exchange().expectStatus().isForbidden();
    call("GET", base(w), w.chair())
        .exchange()
        .expectBody()
        .jsonPath("$.actions.finalize.allowed")
        .isEqualTo(false)
        .jsonPath("$.actions.finalize.reason")
        .isEqualTo("1 disagreements still need a final grade");
    decide(w, w.chair(), 200);
    call("GET", base(w), w.chair())
        .exchange()
        .expectBody()
        .jsonPath("$.actions.finalize.allowed")
        .isEqualTo(true);
    call("POST", base(w) + "/finalize", w.chair())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("finalized")
        .jsonPath("$.version_no")
        .isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(
            TestUsers.scalar("SELECT status FROM tenders WHERE id = ?", w.tender()))
        .isEqualTo("finalized");
    // (0.5 + 0) / 2 rows = 25 points of 100; one mandatory failure (r2); the vendor is ranked 1
    call("GET", base(w) + "/overview", w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.vendors[0].rank")
        .isEqualTo(1)
        .jsonPath("$.vendors[0].final_score")
        .isEqualTo(25.0)
        .jsonPath("$.vendors[0].mandatory_failures")
        .isEqualTo(1)
        .jsonPath("$.vendors[0].fails_mandatory")
        .isEqualTo(true)
        .jsonPath("$.vendors[0].agreement_level")
        .isEqualTo(50.0);
    // members and the Observer now read the finalized version, with who graded what
    call("GET", base(w) + "/matrix", w.observer())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.rows.length()")
        .isEqualTo(2)
        .jsonPath("$.rows[0].cells[0].has_disagreement")
        .isEqualTo(true);
    call("GET", base(w) + "/matrix?disagreement=true", w.m2())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.rows.length()")
        .isEqualTo(1);
    // a finalized version accepts no further grade, and cannot be finalized twice
    decide(w, w.chair(), 409);
    call("POST", base(w) + "/finalize", w.chair()).exchange().expectStatus().isEqualTo(409);
  }

  @Test
  void reopeningCreatesTheNextVersionAndTheEarlierOneStaysReadable() {
    World w = ReportWorld.create();
    decide(w, w.chair(), 200);
    call("POST", base(w) + "/finalize", w.chair()).exchange().expectStatus().isOk();
    call("POST", base(w) + "/reopen", w.m1())
        .bodyValue(Map.of("reason", "New evidence arrived."))
        .exchange()
        .expectStatus()
        .isForbidden();
    call("POST", base(w) + "/reopen", w.chair())
        .bodyValue(Map.of("reason", "short"))
        .exchange()
        .expectStatus()
        .isEqualTo(422);
    call("POST", base(w) + "/reopen", w.chair())
        .bodyValue(Map.of("reason", "New evidence arrived."))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.version_no")
        .isEqualTo(2)
        .jsonPath("$.status")
        .isEqualTo("in_progress")
        .jsonPath("$.reopen_reason")
        .isEqualTo("New evidence arrived.");
    org.assertj.core.api.Assertions.assertThat(
            TestUsers.scalar("SELECT status FROM tenders WHERE id = ?", w.tender()))
        .isEqualTo("consolidation");
    // the Chair works on version 2 with the decision copied; members still read version 1
    call("GET", base(w), w.chair())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.version_no")
        .isEqualTo(2)
        .jsonPath("$.disagreement_count")
        .isEqualTo(0);
    call("GET", base(w), w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.version_no")
        .isEqualTo(1)
        .jsonPath("$.status")
        .isEqualTo("finalized");
    call("GET", base(w) + "/matrix?version=2", w.m1()).exchange().expectStatus().isNotFound();
    call("POST", base(w) + "/finalize", w.chair())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.version_no")
        .isEqualTo(2);
    call("GET", base(w) + "/versions", w.observer())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.length()")
        .isEqualTo(2)
        .jsonPath("$[0].status")
        .isEqualTo("finalized")
        .jsonPath("$[1].status")
        .isEqualTo("superseded");
    call("GET", base(w) + "/matrix?version=1", w.m2()).exchange().expectStatus().isOk();
  }

  @Test
  void aVendorsFinalGradeIsNeverVisibleBeforeEveryMemberHasSubmitted() {
    World w = ReportWorld.create();
    TestUsers.sql(
        "UPDATE member_evaluations SET status = 'reopened', reopened_at = now(),"
            + " reopen_reason = 'Please look again at this.', reopened_by = ?"
            + " WHERE tender_vendor_id = ? AND user_id = ?",
        w.chair().id(),
        w.tv(),
        w.m2().id());
    call("GET", base(w) + "/matrix", w.chair())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.rows[0].cells[0].available")
        .isEqualTo(false);
    call("GET", base(w) + "/overview", w.admin())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.vendors[0].available")
        .isEqualTo(false)
        .jsonPath("$.vendors[0].awaiting.length()")
        .isEqualTo(1);
    decide(w, w.chair(), 409);
  }

  @Test
  void aFinalGradeAndFinalizeAndReopenPublishReportChangedAfterTheCommit() throws Exception {
    World w = ReportWorld.create();
    decide(w, w.chair(), 200);
    call("POST", base(w) + "/finalize", w.chair()).exchange().expectStatus().isOk();
    call("POST", base(w) + "/reopen", w.chair())
        .bodyValue(Map.of("reason", "A late clarification changes the grade"))
        .exchange()
        .expectStatus()
        .isCreated();
    org.mockito.ArgumentCaptor<io.nats.client.Message> sent =
        org.mockito.ArgumentCaptor.forClass(io.nats.client.Message.class);
    org.mockito.Mockito.verify(js, org.mockito.Mockito.atLeast(3)).publish(sent.capture());
    List<String> reasons =
        sent.getAllValues().stream()
            .filter(m -> m.getSubject().equals("ui.push.tender." + w.tender()))
            .map(m -> new String(m.getData(), java.nio.charset.StandardCharsets.UTF_8))
            .filter(b -> b.contains("\"report.changed\""))
            .toList();
    org.assertj.core.api.Assertions.assertThat(reasons)
        .anyMatch(b -> b.contains("final_grade_recorded"))
        .anyMatch(b -> b.contains("report_finalized"))
        .anyMatch(b -> b.contains("report_reopened"))
        .noneMatch(b -> b.contains("partially_compliant") || b.contains("Backups"));
  }

  private WebTestClient.RequestBodySpec withMatch(String uri, TestUser u, String match) {
    WebTestClient.RequestBodySpec c = call("PUT", uri, u);
    return match == null ? c : (WebTestClient.RequestBodySpec) c.header("If-Match", match);
  }

  private static Map<String, String> grade(String g, String why) {
    return Map.of("final_grade", g, "justification", why);
  }

  @Test
  void changingARecordedFinalGradeNeedsItsVersionAndKeepsTheEarlierDecision() {
    World w = ReportWorld.create();
    String uri = base(w) + "/grades/" + w.tv() + "/" + w.r1();
    // the first decision needs no version
    byte[] first =
        withMatch(uri, w.chair(), null)
            .bodyValue(grade("partially_compliant", "Backups only is partial coverage."))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .exists("ETag")
            .expectBody()
            .jsonPath("$.row_version")
            .isEqualTo(1)
            .jsonPath("$.history.length()")
            .isEqualTo(0)
            .returnResult()
            .getResponseBody();
    org.assertj.core.api.Assertions.assertThat(first).isNotNull();
    // a change without the version is refused, and so is a stale one
    withMatch(uri, w.chair(), null)
        .bodyValue(grade("non_compliant", "On second reading nothing is encrypted."))
        .exchange()
        .expectStatus()
        .isEqualTo(428);
    withMatch(uri, w.chair(), "\"7\"")
        .bodyValue(grade("non_compliant", "On second reading nothing is encrypted."))
        .exchange()
        .expectStatus()
        .isEqualTo(412);
    // with the right version it is saved, the version moves on, and the first decision is history
    withMatch(uri, w.chair(), "\"1\"")
        .bodyValue(grade("non_compliant", "On second reading nothing is encrypted."))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.final_grade")
        .isEqualTo("non_compliant")
        .jsonPath("$.row_version")
        .isEqualTo(2)
        .jsonPath("$.history.length()")
        .isEqualTo(1)
        .jsonPath("$.history[0].final_grade")
        .isEqualTo("partially_compliant")
        .jsonPath("$.history[0].justification")
        .isEqualTo("Backups only is partial coverage.")
        .jsonPath("$.history[0].decided_by.id")
        .isEqualTo(w.chair().id().toString());
    // the second tab, still holding version 1, cannot overwrite it
    withMatch(uri, w.chair(), "\"1\"")
        .bodyValue(grade("fully_compliant", "A stale tab saving over the decision."))
        .exchange()
        .expectStatus()
        .isEqualTo(412);
    // members read the history once the report is finalized
    call("GET", base(w) + "/matrix/" + w.r1() + "/" + w.tv(), w.m1())
        .exchange()
        .expectStatus()
        .isNotFound();
    call("POST", base(w) + "/finalize", w.chair()).exchange().expectStatus().isOk();
    call("GET", base(w) + "/matrix/" + w.r1() + "/" + w.tv(), w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.history.length()")
        .isEqualTo(1)
        .jsonPath("$.history[0].final_grade")
        .isEqualTo("partially_compliant");
  }

  @Test
  void aReopenedReportKeepsTheOriginalDeciderUntilTheCurrentChairChangesIt() {
    World w = ReportWorld.create();
    String uri = base(w) + "/grades/" + w.tv() + "/" + w.r1();
    withMatch(uri, w.chair(), null)
        .bodyValue(grade("partially_compliant", "Backups only is partial coverage."))
        .exchange()
        .expectStatus()
        .isOk();
    call("POST", base(w) + "/finalize", w.chair()).exchange().expectStatus().isOk();
    // another member becomes the Chair of the tender, then reopens
    TestUsers.sql(
        "UPDATE tender_members SET is_chair = false WHERE tender_id = ? AND user_id = ?",
        w.tender(),
        w.chair().id());
    TestUsers.sql(
        "UPDATE tender_members SET is_chair = true WHERE tender_id = ? AND user_id = ?",
        w.tender(),
        w.m1().id());
    call("POST", base(w) + "/reopen", w.m1())
        .bodyValue(Map.of("reason", "New evidence arrived."))
        .exchange()
        .expectStatus()
        .isCreated();
    String cell = base(w) + "/matrix/" + w.r1() + "/" + w.tv();
    // the carried decision is still the earlier Chair's, at the earlier time
    call("GET", cell, w.m1())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.decided_by.id")
        .isEqualTo(w.chair().id().toString())
        .jsonPath("$.row_version")
        .isEqualTo(1);
    // changing it is the new Chair's decision, in their name; the earlier one goes to the history
    withMatch(uri, w.m1(), "\"1\"")
        .bodyValue(grade("fully_compliant", "The clarification shows full coverage."))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.decided_by.id")
        .isEqualTo(w.m1().id().toString())
        .jsonPath("$.history[0].decided_by.id")
        .isEqualTo(w.chair().id().toString());
  }
}
