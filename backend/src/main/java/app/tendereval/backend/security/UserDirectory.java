package app.tendereval.backend.security;

import app.tendereval.backend.config.AppProperties;
import app.tendereval.backend.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Finds the platform user for an access token (PI 3.4, 3.7). An unknown subject is refused with 403
 * {@code not_invited}, except the bootstrap administrator's first sign-in. A deactivated user is
 * refused even while the token is still valid. Answers are cached for {@code
 * USER_STATUS_CACHE_SECONDS}.
 */
@Component
public class UserDirectory {

  private record Cached(CurrentUser user, Instant at) {}

  private final DatabaseClient db;
  private final AppProperties props;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public UserDirectory(DatabaseClient db, AppProperties props) {
    this.db = db;
    this.props = props;
  }

  public Mono<CurrentUser> resolve(String subject, String email, String name) {
    Cached hit = cache.get(subject);
    Duration ttl = Duration.ofSeconds(props.userStatusCacheSeconds());
    if (hit != null && hit.at().plus(ttl).isAfter(Instant.now())) {
      return check(hit.user());
    }
    return load(subject)
        .switchIfEmpty(Mono.defer(() -> bootstrap(subject, email, name)))
        .switchIfEmpty(
            Mono.error(ApiException.forbidden("not_invited", "This account has not been invited")))
        .flatMap(
            u -> {
              cache.put(subject, new Cached(u, Instant.now()));
              return check(u);
            });
  }

  /** Drops a cached answer, so a deactivation or role change applies at once on this instance. */
  public void forget(String subject) {
    cache.remove(subject);
  }

  private Mono<CurrentUser> check(CurrentUser u) {
    if ("deactivated".equals(u.status())) {
      return Mono.error(ApiException.forbidden("user_deactivated", "This account is deactivated"));
    }
    return Mono.just(u);
  }

  private Mono<CurrentUser> load(String subject) {
    return db.sql("SELECT * FROM app_resolve_user(:sub)")
        .bind("sub", subject)
        .map(
            (row, meta) -> {
              OffsetDateTime last = row.get("last_sign_in_at", OffsetDateTime.class);
              Optional<Role> role =
                  Role.fromPlatformName(String.valueOf(row.get("role", String.class)));
              return new CurrentUser(
                  row.get("id", UUID.class),
                  subject,
                  row.get("email", String.class),
                  row.get("display_name", String.class),
                  role.orElseThrow(),
                  row.get("status", String.class),
                  row.get("row_version", Integer.class),
                  last == null ? null : last.toInstant());
            })
        .one();
  }

  private Mono<CurrentUser> bootstrap(String subject, String email, String name) {
    String configured = props.bootstrapAdminEmail();
    if (configured == null
        || configured.isBlank()
        || email == null
        || !configured.equalsIgnoreCase(email.trim())) {
      return Mono.empty();
    }
    return db.sql("SELECT app_bootstrap_admin(:sub, :email, :name) AS id")
        .bind("sub", subject)
        .bind("email", email.trim())
        .bind("name", name == null || name.isBlank() ? email.trim() : name)
        .map((row, meta) -> Optional.ofNullable(row.get("id", UUID.class)))
        .one()
        // NULL: an administrator already exists, so this is not a bootstrap (PI 3.7).
        .flatMap(id -> id.isPresent() ? load(subject) : Mono.<CurrentUser>empty());
  }

  static HttpStatus refusal() {
    return HttpStatus.FORBIDDEN;
  }
}
