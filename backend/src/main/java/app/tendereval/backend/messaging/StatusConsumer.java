package app.tendereval.backend.messaging;

import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.ReplayPolicy;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The backend's {@code backend-status} consumer (PI 5.3). It creates the consumer when missing,
 * pulls on its own thread (the NATS client blocks), copies unreadable messages to the dead-letter
 * stream and acknowledges only after the message has been handled.
 */
@Component
public class StatusConsumer implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(StatusConsumer.class);

  /** Same values as infra/nats/consumers/ANALYSIS_EVENTS.backend-status.json (tested). */
  public static final String STREAM = "ANALYSIS_EVENTS";

  public static final String DURABLE = "backend-status";
  public static final Duration ACK_WAIT = Duration.ofSeconds(30);
  public static final long MAX_ACK_PENDING = 64;
  public static final long MAX_DELIVER = 10;

  private final JetStream js;
  private final JetStreamManagement jsm;
  private final StatusStore store;
  private final JsonMapper json = JsonMapper.builder().build();

  private volatile boolean running;
  private Thread thread;

  public StatusConsumer(JetStream js, JetStreamManagement jsm, StatusStore store) {
    this.js = js;
    this.jsm = jsm;
    this.store = store;
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
  public void start() {
    try {
      ensureConsumer();
      JetStreamSubscription sub = js.subscribe(null, PullSubscribeOptions.bind(STREAM, DURABLE));
      running = true;
      thread = new Thread(() -> loop(sub), "backend-status-consumer");
      thread.setDaemon(true);
      thread.start();
    } catch (Exception e) {
      throw new IllegalStateException("cannot start the status consumer", e);
    }
  }

  private void ensureConsumer() throws Exception {
    try {
      jsm.getConsumerInfo(STREAM, DURABLE);
    } catch (JetStreamApiException e) {
      if (e.getApiErrorCode() == 10014 || e.getErrorCode() == 404) {
        jsm.addOrUpdateConsumer(STREAM, configuration());
        log.info("consumer created durable={}", DURABLE);
      } else {
        throw e;
      }
    }
  }

  private void loop(JetStreamSubscription sub) {
    while (running) {
      try {
        List<Message> batch = sub.fetch(16, Duration.ofSeconds(2));
        for (Message m : batch) {
          handle(m);
        }
      } catch (Exception e) {
        if (running) {
          log.warn("status fetch failed: {}", e.getClass().getSimpleName());
          sleep(1000);
        }
      }
    }
  }

  void handle(Message m) {
    try {
      String schema = m.getHeaders() == null ? null : m.getHeaders().getFirst("Tender-Schema");
      if (schema != null && !isKnownSchema(schema)) {
        deadLetter(m, "unknown schema " + schema);
        return;
      }
      JsonNode body = json.readTree(m.getData());
      String state = text(body, "state");
      JsonNode seq = body.get("seq");
      if (state == null || seq == null || !seq.isNumber() || text(body, "job_type") == null) {
        deadLetter(m, "invalid status body");
        return;
      }
      String jobId = text(body, "job_id");
      String key = jobId != null ? jobId : "dv:" + text(body.get("target"), "id");
      boolean changed = store.record(key, state, seq.asLong(), text(body, "step"));
      log.info(
          "status stored changed={} state={} seq={} subject={}",
          changed,
          state,
          seq.asLong(),
          m.getSubject());
      m.ack();
    } catch (Exception e) {
      if (m.metaData().deliveredCount() >= MAX_DELIVER) {
        deadLetter(m, "unreadable after " + MAX_DELIVER + " deliveries");
      } else {
        m.nakWithDelay(Duration.ofSeconds(5));
      }
    }
  }

  private static boolean isKnownSchema(String header) {
    int slash = header.indexOf('/');
    return slash > 0
        && header.substring(0, slash).equals(Subjects.SCHEMA_STATUS_NAME)
        && header.substring(slash + 1).equals(Subjects.SCHEMA_STATUS_MAJOR);
  }

  private static String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode v = node.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }

  private void deadLetter(Message m, String reason) {
    try {
      Headers h = new Headers();
      h.add("Dlq-Reason", reason);
      h.add("Dlq-Original-Subject", m.getSubject());
      h.add("Dlq-Deliveries", String.valueOf(m.metaData().deliveredCount()));
      long streamSeq = m.metaData().streamSequence();
      js.publish(
          NatsMessage.builder()
              .subject("dlq." + m.getSubject())
              .headers(h)
              .data(m.getData())
              .build(),
          PublishOptions.builder().messageId("dlq:" + STREAM + ":" + streamSeq).build());
      m.term();
      log.warn("dead letter subject={} reason={}", m.getSubject(), reason);
    } catch (Exception e) {
      m.nakWithDelay(Duration.ofSeconds(30));
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void stop() {
    running = false;
    if (thread != null) {
      thread.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
