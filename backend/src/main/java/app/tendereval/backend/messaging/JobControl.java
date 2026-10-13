package app.tendereval.backend.messaging;

import io.nats.client.Connection;
import io.nats.client.KeyValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The {@code JOB_CONTROL} key-value bucket (PI 5.9): the backend writes {@code cancel.{job_id}} and
 * a worker looks for it when it picks a job up and between units of work.
 */
@Component
public class JobControl {

  private static final Logger log = LoggerFactory.getLogger(JobControl.class);
  static final String BUCKET = "JOB_CONTROL";

  private final Connection connection;

  public JobControl(Connection connection) {
    this.connection = connection;
  }

  /**
   * Never fails the caller: the database already says cancelled, and the silence timer backs it.
   */
  public Mono<Void> requestCancel(java.util.UUID jobId) {
    return Mono.fromRunnable(
            () -> {
              try {
                KeyValue kv = connection.keyValue(BUCKET);
                kv.put("cancel." + jobId, "1");
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(e -> log.warn("cancel key not written job={} error={}", jobId, e.getMessage()))
        .onErrorResume(e -> Mono.empty())
        .then();
  }
}
