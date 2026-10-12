package app.tendereval.backend.testsupport;

import app.tendereval.backend.common.Idempotency;
import app.tendereval.backend.db.RequestTransaction;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Test-only endpoints. They live in the test source set and exist in no deployed build. */
@RestController
public class ProbeController {

  private final RequestTransaction tx;
  private final Idempotency idempotency;
  public final AtomicInteger executions = new AtomicInteger();

  public ProbeController(RequestTransaction tx, Idempotency idempotency) {
    this.tx = tx;
    this.idempotency = idempotency;
  }

  public record Started(int execution, String value) {}

  @PostMapping("/_test/idem")
  public Mono<ResponseEntity<Started>> idem(
      @RequestHeader(value = "Idempotency-Key", required = false) String header,
      @RequestParam String value) {
    String key = Idempotency.requireKey(header);
    return tx.asUser(
        (db, user) ->
            idempotency.run(
                db,
                user.id(),
                key,
                "POST /_test/idem",
                Idempotency.hash(value),
                Started.class,
                () ->
                    Mono.just(
                        ResponseEntity.accepted()
                            .body(new Started(executions.incrementAndGet(), value)))));
  }

  @GetMapping("/_test/context")
  public Mono<Map<String, Object>> context() {
    return tx.inRequestTransaction(
        db ->
            db.sql(
                    "SELECT current_setting('app.user_id', true) AS user_id,"
                        + " current_setting('app.role', true) AS role,"
                        + " pg_backend_pid() AS pid, current_user AS db_user")
                .fetch()
                .one());
  }

  @PostMapping("/_test/notes")
  public Mono<Long> add(@RequestParam String owner, @RequestParam String note) {
    return tx.inRequestTransaction(
        db ->
            db.sql("INSERT INTO rls_probe (owner_id, note) VALUES (:o, :n)")
                .bind("o", owner)
                .bind("n", note)
                .fetch()
                .rowsUpdated());
  }

  @GetMapping("/_test/notes")
  public Mono<List<Map<String, Object>>> notes() {
    return tx.inRequestTransaction(
        db ->
            db.sql("SELECT owner_id, note FROM rls_probe ORDER BY id").fetch().all().collectList());
  }
}
