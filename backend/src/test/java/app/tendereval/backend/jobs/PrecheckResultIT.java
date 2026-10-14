package app.tendereval.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** PI 5.5 to 5.7 for kind prechecks: stored once, counted, the run recorded. */
class PrecheckResultIT extends AbstractApiIT {

  @Autowired JobProcessor jobs;
  @Autowired PrecheckResultProcessor results;

  private final JsonMapper json = JsonMapper.builder().build();

  private record Setup(UUID tender, UUID pv, UUID job) {}

  private Setup setup() {
    TestUser admin = TestUsers.create("admin");
    TestUser member = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, member);
    UUID v =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO vendors (name, normalized_name) VALUES ('Pc', ?) RETURNING id",
                UUID.randomUUID().toString());
    UUID tv =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO tender_vendors (tender_id, vendor_id, added_by) VALUES (?, ?, ?) RETURNING id",
                tender,
                v,
                admin.id());
    UUID pv =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by) VALUES (?, 1, ?) RETURNING id",
                tv,
                admin.id());
    UUID job =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id, started_by)"
                    + " VALUES (?, 'screening_precheck', 'proposal_version', ?, ?) RETURNING id",
                tender,
                pv,
                member.id());
    return new Setup(tender, pv, job);
  }

  private StatusMessage status(Setup s, long seq, String state, Map<String, Object> extra) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("job_id", s.job().toString());
    body.put("job_type", "screening_precheck");
    body.put("tender_id", s.tender().toString());
    body.put("target", Map.of("type", "proposal_version", "id", s.pv().toString()));
    body.put("seq", seq);
    body.put("state", state);
    body.putAll(extra);
    return StatusMessage.parse(json.readTree(json.writeValueAsString(body)));
  }

  private JsonNode batch(Setup s, UUID a, UUID b) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("job_id", s.job().toString());
    body.put("job_type", "screening_precheck");
    body.put("tender_id", s.tender().toString());
    body.put("target", Map.of("type", "proposal_version", "id", s.pv().toString()));
    body.put("kind", "prechecks");
    body.put("part_no", 1);
    body.put("final", true);
    body.put(
        "items",
        List.of(
            Map.of(
                "item_id",
                a.toString(),
                "expected_section",
                "Executive summary",
                "found",
                true,
                "note",
                "Executive summary (page 2)"),
            Map.of(
                "item_id",
                b.toString(),
                "expected_section",
                "Pricing",
                "found",
                false,
                "note",
                "No section of the proposal covers it.")));
    return json.readTree(json.writeValueAsString(body));
  }

  @Test
  void findingsAreStoredOnceAndTheJobCompletesWhenTheCountsMatch() {
    Setup s = setup();
    jobs.applyStatus(
            status(
                s,
                1,
                "running",
                Map.of(
                    "step",
                    "load_scope",
                    "progress_percent",
                    2,
                    "run",
                    Map.of("model_name", "stub", "prompt_version", "precheck-1.0.0"))))
        .block();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertThat(results.apply(batch(s, a, b)).block()).isInstanceOf(Outcome.Ack.class);
    assertThat(results.apply(batch(s, a, b)).block()).isInstanceOf(Outcome.Ack.class);
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM screening_prechecks WHERE proposal_version_id = ?", s.pv()))
        .isEqualTo(2L);

    // wrong count: held back
    Outcome wrong =
        jobs.applyStatus(
                status(
                    s, 2, "completed", Map.of("result_parts", 1, "counts", Map.of("prechecks", 3))))
            .block();
    assertThat(wrong).isInstanceOf(Outcome.Retry.class);
    assertThat(TestUsers.scalar("SELECT status FROM jobs WHERE id = ?", s.job()))
        .isEqualTo("running");
    // right count: completed, run finished
    Outcome ok =
        jobs.applyStatus(
                status(
                    s, 3, "completed", Map.of("result_parts", 1, "counts", Map.of("prechecks", 2))))
            .block();
    assertThat(ok).isInstanceOf(Outcome.Ack.class);
    assertThat(TestUsers.scalar("SELECT status FROM jobs WHERE id = ?", s.job()))
        .isEqualTo("completed");
    assertThat(TestUsers.scalar("SELECT outcome FROM ai_runs WHERE job_id = ?", s.job()))
        .isEqualTo("succeeded");
  }

  @Test
  void aResultForAnotherTenderOrBeforeTheRunIsRecordedIsNotStored() {
    Setup s = setup();
    // no first status yet: asked again later
    assertThat(results.apply(batch(s, UUID.randomUUID(), UUID.randomUUID())).block())
        .isInstanceOf(Outcome.Retry.class);
    Setup other = setup();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("job_id", s.job().toString());
    body.put("tender_id", other.tender().toString());
    body.put("kind", "prechecks");
    body.put("items", List.of());
    assertThat(results.apply(json.readTree(json.writeValueAsString(body))).block())
        .isInstanceOf(Outcome.DeadLetter.class);
  }
}
