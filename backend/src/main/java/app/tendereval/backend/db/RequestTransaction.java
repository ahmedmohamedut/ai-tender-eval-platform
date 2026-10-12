package app.tendereval.backend.db;

import app.tendereval.backend.security.AppAuthentication;
import app.tendereval.backend.security.Role;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Runs a unit of work in one transaction that first sets {@code app.user_id} and {@code app.role}
 * for that transaction only (implementation guide 5.3). {@code set_config(..., true)} is
 * transaction-local, so a pooled connection never carries one request's identity into the next.
 *
 * <p>The returned Mono completes after commit. Publishing to NATS therefore belongs in a {@code
 * flatMap} on the result, never inside the work function (rule 4).
 */
@Component
public class RequestTransaction {

  private static final String SET_CONTEXT =
      "SELECT set_config('app.user_id', :uid, true), set_config('app.role', :role, true)";

  private final DatabaseClient db;
  private final TransactionalOperator tx;

  public RequestTransaction(DatabaseClient db, TransactionalOperator tx) {
    this.db = db;
    this.tx = tx;
  }

  /** Runs {@code work} as the signed-in user taken from the security context. */
  public <T> Mono<T> inRequestTransaction(Function<DatabaseClient, Mono<T>> work) {
    return ReactiveSecurityContextHolder.getContext()
        .map(ctx -> ctx.getAuthentication())
        .filter(a -> a instanceof AppAuthentication)
        .cast(AppAuthentication.class)
        .switchIfEmpty(Mono.error(new AccessDeniedException("no authenticated user")))
        .flatMap(
            a ->
                run(
                    a.userId(),
                    a.singleRole()
                        .orElseThrow(() -> new AccessDeniedException("token needs one role")),
                    work));
  }

  public <T> Mono<T> run(String userId, Role role, Function<DatabaseClient, Mono<T>> work) {
    Mono<T> body =
        db.sql(SET_CONTEXT)
            .bind("uid", userId)
            .bind("role", role.platformName())
            .fetch()
            .rowsUpdated()
            .then(Mono.defer(() -> work.apply(db)));
    return tx.transactional(body);
  }
}
