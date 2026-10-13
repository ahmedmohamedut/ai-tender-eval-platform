package app.tendereval.backend.messaging;

import app.tendereval.backend.jobs.Outcome;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A durable pull consumer of ANALYSIS_EVENTS (PI 5.3, 5.8). It pulls on its own thread (the NATS
 * client blocks), acknowledges only after the handler has finished, returns a message with backoff
 * when the handler is not ready, and copies a message it cannot use to the dead-letter stream.
 */
public abstract class AnalysisConsumer implements SmartLifecycle {

  public static final String STREAM = "ANALYSIS_EVENTS";
  public static final long MAX_DELIVER = 10;

  /** Backoff by delivery count: 1 s, 5 s, 30 s, 2 min, 10 min (PI 5.8). */
  static final Duration[] BACKOFF = {
    Duration.ofSeconds(1),
    Duration.ofSeconds(5),
    Duration.ofSeconds(30),
    Duration.ofMinutes(2),
    Duration.ofMinutes(10)
  };

  private final Logger log = LoggerFactory.getLogger(getClass());
  private final JetStream js;
  private final JetStreamManagement jsm;
  private final String durable;
  private final ConsumerConfiguration configuration;
  protected final JsonMapper json = JsonMapper.builder().build();

  private volatile boolean running;
  private Thread thread;

  protected AnalysisConsumer(
      JetStream js, JetStreamManagement jsm, String durable, ConsumerConfiguration configuration) {
    this.js = js;
    this.jsm = jsm;
    this.durable = durable;
    this.configuration = configuration;
  }

  /**
   * Handles one readable message body. {@code body} is valid JSON; an unknown schema was already
   * refused.
   */
  protected abstract Outcome process(Message message, JsonNode body);

  /** Called when the last delivery is reached without success; marks the job failed. */
  protected abstract void giveUp(JsonNode body, String reason);

  protected abstract boolean knownSchema(String header);

  @Override
  public void start() {
    try {
      ensureConsumer();
      JetStreamSubscription sub = js.subscribe(null, PullSubscribeOptions.bind(STREAM, durable));
      running = true;
      thread = new Thread(() -> loop(sub), durable + "-consumer");
      thread.setDaemon(true);
      thread.start();
    } catch (Exception e) {
      throw new IllegalStateException("cannot start the consumer " + durable, e);
    }
  }

  private void ensureConsumer() throws Exception {
    try {
      jsm.getConsumerInfo(STREAM, durable);
    } catch (JetStreamApiException e) {
      if (e.getApiErrorCode() == 10014 || e.getErrorCode() == 404) {
        jsm.addOrUpdateConsumer(STREAM, configuration);
        log.info("consumer created durable={}", durable);
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
          log.warn("fetch failed: {}", e.getClass().getSimpleName());
          sleep(1000);
        }
      }
    }
  }

  void handle(Message m) {
    JsonNode body = null;
    try {
      String schema = m.getHeaders() == null ? null : m.getHeaders().getFirst("Tender-Schema");
      if (schema != null && !knownSchema(schema)) {
        deadLetter(m, "unknown schema " + schema);
        return;
      }
      body = json.readTree(m.getData());
      Outcome outcome = process(m, body);
      switch (outcome) {
        case Outcome.Ack a -> {
          log.info("handled subject={} note={}", m.getSubject(), a.note());
          m.ack();
        }
        case Outcome.Retry r -> retry(m, body, r.delay(), r.note());
        case Outcome.DeadLetter d -> deadLetter(m, d.reason());
      }
    } catch (tools.jackson.core.JacksonException e) {
      deadLetter(m, "unreadable body");
    } catch (Exception e) {
      log.warn("handler failed subject={} error={}", m.getSubject(), e.getClass().getSimpleName());
      retry(m, body, null, "handler error");
    }
  }

  private void retry(Message m, JsonNode body, Duration delay, String note) {
    long delivered = m.metaData().deliveredCount();
    if (delivered >= MAX_DELIVER) {
      if (body != null) {
        try {
          giveUp(body, note);
        } catch (Exception e) {
          log.warn("could not mark the job failed: {}", e.getClass().getSimpleName());
        }
      }
      deadLetter(m, "not handled after " + MAX_DELIVER + " deliveries: " + note);
      return;
    }
    Duration wait =
        delay != null
            ? delay
            : BACKOFF[
                (int) Math.min(delivered, BACKOFF.length) - 1 < 0
                    ? 0
                    : (int) Math.min(delivered, BACKOFF.length) - 1];
    m.nakWithDelay(wait);
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
