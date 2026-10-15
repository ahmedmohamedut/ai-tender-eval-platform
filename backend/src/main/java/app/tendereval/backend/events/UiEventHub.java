package app.tendereval.backend.events;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.MessageInfo;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Reads UI_EVENTS once per backend instance (PI 5.10) and hands each event to the connections that
 * are open here. The SSE id of an event is its stream sequence number, so a reconnecting browser
 * can ask for what follows its last id.
 */
@Component
public class UiEventHub implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(UiEventHub.class);
  static final String STREAM = "UI_EVENTS";
  static final int REPLAY_CAP = 1000;

  /** One push event of a tender. */
  public record UiEvent(long seq, UUID tenderId, String type, String json, UUID userId) {
    public UiEvent(long seq, UUID tenderId, String type, String json) {
      this(seq, tenderId, type, json, null);
    }
  }

  private final Connection connection;
  private final JetStream js;
  private final JetStreamManagement jsm;
  private final Sinks.Many<UiEvent> sink = Sinks.many().multicast().directBestEffort();
  private final tools.jackson.databind.json.JsonMapper mapper =
      tools.jackson.databind.json.JsonMapper.builder().build();
  private volatile boolean running;
  private Dispatcher dispatcher;
  private JetStreamSubscription subscription;
  private JetStreamSubscription userSubscription;

  public UiEventHub(Connection connection, JetStream js, JetStreamManagement jsm) {
    this.connection = connection;
    this.js = js;
    this.jsm = jsm;
  }

  @Override
  public void start() {
    try {
      ConsumerConfiguration cc =
          ConsumerConfiguration.builder()
              .ackPolicy(AckPolicy.None)
              .deliverPolicy(DeliverPolicy.New)
              .build();
      dispatcher = connection.createDispatcher();
      subscription =
          js.subscribe(
              "ui.push.tender.*",
              dispatcher,
              this::onMessage,
              false,
              PushSubscribeOptions.builder().stream(STREAM).configuration(cc).build());
      userSubscription =
          js.subscribe(
              "ui.push.user.*",
              dispatcher,
              this::onMessage,
              false,
              PushSubscribeOptions.builder().stream(STREAM).configuration(cc).build());
      running = true;
    } catch (Exception e) {
      throw new IllegalStateException("cannot start the UI event hub", e);
    }
  }

  private void onMessage(Message m) {
    try {
      UiEvent e = toEvent(m.metaData().streamSequence(), m.getSubject(), m.getData());
      if (e != null) {
        sink.tryEmitNext(e);
      }
    } catch (Exception ex) {
      log.warn("push event dropped: {}", ex.getClass().getSimpleName());
    }
  }

  UiEvent toEvent(long seq, String subject, byte[] data) {
    String last = subject.substring(subject.lastIndexOf('.') + 1);
    UUID id;
    try {
      id = UUID.fromString(last);
    } catch (IllegalArgumentException e) {
      return null;
    }
    String json = new String(data, StandardCharsets.UTF_8);
    boolean user = subject.startsWith("ui.push.user.");
    String type =
        mapper
            .readTree(json)
            .path("type")
            .asString(user ? "notification.created" : "tender.changed");
    return user ? new UiEvent(seq, null, type, json, id) : new UiEvent(seq, id, type, json, null);
  }

  /** Live events of this instance. */
  public Flux<UiEvent> live() {
    return sink.asFlux();
  }

  public long lastSequence() throws Exception {
    return jsm.getStreamInfo(STREAM).getStreamState().getLastSequence();
  }

  /**
   * Events after {@code afterSeq} that the stream still holds, or null when the window has passed
   * (the browser then gets a resync).
   */
  public List<UiEvent> replayAfter(long afterSeq, long upToSeq) throws Exception {
    long first = jsm.getStreamInfo(STREAM).getStreamState().getFirstSequence();
    if (upToSeq <= afterSeq) {
      return List.of();
    }
    if (afterSeq + 1 < first || upToSeq - afterSeq > REPLAY_CAP) {
      return null;
    }
    List<UiEvent> out = new ArrayList<>();
    for (long seq = afterSeq + 1; seq <= upToSeq; seq++) {
      try {
        MessageInfo info = jsm.getMessage(STREAM, seq);
        UiEvent e = toEvent(seq, info.getSubject(), info.getData());
        if (e != null) {
          out.add(e);
        }
      } catch (io.nats.client.JetStreamApiException missing) {
        // an event of another subject type or one that expired: nothing to replay
      }
    }
    return out;
  }

  @Override
  public void stop() {
    running = false;
    try {
      if (dispatcher != null) {
        connection.closeDispatcher(dispatcher);
      }
    } catch (Exception ignored) {
      // closing
    }
    sink.tryEmitComplete();
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
