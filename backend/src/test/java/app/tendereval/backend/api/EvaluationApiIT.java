package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import io.nats.client.JetStream;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** UX-EVA-01, UX-EVA-14, UX-JOB: the system starts an evaluation; a person re-runs what is left. */
class EvaluationApiIT extends AbstractApiIT {

  @Autowired JetStream js;

  @BeforeEach
  void publishWorks() throws Exception {
    when(js.publish(any(Message.class), any(PublishOptions.class)))
        .thenReturn(mock(PublishAck.class));
    clearInvocations(js);
  }

  private record World(
      TestUser admin,
      TestUser chair,
      TestUser member,
      UUID tender,
      UUID tv,
      UUID pv,
      UUID r1,
      UUID r2) {}

  private World world(boolean baseline) {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser member = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, member);
    TestUsers.sql("UPDATE tenders SET status = 'requirements' WHERE id = ?", tender);
    UUID r1 = requirement(tender, "Encrypt data at rest.");
    UUID r2 = requirement(tender, "Provide support all day.");
    if (baseline) {
      UUID b =
          (UUID)
              TestUsers.scalar(
                  "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by)"
                      + " VALUES (?, 1, 2, ?) RETURNING id",
                  tender,
                  chair.id());
      for (UUID r : List.of(r1, r2)) {
        TestUsers.sql(
            "INSERT INTO baseline_requirements (baseline_id, requirement_id, display_no, text,"
                + " importance, content_hash) VALUES (?, ?, 'REQ', 'x', 'mandatory', repeat('c', 64))",
            b,
            r);
      }
    }
    UUID vendor =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO vendors (name, normalized_name) VALUES ('Ev', ?) RETURNING id",
                UUID.randomUUID().toString());
    UUID tv =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO tender_vendors (tender_id, vendor_id, added_by) VALUES (?, ?, ?) RETURNING id",
                tender,
                vendor,
                admin.id());
    UUID pv =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by) VALUES (?, 1, ?) RETURNING id",
                tv,
                admin.id());
    TestUsers.sql("UPDATE proposal_versions SET status = 'in_screening' WHERE id = ?", pv);
    TestUsers.sql(
        "INSERT INTO conflict_declarations (tender_vendor_id, user_id, has_conflict) VALUES (?, ?, false)",
        tv,
        chair.id());
    return new World(admin, chair, member, tender, tv, pv, r1, r2);
  }

  private static UUID requirement(UUID tender, String text) {
    return (UUID)
        TestUsers.scalar(
            "INSERT INTO requirements (tender_id, text, importance, origin) VALUES (?, ?, 'mandatory',"
                + " 'manual') RETURNING id",
            tender,
            text);
  }

  private WebTestClient.RequestBodySpec call(String method, String uri, TestUser u) {
    return web.method(HttpMethod.valueOf(method))
        .uri(uri)
        .header("Authorization", "Bearer " + tokenFor(u))
        .header("Idempotency-Key", "e-" + UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON);
  }

  private void accept(World w) {
    call("POST", "/tender-vendors/" + w.tv() + "/screening/decision", w.chair())
        .bodyValue(Map.of("decision", "accept"))
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void acceptingWithAConfirmedBaselineQueuesTheEvaluationAndPublishesTheCommandAfterTheCommit()
      throws Exception {
    World w = world(true);
    accept(w);
    assertThat(TestUsers.scalar("SELECT status FROM proposal_versions WHERE id = ?", w.pv()))
        .isEqualTo("evaluating");
    Object job =
        TestUsers.scalar(
            "SELECT id::text FROM jobs WHERE target_id = ? AND job_type = 'evaluation'", w.pv());
    assertThat(job).isNotNull();
    assertThat(TestUsers.scalar("SELECT started_by FROM jobs WHERE id = ?::uuid", job)).isNull();
    ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
    verify(js, atLeastOnce()).publish(sent.capture(), any(PublishOptions.class));
    Message m =
        sent.getAllValues().stream()
            .filter(x -> new String(x.getData()).contains((String) job))
            .findFirst()
            .orElseThrow();
    assertThat(m.getSubject()).isEqualTo("documents.analysis.requested.evaluation");
    String body = new String(m.getData());
    assertThat(JsonPath.<String>read(body, "$.target.type")).isEqualTo("proposal_version");
    assertThat(JsonPath.<String>read(body, "$.input_ref.key"))
        .isEqualTo("jobs/" + job + "/input.json");
    assertThat(JsonPath.<Object>read(body, "$.requested_by")).isNull();
  }

  @Test
  void acceptingBeforeTheBaselineIsConfirmedWaits() {
    World w = world(false);
    accept(w);
    assertThat(TestUsers.scalar("SELECT status FROM proposal_versions WHERE id = ?", w.pv()))
        .isEqualTo("accepted");
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM jobs WHERE target_id = ? AND job_type = 'evaluation'",
                w.pv()))
        .isEqualTo(0L);
  }

  @Test
  void aMemberReRunsWhatStillNeedsAGradeAndNothingElse() throws Exception {
    World w = world(true);
    TestUsers.sql("UPDATE proposal_versions SET status = 'accepted' WHERE id = ?", w.pv());
    String uri = "/tender-vendors/" + w.tv() + "/evaluation/rerun";
    // not evaluated yet
    call("POST", uri, w.member())
        .bodyValue(Map.of("scope", "failed"))
        .exchange()
        .expectStatus()
        .isEqualTo(409);

    // an evaluated proposal with one graded and one failed requirement
    UUID oldJob =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id) VALUES (?, 'evaluation',"
                    + " 'proposal_version', ?) RETURNING id",
                w.tender(),
                w.pv());
    UUID run =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO ai_runs (job_id, run_type, model_name, prompt_version) VALUES (?,"
                    + " 'evaluation', 'stub', 'e-1') RETURNING id",
                oldJob);
    UUID baseline =
        (UUID) TestUsers.scalar("SELECT id FROM baselines WHERE tender_id = ?", w.tender());
    TestUsers.sql(
        "INSERT INTO ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id,"
            + " requirement_id, outcome, grade, non_compliance_reason, requirement_hash)"
            + " VALUES (gen_random_uuid(), ?, ?, ?, ?, 'graded', 'non_compliant', 'not_addressed',"
            + " repeat('c', 64))",
        run,
        w.pv(),
        baseline,
        w.r1());
    TestUsers.sql(
        "INSERT INTO ai_grades (source_item_id, ai_run_id, proposal_version_id, baseline_id,"
            + " requirement_id, outcome, failure_code, requirement_hash)"
            + " VALUES (gen_random_uuid(), ?, ?, ?, ?, 'failed', 'ungrounded', repeat('c', 64))",
        run,
        w.pv(),
        baseline,
        w.r2());
    TestUsers.sql("UPDATE jobs SET status = 'running', started_at = now() WHERE id = ?", oldJob);
    TestUsers.sql(
        "UPDATE jobs SET status = 'completed_with_warnings', finished_at = now() WHERE id = ?",
        oldJob);

    call("POST", uri, w.admin())
        .bodyValue(Map.of("scope", "failed"))
        .exchange()
        .expectStatus()
        .isForbidden();
    byte[] out =
        call("POST", uri, w.member())
            .bodyValue(Map.of("scope", "failed"))
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody()
            .jsonPath("$.job_type")
            .isEqualTo("evaluation")
            .returnResult()
            .getResponseBody();
    String job = JsonPath.read(new String(out), "$.id");
    // the input names only the failed requirement
    ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
    verify(js, atLeastOnce()).publish(sent.capture(), any(PublishOptions.class));
    assertThat(sent.getAllValues().stream().anyMatch(x -> new String(x.getData()).contains(job)))
        .isTrue();
    // a second re-run while one is open has nothing new to start
    call("POST", uri, w.member())
        .bodyValue(Map.of("scope", "failed"))
        .exchange()
        .expectStatus()
        .isEqualTo(409);
  }
}
