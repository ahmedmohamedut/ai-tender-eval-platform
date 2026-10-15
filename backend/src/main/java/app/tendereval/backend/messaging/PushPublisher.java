package app.tendereval.backend.messaging;

import io.nats.client.JetStream;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes a {@code PushEvent} to {@code ui.push.tender.{tender_id}} (PI 5.10). It is called after
 * the transaction that changed the data has committed, and a failure is logged and dropped: a
 * browser that misses an event refetches on its next resync.
 */
@Component
public class PushPublisher {

  private static final Logger log = LoggerFactory.getLogger(PushPublisher.class);

  private final JetStream js;
  private final JsonMapper json = JsonMapper.builder().build();

  public PushPublisher(JetStream js) {
    this.js = js;
  }

  public Mono<Void> tender(
      UUID tenderId, String type, String objectType, UUID objectId, Map<String, Object> data) {
    return Mono.fromCallable(
            () -> {
              Map<String, Object> body = new java.util.LinkedHashMap<>();
              body.put("type", type);
              body.put("tender_id", tenderId.toString());
              body.put("object_type", objectType);
              body.put("object_id", objectId == null ? null : objectId.toString());
              body.put("data", data == null ? Map.of() : data);
              Headers headers = new Headers();
              headers.add("Nats-Msg-Id", "push:" + UUID.randomUUID());
              NatsMessage message =
                  NatsMessage.builder()
                      .subject("ui.push.tender." + tenderId)
                      .headers(headers)
                      .data(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8))
                      .build();
              PublishAck ack = js.publish(message);
              return ack;
            })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(
            e ->
                log.warn(
                    "push event not published type={} error={}",
                    type,
                    e.getClass().getSimpleName()))
        .onErrorResume(e -> Mono.empty())
        .then();
  }

  /**
   * An event for one person ({@code ui.push.user.{id}}): a notification arrived. The body carries
   * ids only; the browser fetches the notification itself through row-level security.
   */
  public Mono<Void> user(UUID userId, String type, Map<String, Object> data) {
    return Mono.fromCallable(
            () -> {
              Map<String, Object> body = new java.util.LinkedHashMap<>();
              body.put("type", type);
              body.put("user_id", userId.toString());
              body.put("data", data == null ? Map.of() : data);
              Headers headers = new Headers();
              headers.add("Nats-Msg-Id", "push:" + UUID.randomUUID());
              NatsMessage message =
                  NatsMessage.builder()
                      .subject("ui.push.user." + userId)
                      .headers(headers)
                      .data(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8))
                      .build();
              return js.publish(message);
            })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(
            e ->
                log.warn(
                    "push event not published type={} error={}",
                    type,
                    e.getClass().getSimpleName()))
        .onErrorResume(e -> Mono.empty())
        .then();
  }
}
