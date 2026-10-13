package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** UX-JOB-01..07, UX-DOC-03: the job center and the retry of document processing. */
class JobsApiIT extends AbstractApiIT {

  private record Setup(UUID tender, UUID document, UUID version, UUID job) {}

  private Setup setup(UUID tender, TestUser admin) {
    UUID doc =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'brd', 'Terms') RETURNING id",
                tender);
    UUID v = UUID.randomUUID();
    TestUsers.sql(
        "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format,"
            + " size_bytes, sha256, storage_key, status, uploaded_by) VALUES (?, ?, 1, 'a.pdf',"
            + " 'pdf', 10, repeat('a', 64), ?, 'uploading', ?)",
        v,
        doc,
        "originals/%s/%s/%s.pdf".formatted(tender, doc, v),
        admin.id());
    TestUsers.sql("UPDATE document_versions SET status = 'processing' WHERE id = ?", v);
    UUID job =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id, started_by)"
                    + " VALUES (?, 'document_processing', 'document_version', ?, ?) RETURNING id",
                tender,
                v,
                admin.id());
    return new Setup(tender, doc, v, job);
  }

  private void fail(Setup s, String code) {
    TestUsers.sql("UPDATE jobs SET status = 'running' WHERE id = ?", s.job());
    TestUsers.sql(
        "UPDATE jobs SET status = 'failed', failure_code = ?, failure_message = 'x', finished_at = now()"
            + " WHERE id = ?",
        code,
        s.job());
    TestUsers.sql(
        "UPDATE document_versions SET status = 'failed', failure_reason = ? WHERE id = ?",
        code,
        s.version());
  }

  @Test
  void aMemberSeesTheJobsOfTheirTenderWithStepsAndLabels() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser member = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, member);
    Setup s = setup(tender, admin);
    get("/jobs/" + s.job(), chair).exchange().expectBody().jsonPath("$.can_cancel").isEqualTo(true);
    TestUsers.sql(
        "INSERT INTO job_steps (job_id, step_no, name) VALUES (?, 1, 'fetch'), (?, 2, 'parse')",
        s.job(),
        s.job());

    get("/jobs/" + s.job(), member)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("queued")
        .jsonPath("$.job_type")
        .isEqualTo("document_processing")
        .jsonPath("$.target_label")
        .isEqualTo("Terms (version 1)")
        .jsonPath("$.steps[1].name")
        .isEqualTo("parse")
        .jsonPath("$.can_cancel")
        .isEqualTo(false) // neither the starter nor the Chair
        .jsonPath("$.can_retry")
        .isEqualTo(false);

    get("/jobs?tender_id=" + tender + "&active=true", member)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(1)
        .jsonPath("$.items[0].id")
        .isEqualTo(s.job().toString());
  }

  @Test
  void aPersonOutsideTheTenderSeesNoJobAndCannotCancel() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = Fixtures.tender(admin, TestUsers.create("member"));
    Setup s = setup(tender, admin);
    TestUser outsider = TestUsers.create("member");
    get("/jobs/" + s.job(), outsider).exchange().expectStatus().isNotFound();
    get("/jobs?tender_id=" + tender, outsider)
        .exchange()
        .expectBody()
        .jsonPath("$.total")
        .isEqualTo(0);
    post("/jobs/" + s.job() + "/cancel", outsider).exchange().expectStatus().isNotFound();
    assertThat(TestUsers.scalar("SELECT status FROM jobs WHERE id = ?", s.job()))
        .isEqualTo("queued");
  }

  @Test
  void theChairCancelsAJobAndTheVersionIsSettled() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair);
    Setup s = setup(tender, admin);

    post("/jobs/" + s.job() + "/cancel", chair)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("cancelled")
        .jsonPath("$.can_cancel")
        .isEqualTo(false);
    assertThat(TestUsers.scalar("SELECT cancelled_by FROM jobs WHERE id = ?", s.job()))
        .isEqualTo(chair.id());
    assertThat(TestUsers.scalar("SELECT status FROM document_versions WHERE id = ?", s.version()))
        .isEqualTo("failed");
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM audit_events WHERE action = 'job_cancelled' AND object_id = ?",
                s.job()))
        .isEqualTo(1L);

    // a second cancel is a conflict, not a second event
    post("/jobs/" + s.job() + "/cancel", chair)
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("job_finished");
  }

  @Test
  void anotherMemberOfTheTenderCannotCancelSomeoneElsesJob() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    TestUser other = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair, other);
    Setup s = setup(tender, admin);
    post("/jobs/" + s.job() + "/cancel", other).exchange().expectStatus().isForbidden();
    assertThat(TestUsers.scalar("SELECT status FROM jobs WHERE id = ?", s.job()))
        .isEqualTo("queued");
  }

  @Test
  void anAdministratorRetriesATemporaryFailureWithANewJob() {
    TestUser admin = TestUsers.create("admin");
    UUID tender = Fixtures.tender(admin, TestUsers.create("member"));
    Setup s = setup(tender, admin);
    fail(s, "source_unavailable");

    get("/jobs/" + s.job(), admin)
        .exchange()
        .expectBody()
        .jsonPath("$.failure.code")
        .isEqualTo("source_unavailable")
        .jsonPath("$.failure.suggested_actions[0]")
        .isEqualTo("retry")
        .jsonPath("$.can_retry")
        .isEqualTo(true);

    var r =
        post("/document-versions/" + s.version() + "/retry", admin)
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectHeader()
            .exists("Location")
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("queued")
            .jsonPath("$.retry_of_job_id")
            .isEqualTo(s.job().toString())
            .returnResult();
    assertThat(TestUsers.scalar("SELECT status FROM document_versions WHERE id = ?", s.version()))
        .isEqualTo("processing");
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM jobs WHERE target_id = ? AND status = 'queued'", s.version()))
        .isEqualTo(1L);

    // while a job is open, a second retry is refused
    post("/document-versions/" + s.version() + "/retry", admin)
        .exchange()
        .expectStatus()
        .isEqualTo(409);
  }

  @Test
  void aFileCauseIsNotRetriedAndAMemberCannot() {
    TestUser admin = TestUsers.create("admin");
    TestUser chair = TestUsers.create("member");
    UUID tender = Fixtures.tender(admin, chair);
    Setup s = setup(tender, admin);
    fail(s, "password_protected");

    post("/document-versions/" + s.version() + "/retry", admin)
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("not_retryable");
    post("/document-versions/" + s.version() + "/retry", chair)
        .exchange()
        .expectStatus()
        .isForbidden();
    get("/jobs/" + s.job(), admin)
        .exchange()
        .expectBody()
        .jsonPath("$.can_retry")
        .isEqualTo(false)
        .jsonPath("$.failure.message")
        .value(m -> assertThat((String) m).contains("password protected"));
  }
}
