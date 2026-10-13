package app.tendereval.backend.messaging;

import app.tendereval.backend.jobs.JobProcessor;
import app.tendereval.backend.jobs.Outcome;
import app.tendereval.backend.jobs.StatusMessage;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.Message;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.ReplayPolicy;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** The backend's {@code backend-status} consumer (PI 5.3): applies status messages to jobs. */
@Component
public class StatusConsumer extends AnalysisConsumer {

  /** Same values as infra/nats/consumers/ANALYSIS_EVENTS.backend-status.json (tested). */
  public static final String DURABLE = "backend-status";

  public static final Duration ACK_WAIT = Duration.ofSeconds(30);
  public static final long MAX_ACK_PENDING = 64;

  private final StatusStore store;
  private final JobProcessor processor;

  public StatusConsumer(
      JetStream js, JetStreamManagement jsm, StatusStore store, JobProcessor processor) {
    super(js, jsm, DURABLE, configuration());
    this.store = store;
    this.processor = processor;
  }

  public static ConsumerConfiguration configuration() {
    return ConsumerConfiguration.builder()
        .durable(DURABLE)
        .ackPolicy(AckPolicy.Explicit)
        .ackWait(ACK_WAIT)
        .maxAckPending(MAX_ACK_PENDING)
        .maxDeliver(MAX_DELIVER)
        .filterSubject(Subjects.STATUS_FILTER)
        .deliverPolicy(DeliverPolicy.All)
        .replayPolicy(ReplayPolicy.Instant)
        .build();
  }

  @Override
  protected boolean knownSchema(String header) {
    int slash = header.indexOf('/');
    return slash > 0
        && header.substring(0, slash).equals(Subjects.SCHEMA_STATUS_NAME)
        && header.substring(slash + 1).equals(Subjects.SCHEMA_STATUS_MAJOR);
  }

  @Override
  protected Outcome process(Message m, JsonNode body) {
    StatusMessage status;
    try {
      status = StatusMessage.parse(body);
    } catch (RuntimeException e) {
      return Outcome.deadLetter("invalid status body");
    }
    String key = status.jobId() != null ? status.jobId().toString() : "dv:" + status.targetId();
    store.record(key, status.state(), status.seq(), status.step());
    if (status.jobId() != null && store.isDevJob(status.jobId().toString())) {
      return Outcome.ack("walking slice job");
    }
    return processor.applyStatus(status).block(Duration.ofSeconds(30));
  }

  @Override
  protected void giveUp(JsonNode body, String reason) {
    String id = StatusMessageIds.jobId(body);
    if (id != null) {
      processor
          .failById(UUID.fromString(id), "other", "The result could not be stored")
          .block(Duration.ofSeconds(30));
    }
  }
}
