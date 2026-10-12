package app.tendereval.backend.me;

import app.tendereval.backend.api.SessionApi;
import app.tendereval.backend.api.model.ClientConfig;
import app.tendereval.backend.api.model.Me;
import app.tendereval.backend.api.model.MeUpdate;
import app.tendereval.backend.api.model.User;
import app.tendereval.backend.api.model.UserRole;
import app.tendereval.backend.api.model.UserStatus;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.common.Settings;
import app.tendereval.backend.config.AppProperties;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.security.UserDirectory;
import app.tendereval.backend.web.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** UX-AUTH-01, UX-ROL-04, UX-NFR-08: who am I, and the values the interface needs. */
@RestController
public class MeController implements SessionApi {

  private final RequestTransaction tx;
  private final AppProperties props;
  private final UserDirectory directory;
  private final AuditService audit;

  public MeController(
      RequestTransaction tx, AppProperties props, UserDirectory directory, AuditService audit) {
    this.tx = tx;
    this.props = props;
    this.directory = directory;
    this.audit = audit;
  }

  @Override
  public Mono<ResponseEntity<Me>> getMe(ServerWebExchange exchange) {
    return tx.asUser(
            (db, user) ->
                recordSignIn(db, user).then(Mono.defer(() -> build(db, user.id().toString()))))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Me>> updateMe(Mono<MeUpdate> body, ServerWebExchange exchange) {
    return body.flatMap(
            update -> {
              String zone = update.getTimeZone();
              if (zone != null && !ZoneId.getAvailableZoneIds().contains(zone)) {
                return Mono.error(
                    ApiException.invalid(
                        "time_zone", "unknown_time_zone", "Choose a valid time zone"));
              }
              return tx.asUser(
                  (db, user) -> {
                    Mono<Void> write =
                        zone == null
                            ? Mono.empty()
                            : db.sql("SELECT time_zone FROM users WHERE id = :id")
                                .bind("id", user.id())
                                .map((row, meta) -> row.get("time_zone", String.class))
                                .one()
                                .flatMap(
                                    old ->
                                        old.equals(zone)
                                            ? Mono.<Void>empty()
                                            : db.sql(
                                                    "UPDATE users SET time_zone = :tz WHERE id = :id")
                                                .bind("tz", zone)
                                                .bind("id", user.id())
                                                .fetch()
                                                .rowsUpdated()
                                                .then(
                                                    audit.record(
                                                        db,
                                                        user,
                                                        AuditEntry.of(
                                                                "user_updated", "user", user.id())
                                                            .changed(
                                                                java.util.Map.of("time_zone", old),
                                                                java.util.Map.of("time_zone", zone))
                                                            .build())));
                    return write.then(Mono.defer(() -> build(db, user.id().toString())));
                  });
            })
        .map(ResponseEntity::ok);
  }

  /** First sign-in moves an invited user to active (PI 3.5); later requests refresh the time. */
  private Mono<Void> recordSignIn(DatabaseClient db, CurrentUser user) {
    boolean stale =
        "invited".equals(user.status())
            || user.lastSignInAt() == null
            || user.lastSignInAt().isBefore(java.time.Instant.now().minusSeconds(600));
    if (!stale) {
      return Mono.empty();
    }
    return db.sql("UPDATE users SET status = 'active', last_sign_in_at = now() WHERE id = :id")
        .bind("id", user.id())
        .fetch()
        .rowsUpdated()
        .doOnSuccess(n -> directory.forget(user.subject()))
        .then();
  }

  private Mono<Me> build(DatabaseClient db, String userId) {
    Mono<User> user =
        db.sql(
                "SELECT id, email, display_name, role, status, time_zone, last_sign_in_at,"
                    + " created_at, row_version FROM users WHERE id = :id::uuid")
            .bind("id", userId)
            .map(
                (row, meta) ->
                    new User()
                        .id(row.get("id", java.util.UUID.class))
                        .email(row.get("email", String.class))
                        .displayName(row.get("display_name", String.class))
                        .role(UserRole.fromValue(row.get("role", String.class)))
                        .status(UserStatus.fromValue(row.get("status", String.class)))
                        .timeZone(row.get("time_zone", String.class))
                        .lastSignInAt(row.get("last_sign_in_at", OffsetDateTime.class))
                        .createdAt(row.get("created_at", OffsetDateTime.class))
                        .rowVersion(row.get("row_version", Integer.class)))
            .one();
    Mono<Long> assigned =
        db.sql("SELECT count(*) AS n FROM tenders WHERE deleted_at IS NULL AND archived_at IS NULL")
            .map((row, meta) -> row.get("n", Long.class))
            .one();
    return Mono.zip(user, assigned, Settings.load(db))
        .map(
            t ->
                new Me()
                    .user(t.getT1())
                    .assignedTenderCount(t.getT2().intValue())
                    .clientConfig(clientConfig(t.getT3())));
  }

  private ClientConfig clientConfig(Settings s) {
    return new ClientConfig()
        .minNoteLength(s.minNoteLength())
        .uploadMaxBytes((int) Math.min(Integer.MAX_VALUE, s.uploadMaxBytes()))
        .acceptedFormats(List.of("pdf", "docx"))
        .lowConfidenceThreshold(s.lowConfidenceThreshold())
        .sessionIdleTimeoutSeconds(props.sessionIdleTimeoutSeconds())
        .sessionWarningSeconds(s.sessionWarningSeconds());
  }
}
