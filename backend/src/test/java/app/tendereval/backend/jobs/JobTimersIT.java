package app.tendereval.backend.jobs;

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
import io.nats.client.JetStream;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

/** PI 5.8: the queued, silence and ceiling timers, and the version reconciliation. */
class JobTimersIT extends AbstractApiIT {

  @Autowired JobTimers timers;
  @Autowired JetStream js;

  private record Setup(UUID tender, UUID version, UUID job) {}

  @BeforeEach
  void quiet() throws Exception {
    // Any job a previous test left open must not be touched by this test's assertions.
    when(js.publish(any(Message.class), any(PublishOptions.class)))
        .thenReturn(mock(PublishAck.class));
    clearInvocations(js);
  }

  private Setup setup(String jobStatus) {
    TestUser admin = TestUsers.create("admin");
    UUID tender = Fixtures.tender(admin, TestUsers.create("member"));
    UUID doc =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'brd', 'D') RETURNING id",
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
    if ("running".equals(jobStatus)) {
      TestUsers.sql(
          "UPDATE jobs SET status = 'running', started_at = now(), last_status_at = now() WHERE id = ?",
          job);
    }
    return new Setup(tender, v, job);
  }

  private void age(Setup s, String column, String interval) {
    TestUsers.sql(
        "UPDATE jobs SET " + column + " = now() - interval '" + interval + "' WHERE id = ?",
        s.job());
  }

  private String job(Setup s, String column) {
    return String.valueOf(
        TestUsers.scalar("SELECT " + column + " FROM jobs WHERE id = ?", s.job()));
  }

  @Test
  void aQueuedJobPastTheTimeoutHasItsCommandPublishedOnce() throws Exception {
    Setup s = setup("queued");
    age(s, "queued_at", "5 minutes");
    timers.sweep().block();

    ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
    verify(js, atLeastOnce()).publish(sent.capture(), any(PublishOptions.class));
    Message m =
        sent.getAllValues().stream()
            .filter(x -> new String(x.getData()).contains(s.job().toString()))
            .findFirst()
            .orElseThrow();
    assertThat(m.getSubject()).isEqualTo("documents.analysis.requested.document_processing");
    String body = new String(m.getData());
    assertThat(body).contains("\"job_type\":\"document_processing\"");
    assertThat(body).contains(s.version().toString());
    assertThat(body).contains("originals/" + s.tender());
    assertThat(m.getHeaders().getFirst("Nats-Msg-Id")).startsWith("job:" + s.job());
    assertThat(job(s, "command_published_at IS NOT NULL")).isEqualTo("true");

    // The same job is not published again before another timeout has passed.
    clearInvocations(js);
    timers.sweep().block();
    ArgumentCaptor<Message> again = ArgumentCaptor.forClass(Message.class);
    verify(js, org.mockito.Mockito.atMost(100)).publish(again.capture(), any(PublishOptions.class));
    assertThat(
            again.getAllValues().stream()
                .anyMatch(x -> new String(x.getData()).contains(s.job().toString())))
        .isFalse();
  }

  @Test
  void aYoungQueuedJobIsLeftAlone() throws Exception {
    Setup s = setup("queued");
    timers.sweep().block();
    ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
    verify(js, org.mockito.Mockito.atMost(100)).publish(sent.capture(), any(PublishOptions.class));
    assertThat(
            sent.getAllValues().stream()
                .anyMatch(x -> new String(x.getData()).contains(s.job().toString())))
        .isFalse();
    assertThat(job(s, "status")).isEqualTo("queued");
  }

  @Test
  void aSilentRunningJobIsFailedWithTimeoutAndItsVersionToo() {
    Setup s = setup("running");
    age(s, "last_status_at", "10 minutes");
    timers.sweep().block();
    assertThat(job(s, "status")).isEqualTo("failed");
    assertThat(job(s, "failure_code")).isEqualTo("timeout");
    assertThat(
            TestUsers.scalar(
                "SELECT status || ':' || failure_reason FROM document_versions WHERE id = ?",
                s.version()))
        .isEqualTo("failed:timeout");
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM audit_events WHERE object_id = ? AND action = 'job_status_changed'"
                    + " AND source = 'system'",
                s.job()))
        .isEqualTo(1L);
  }

  @Test
  void aRunningJobThatReportedRecentlyIsLeftAlone() {
    Setup s = setup("running");
    timers.sweep().block();
    assertThat(job(s, "status")).isEqualTo("running");
  }

  @Test
  void aJobOlderThanTheCeilingForItsTypeIsFailed() {
    Setup s = setup("running");
    age(s, "queued_at", "40 minutes"); // the document processing ceiling is 30
    timers.sweep().block();
    assertThat(job(s, "status")).isEqualTo("failed");
    assertThat(job(s, "failure_code")).isEqualTo("timeout");
  }

  @Test
  void aVersionLeftProcessingByACancelledJobIsFailed() {
    Setup s = setup("queued");
    TestUsers.sql(
        "UPDATE jobs SET status = 'cancelled', cancelled_by = started_by, finished_at = now() WHERE id = ?",
        s.job());
    assertThat(TestUsers.scalar("SELECT status FROM document_versions WHERE id = ?", s.version()))
        .isEqualTo("processing");
    timers.sweep().block();
    assertThat(TestUsers.scalar("SELECT status FROM document_versions WHERE id = ?", s.version()))
        .isEqualTo("failed");
  }
}
