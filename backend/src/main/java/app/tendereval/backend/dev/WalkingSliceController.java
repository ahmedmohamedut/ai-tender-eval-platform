package app.tendereval.backend.dev;

import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.messaging.CommandPublisher;
import app.tendereval.backend.messaging.StatusStore;
import app.tendereval.backend.messaging.Subjects;
import app.tendereval.backend.web.TraceFilter;
import app.tendereval.backend.web.TraceIds;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Phase 0 walking slice. Present only when {@code app.dev-endpoints} is true, and not part of
 * {@code api/openapi.yaml}. It proves three things end to end: a request runs inside the request
 * transaction with the user and role set, a command is published after that transaction commits,
 * and a status published by the worker arrives at the backend.
 *
 * <p>There is no jobs table yet, so no row is written. The shape (transaction, commit, publish) is
 * the one every job start will use.
 */
@RestController
@RequestMapping("/_dev/walking-slice")
@ConditionalOnProperty(prefix = "app", name = "dev-endpoints", havingValue = "true")
public class WalkingSliceController {

  private final RequestTransaction transaction;
  private final CommandPublisher publisher;
  private final StatusStore statuses;

  public WalkingSliceController(
      RequestTransaction transaction, CommandPublisher publisher, StatusStore statuses) {
    this.transaction = transaction;
    this.publisher = publisher;
    this.statuses = statuses;
  }

  @PostMapping
  public Mono<ResponseEntity<Map<String, Object>>> start(ServerWebExchange exchange) {
    UUID jobId = UUID.randomUUID();
    statuses.registerDevJob(jobId.toString());
    UUID tenderId = UUID.randomUUID();
    UUID documentVersionId = UUID.randomUUID();
    String traceId = TraceFilter.traceId(exchange);

    return transaction
        .inRequestTransaction(
            db ->
                db.sql(
                        "SELECT current_setting('app.user_id', true) AS user_id,"
                            + " current_setting('app.role', true) AS role,"
                            + " current_user AS db_user")
                    .fetch()
                    .one())
        // The transaction has committed here. Only now is the command published (rule 4).
        .flatMap(
            row -> {
              String command =
                  "{\"job_id\":\"%s\",\"job_type\":\"document_processing\",\"tender_id\":\"%s\","
                          .formatted(jobId, tenderId)
                      + "\"target\":{\"type\":\"document_version\",\"id\":\"%s\"},"
                          .formatted(documentVersionId)
                      + "\"requested_by\":null,\"attempt\":1,\"created_at\":\"%s\","
                          .formatted(Instant.now())
                      + "\"input\":{\"document_version_id\":\"%s\"}}".formatted(documentVersionId);
              return publisher
                  .publish(
                      Subjects.command("document_processing"),
                      command,
                      jobId.toString(),
                      TraceIds.newTraceparent(traceId),
                      Subjects.SCHEMA_COMMAND)
                  .thenReturn(row);
            })
        .map(
            row -> {
              Map<String, Object> body = new LinkedHashMap<>();
              body.put("job_id", jobId.toString());
              body.put("transaction_user_id", row.get("user_id"));
              body.put("transaction_role", row.get("role"));
              body.put("database_user", row.get("db_user"));
              body.put("trace_id", traceId);
              return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
            });
  }

  /** A slow stream, so the gateway's handling of server-sent events can be observed. */
  @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<String> sse() {
    return Flux.interval(Duration.ofMillis(500)).take(8).map(i -> "tick-" + i);
  }

  @GetMapping("/{jobId}")
  public ResponseEntity<Map<String, Object>> status(@PathVariable String jobId) {
    return statuses
        .get(jobId)
        .map(
            s -> {
              Map<String, Object> body = new LinkedHashMap<>();
              body.put("job_id", s.key());
              body.put("state", s.state());
              body.put("seq", s.seq());
              body.put("step", s.step());
              return ResponseEntity.ok(body);
            })
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }
}
