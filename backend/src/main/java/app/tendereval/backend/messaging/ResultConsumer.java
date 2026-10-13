package app.tendereval.backend.messaging;

import app.tendereval.backend.jobs.JobProcessor;
import app.tendereval.backend.jobs.Outcome;
import app.tendereval.backend.jobs.ParsedResultProcessor;
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

/** The backend's {@code backend-results} consumer (PI 5.3, 5.6): stores result batches. */
@Component
public class ResultConsumer extends AnalysisConsumer {

  /** Same values as infra/nats/consumers/ANALYSIS_EVENTS.backend-results.json (tested). */
  public static final String DURABLE = "backend-results";

  private final StatusStore store;
  private final ParsedResultProcessor parsed;
  private final JobProcessor jobs;

  public ResultConsumer(
      JetStream js,
      JetStreamManagement jsm,
      StatusStore store,
      ParsedResultProcessor parsed,
      JobProcessor jobs) {
    super(js, jsm, DURABLE, configuration());
    this.store = store;
    this.parsed = parsed;
    this.jobs = jobs;
  }

  public static ConsumerConfiguration configuration() {
    return ConsumerConfiguration.builder()
        .durable(DURABLE)
        .ackPolicy(AckPolicy.Explicit)
        .ackWait(Duration.ofSeconds(30))
        .maxAckPending(16)
        .maxDeliver(MAX_DELIVER)
        .filterSubject("documents.analysis.result.>")
        .deliverPolicy(DeliverPolicy.All)
        .replayPolicy(ReplayPolicy.Instant)
        .build();
  }

  @Override
  protected boolean knownSchema(String header) {
    int slash = header.indexOf('/');
    return slash > 0 && header.substring(slash + 1).equals(Subjects.SCHEMA_STATUS_MAJOR);
  }

  @Override
  protected Outcome process(Message m, JsonNode body) {
    String id = StatusMessageIds.jobId(body);
    if (id != null && store.isDevJob(id)) {
      return Outcome.ack("walking slice job");
    }
    String kind = body.get("kind") == null ? null : body.get("kind").asString();
    if ("parsed".equals(kind)) {
      return parsed.apply(body).block(Duration.ofSeconds(120));
    }
    // Requirement, grade and pre-check batches arrive with Phases 3 to 5.
    return Outcome.deadLetter("no handler for result kind " + kind);
  }

  @Override
  protected void giveUp(JsonNode body, String reason) {
    String id = StatusMessageIds.jobId(body);
    if (id != null) {
      jobs.failById(UUID.fromString(id), "other", "The result could not be stored")
          .block(Duration.ofSeconds(30));
    }
  }
}
