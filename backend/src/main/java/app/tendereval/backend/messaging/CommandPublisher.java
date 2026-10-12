package app.tendereval.backend.messaging;

import io.nats.client.JetStream;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publishes commands to JetStream. Callers subscribe to this only after their database transaction
 * has committed (rule 4). The call completes when JetStream has accepted the message; a repeat of
 * the same {@code Nats-Msg-Id} inside the two minute window is dropped by the stream.
 */
@Component
public class CommandPublisher {

  private final JetStream js;

  public CommandPublisher(JetStream js) {
    this.js = js;
  }

  public Mono<PublishAck> publish(
      String subject, String jsonBody, String msgId, String traceparent, String schema) {
    return Mono.fromCallable(
            () -> {
              Headers headers = new Headers();
              headers.add("Nats-Msg-Id", msgId);
              headers.add("traceparent", traceparent);
              headers.add("Tender-Schema", schema);
              Message message =
                  NatsMessage.builder()
                      .subject(subject)
                      .headers(headers)
                      .data(jsonBody.getBytes(StandardCharsets.UTF_8))
                      .build();
              return js.publish(message, PublishOptions.builder().messageId(msgId).build());
            })
        .subscribeOn(Schedulers.boundedElastic());
  }
}
