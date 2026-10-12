package app.tendereval.backend.testsupport;

import app.tendereval.backend.db.RequestTransaction;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Test-only endpoints. They live in the test source set and exist in no deployed build. */
@RestController
public class ProbeController {

  private final RequestTransaction tx;

  public ProbeController(RequestTransaction tx) {
    this.tx = tx;
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
