package app.tendereval.backend.db;

import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.security.Role;
import app.tendereval.backend.web.ApiException;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Runs a unit of work in one transaction that first sets {@code app.user_id} (the platform user)
 * and {@code app.role} for that transaction only (implementation guide 5.3). {@code set_config(...,
 * true)} is transaction-local, so a pooled connection never carries one request's identity into the
 * next.
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

  /** The user of this request, resolved by {@code UserContextFilter}. */
  public static Mono<CurrentUser> currentUser() {
    return Mono.deferContextual(
        ctx ->
            ctx.hasKey(CurrentUser.class)
                ? Mono.just(ctx.get(CurrentUser.class))
                : Mono.error(
                    new ApiException(
                        HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required")));
  }

  /** Runs {@code work} as the signed-in user. */
  public <T> Mono<T> inRequestTransaction(Function<DatabaseClient, Mono<T>> work) {
    return currentUser().flatMap(u -> run(u.id().toString(), u.role(), work));
  }

  /** Runs {@code work} as the signed-in user and also hands it that user. */
  public <T> Mono<T> asUser(UserWork<T> work) {
    return currentUser().flatMap(u -> run(u.id().toString(), u.role(), c -> work.apply(c, u)));
  }

  @FunctionalInterface
  public interface UserWork<T> {
    Mono<T> apply(DatabaseClient db, CurrentUser user);
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
