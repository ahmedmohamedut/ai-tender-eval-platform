package app.tendereval.backend.users;

import app.tendereval.backend.api.model.DeactivateUser;
import app.tendereval.backend.api.model.RemovalHandling;
import app.tendereval.backend.api.model.User;
import app.tendereval.backend.api.model.UserInvite;
import app.tendereval.backend.api.model.UserPage;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.api.model.UserRefPage;
import app.tendereval.backend.api.model.UserRole;
import app.tendereval.backend.api.model.UserStatus;
import app.tendereval.backend.api.model.UserUpdate;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.common.Cursor;
import app.tendereval.backend.common.ETags;
import app.tendereval.backend.common.Keyset;
import app.tendereval.backend.common.Sql;
import app.tendereval.backend.identity.KeycloakAdmin;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.security.Role;
import app.tendereval.backend.security.UserDirectory;
import app.tendereval.backend.web.ApiException;
import io.r2dbc.spi.Row;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Users, roles and the invitation lifecycle (UX-ROL-05, PI 3.5). Administrator only. */
@Component
public class UserService {

  private static final String COLUMNS =
      "id, email, display_name, role, status, time_zone, last_sign_in_at, created_at, row_version";

  private static final Map<String, Keyset.Field> SORTS =
      Map.of(
          "display_name", new Keyset.Field("lower(display_name)", Keyset.Type.TEXT),
          "email", new Keyset.Field("lower(email)", Keyset.Type.TEXT),
          "created_at", new Keyset.Field("created_at", Keyset.Type.TIMESTAMP));

  private final KeycloakAdmin keycloak;
  private final AuditService audit;
  private final UserDirectory directory;

  public UserService(KeycloakAdmin keycloak, AuditService audit, UserDirectory directory) {
    this.keycloak = keycloak;
    this.audit = audit;
    this.directory = directory;
  }

  // ---- reading ------------------------------------------------------------------------------

  static User toUser(Row row) {
    return new User()
        .id(row.get("id", UUID.class))
        .email(row.get("email", String.class))
        .displayName(row.get("display_name", String.class))
        .role(UserRole.fromValue(row.get("role", String.class)))
        .status(UserStatus.fromValue(row.get("status", String.class)))
        .timeZone(row.get("time_zone", String.class))
        .lastSignInAt(row.get("last_sign_in_at", OffsetDateTime.class))
        .createdAt(row.get("created_at", OffsetDateTime.class))
        .rowVersion(row.get("row_version", Integer.class));
  }

  public Mono<User> get(DatabaseClient db, UUID id) {
    return db.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id")
        .bind("id", id)
        .map((row, meta) -> toUser(row))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("The user")));
  }

  public Mono<UserPage> list(
      DatabaseClient db,
      Integer limitParam,
      String cursor,
      String q,
      String sort,
      UserRole role,
      UserStatus status) {
    int limit = Cursor.limit(limitParam);
    Keyset.Order order = Keyset.parse(sort, SORTS, "display_name");
    Sql filter = new Sql();
    if (q != null && !q.isBlank()) {
      filter.and("(display_name ILIKE :q OR email ILIKE :q)", "q", Sql.contains(q));
    }
    if (role != null) {
      filter.and("role = :role", "role", role.getValue());
    }
    if (status != null) {
      filter.and("status = :status", "status", status.getValue());
    }
    Sql page = new Sql();
    page.binds().putAll(filter.binds());
    String where = filter.where();
    Cursor c = Cursor.decodeOrNull(cursor);
    String keyset = c == null ? "" : (where.isEmpty() ? " WHERE " : " AND ") + order.after("id");
    if (c != null) {
      page.bind("sort_value", c.sortValue()).bind("sort_id", c.id());
    }
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
            "SELECT "
                + COLUMNS
                + " FROM users"
                + where
                + keyset
                + " ORDER BY "
                + order.orderBy("id")
                + " LIMIT "
                + (limit + 1));
    Mono<List<User>> rows = page.apply(spec).map((row, meta) -> toUser(row)).all().collectList();
    Mono<Long> total =
        filter
            .apply(db.sql("SELECT count(*) AS n FROM users" + where))
            .map((row, meta) -> row.get("n", Long.class))
            .one();
    return Mono.zip(rows, total)
        .map(
            t -> {
              List<User> items = t.getT1();
              String next = null;
              if (items.size() > limit) {
                items = items.subList(0, limit);
                User last = items.get(limit - 1);
                next = new Cursor(sortValueOf(order, last), last.getId()).encode();
              }
              return new UserPage().items(items).nextCursor(next).total(t.getT2().intValue());
            });
  }

  private static String sortValueOf(Keyset.Order order, User u) {
    return switch (order.name()) {
      case "email" -> u.getEmail().toLowerCase();
      case "created_at" -> u.getCreatedAt().toString();
      default -> u.getDisplayName().toLowerCase();
    };
  }

  public Mono<UserRefPage> lookup(DatabaseClient db, String q, Integer limitParam) {
    int limit = Math.min(Cursor.limit(limitParam), 100);
    Sql f = new Sql().and("role = 'member'").and("status IN ('active', 'invited')");
    if (q != null && !q.isBlank()) {
      f.and("(display_name ILIKE :q OR email ILIKE :q)", "q", Sql.contains(q));
    }
    return f.apply(
            db.sql(
                "SELECT id, display_name FROM users"
                    + f.where()
                    + " ORDER BY lower(display_name), id LIMIT "
                    + limit))
        .map(
            (row, meta) ->
                new UserRef()
                    .id(row.get("id", UUID.class))
                    .displayName(row.get("display_name", String.class)))
        .all()
        .collectList()
        .map(items -> new UserRefPage().items(items));
  }

  // ---- invitation
  // ---------------------------------------------------------------------------------

  public Mono<User> invite(DatabaseClient db, CurrentUser actor, UserInvite invite) {
    String email = invite.getEmail() == null ? "" : invite.getEmail().trim();
    String name = invite.getDisplayName() == null ? "" : invite.getDisplayName().trim();
    if (name.isEmpty()) {
      return Mono.error(ApiException.invalid("display_name", "required", "Enter a name"));
    }
    String[] parts = splitName(name);
    Role role = Role.fromPlatformName(invite.getRole().getValue()).orElseThrow();
    return db.sql("SELECT 1 AS x FROM users WHERE lower(email) = lower(:e)")
        .bind("e", email)
        .map((row, meta) -> 1)
        .one()
        .flatMap(
            x ->
                Mono.<User>error(
                    ApiException.conflict("email_exists", "A user with this email already exists")))
        .switchIfEmpty(
            Mono.defer(
                () ->
                    keycloak
                        .createUser(email, parts[0], parts[1])
                        .flatMap(
                            kcId ->
                                keycloak
                                    .mapRealmRole(kcId, realmRole(role))
                                    .then(keycloak.sendInvitation(kcId))
                                    .then(insertInvited(db, actor, kcId, email, name, role))
                                    .onErrorResume(
                                        e ->
                                            keycloak
                                                .deleteUser(kcId)
                                                .onErrorResume(x -> Mono.empty())
                                                .then(Mono.error(e))))));
  }

  private Mono<User> insertInvited(
      DatabaseClient db, CurrentUser actor, String kcId, String email, String name, Role role) {
    return db.sql(
            "INSERT INTO users (email, display_name, role, status, sso_subject)"
                + " VALUES (:email, :name, :role, 'invited', :sub) RETURNING "
                + COLUMNS)
        .bind("email", email)
        .bind("name", name)
        .bind("role", role.platformName())
        .bind("sub", kcId)
        .map((row, meta) -> toUser(row))
        .one()
        .flatMap(
            u ->
                audit
                    .record(
                        db,
                        actor,
                        AuditEntry.of("user_invited", "user", u.getId())
                            .created(
                                Map.of(
                                    "email",
                                    email,
                                    "display_name",
                                    name,
                                    "role",
                                    role.platformName(),
                                    "status",
                                    "invited"))
                            .build())
                    .thenReturn(u));
  }

  public Mono<Void> resendInvitation(DatabaseClient db, CurrentUser actor, UUID id) {
    return row(db, id)
        .flatMap(
            u -> {
              if (!"invited".equals(u.status())) {
                return Mono.error(
                    ApiException.conflict(
                        "not_invited", "Only an invited user can be sent a new link"));
              }
              return keycloak
                  .sendInvitation(u.subject())
                  .then(
                      audit.record(
                          db, actor, AuditEntry.of("user_invitation_resent", "user", id).build()));
            });
  }

  // ---- change
  // ---------------------------------------------------------------------------------------

  public Mono<User> update(
      DatabaseClient db, CurrentUser actor, UUID id, int ifMatch, UserUpdate body) {
    return row(db, id)
        .flatMap(
            current -> {
              if (current.rowVersion() != ifMatch) {
                return Mono.error(ETags.stale());
              }
              String newName =
                  body.getDisplayName() == null
                      ? current.displayName()
                      : body.getDisplayName().trim();
              if (newName.isEmpty()) {
                return Mono.error(ApiException.invalid("display_name", "required", "Enter a name"));
              }
              Role newRole =
                  body.getRole() == null
                      ? current.role()
                      : Role.fromPlatformName(body.getRole().getValue()).orElseThrow();
              Mono<Void> guard = Mono.empty();
              if (newRole != current.role()) {
                guard = guardRoleChange(db, actor, current, newRole);
              }
              Mono<Void> keycloakWork = Mono.empty();
              if (!newName.equals(current.displayName())) {
                String[] parts = splitName(newName);
                keycloakWork = keycloak.updateName(current.subject(), parts[0], parts[1]);
              }
              if (newRole != current.role()) {
                keycloakWork =
                    keycloakWork.then(
                        keycloak.replaceRealmRole(
                            current.subject(), realmRole(current.role()), realmRole(newRole)));
              }
              Map<String, Object> before = new LinkedHashMap<>();
              before.put("display_name", current.displayName());
              before.put("role", current.role().platformName());
              Map<String, Object> after = new LinkedHashMap<>();
              after.put("display_name", newName);
              after.put("role", newRole.platformName());
              boolean roleChanged = newRole != current.role();
              boolean changed = roleChanged || !newName.equals(current.displayName());
              if (!changed) {
                return get(db, id);
              }
              return guard
                  .then(keycloakWork)
                  .then(
                      db.sql(
                              "UPDATE users SET display_name = :n, role = :r"
                                  + " WHERE id = :id AND row_version = :v")
                          .bind("n", newName)
                          .bind("r", newRole.platformName())
                          .bind("id", id)
                          .bind("v", ifMatch)
                          .fetch()
                          .rowsUpdated())
                  .flatMap(
                      n ->
                          n == 0
                              ? Mono.<Void>error(ETags.stale())
                              : audit.record(
                                  db,
                                  actor,
                                  AuditEntry.of(
                                          roleChanged ? "user_role_changed" : "user_updated",
                                          "user",
                                          id)
                                      .changed(before, after)
                                      .build()))
                  .doOnSuccess(x -> directory.forget(current.subject()))
                  .then(get(db, id));
            });
  }

  private Mono<Void> guardRoleChange(
      DatabaseClient db, CurrentUser actor, UserRow current, Role newRole) {
    if (current.id().equals(actor.id())) {
      return Mono.error(
          new ApiException(
              HttpStatus.UNPROCESSABLE_ENTITY,
              "cannot_change_own_role",
              "You cannot change your own role"));
    }
    Mono<Void> lastAdmin =
        current.role() == Role.ADMIN
            ? db.sql(
                    "SELECT count(*) AS n FROM users WHERE role = 'admin' AND status <> 'deactivated'"
                        + " AND id <> :id")
                .bind("id", current.id())
                .map((r, m) -> r.get("n", Long.class))
                .one()
                .flatMap(
                    n ->
                        n == 0
                            ? Mono.<Void>error(
                                ApiException.conflict(
                                    "last_administrator",
                                    "There must be at least one active administrator"))
                            : Mono.<Void>empty())
            : Mono.empty();
    Mono<Void> memberships =
        newRole == Role.ADMIN
            ? db.sql(
                    "SELECT count(*) AS n FROM tender_members WHERE user_id = :id AND status = 'active'")
                .bind("id", current.id())
                .map((r, m) -> r.get("n", Long.class))
                .one()
                .flatMap(
                    n ->
                        n > 0
                            ? Mono.<Void>error(
                                ApiException.conflict(
                                    "has_memberships",
                                    "Remove this person from their committees before making them an administrator"))
                            : Mono.<Void>empty())
            : Mono.empty();
    return lastAdmin.then(memberships);
  }

  public Mono<User> deactivate(DatabaseClient db, CurrentUser actor, UUID id, DeactivateUser body) {
    return row(db, id)
        .flatMap(
            current -> {
              if (current.id().equals(actor.id())) {
                return Mono.error(
                    new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "cannot_deactivate_self",
                        "You cannot deactivate your own account"));
              }
              if ("deactivated".equals(current.status())) {
                return get(db, id);
              }
              Mono<Void> lastAdmin =
                  current.role() == Role.ADMIN
                      ? db.sql(
                              "SELECT count(*) AS n FROM users WHERE role = 'admin'"
                                  + " AND status <> 'deactivated' AND id <> :id")
                          .bind("id", id)
                          .map((r, m) -> r.get("n", Long.class))
                          .one()
                          .flatMap(
                              n ->
                                  n == 0
                                      ? Mono.<Void>error(
                                          ApiException.conflict(
                                              "last_administrator",
                                              "There must be at least one active administrator"))
                                      : Mono.<Void>empty())
                      : Mono.empty();
              return lastAdmin
                  .then(handleMemberships(db, actor, id, body))
                  .then(keycloak.setEnabled(current.subject(), false))
                  .then(keycloak.logout(current.subject()))
                  .then(
                      db.sql("UPDATE users SET status = 'deactivated' WHERE id = :id")
                          .bind("id", id)
                          .fetch()
                          .rowsUpdated())
                  .then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("user_deactivated", "user", id)
                              .changed(
                                  Map.of("status", current.status()),
                                  Map.of("status", "deactivated"))
                              .note(body.getNote())
                              .build()))
                  .doOnSuccess(x -> directory.forget(current.subject()))
                  .then(get(db, id));
            });
  }

  /** Active committee seats of a deactivated user: kept as history or handed to another member. */
  private Mono<Void> handleMemberships(
      DatabaseClient db, CurrentUser actor, UUID userId, DeactivateUser body) {
    RemovalHandling handling = body.getHandling();
    UUID target = body.getReassignToUserId();
    if (handling == RemovalHandling.REASSIGNED && target == null) {
      return Mono.error(
          ApiException.invalid(
              "reassign_to_user_id", "required", "Choose who takes over the work"));
    }
    return db.sql(
            "SELECT m.id, m.tender_id, m.is_chair FROM tender_members m JOIN tenders t ON t.id = m.tender_id"
                + " WHERE m.user_id = :u AND m.status = 'active' AND t.deleted_at IS NULL"
                + " AND t.archived_at IS NULL")
        .bind("u", userId)
        .map(
            (row, meta) ->
                new UUID[] {
                  row.get("id", UUID.class),
                  row.get("tender_id", UUID.class),
                  Boolean.TRUE.equals(row.get("is_chair", Boolean.class)) ? userId : null
                })
        .all()
        .collectList()
        .flatMap(
            seats -> {
              for (UUID[] seat : seats) {
                if (seat[2] != null) {
                  return Mono.<Void>error(
                      ApiException.conflict(
                              "chair_must_be_replaced",
                              "This person is the Chair of a tender. Designate another Chair first")
                          .with("tender_id", seat[1].toString()));
                }
              }
              if (seats.isEmpty()) {
                return Mono.<Void>empty();
              }
              Mono<Void> targetCheck =
                  handling == RemovalHandling.REASSIGNED
                      ? db.sql(
                              "SELECT 1 AS x FROM users WHERE id = :t AND role = 'member' AND status IN ('active','invited')")
                          .bind("t", target)
                          .map((r, m) -> 1)
                          .one()
                          .switchIfEmpty(
                              Mono.error(
                                  ApiException.invalid(
                                      "reassign_to_user_id",
                                      "invalid",
                                      "Choose an active committee member")))
                          .then()
                      : Mono.empty();
              return targetCheck.then(
                  Flux.fromIterable(seats)
                      .concatMap(
                          seat ->
                              releaseSeat(db, actor, seat[0], seat[1], userId, handling, target))
                      .then());
            });
  }

  private Mono<Void> releaseSeat(
      DatabaseClient db,
      CurrentUser actor,
      UUID memberId,
      UUID tenderId,
      UUID userId,
      RemovalHandling handling,
      UUID target) {
    DatabaseClient.GenericExecuteSpec update =
        db.sql(
                "UPDATE tender_members SET status = 'removed', removed_at = now(),"
                    + " removal_handling = :h, reassigned_to_user_id = :t WHERE id = :id")
            .bind("h", handling.getValue())
            .bind("id", memberId);
    update =
        target == null || handling != RemovalHandling.REASSIGNED
            ? update.bindNull("t", UUID.class)
            : update.bind("t", target);
    Mono<Void> addTarget =
        handling == RemovalHandling.REASSIGNED
            ? db.sql(
                    "INSERT INTO tender_members (tender_id, user_id) SELECT :tid, :u"
                        + " WHERE NOT EXISTS (SELECT 1 FROM tender_members WHERE tender_id = :tid"
                        + " AND user_id = :u AND status = 'active')")
                .bind("tid", tenderId)
                .bind("u", target)
                .fetch()
                .rowsUpdated()
                .then()
            : Mono.empty();
    return update
        .fetch()
        .rowsUpdated()
        .then(addTarget)
        .then(
            audit.record(
                db,
                actor,
                AuditEntry.of("committee_changed", "tender_member", memberId)
                    .tender(tenderId)
                    .changed(
                        Map.of("status", "active"),
                        Map.of("status", "removed", "removal_handling", handling.getValue()))
                    .build()));
  }

  public Mono<User> reactivate(DatabaseClient db, CurrentUser actor, UUID id) {
    return row(db, id)
        .flatMap(
            current -> {
              if (!"deactivated".equals(current.status())) {
                return get(db, id);
              }
              String next = current.lastSignInAt() == null ? "invited" : "active";
              return keycloak
                  .setEnabled(current.subject(), true)
                  .then(
                      db.sql("UPDATE users SET status = :s WHERE id = :id")
                          .bind("s", next)
                          .bind("id", id)
                          .fetch()
                          .rowsUpdated())
                  .then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("user_reactivated", "user", id)
                              .changed(Map.of("status", "deactivated"), Map.of("status", next))
                              .build()))
                  .doOnSuccess(x -> directory.forget(current.subject()))
                  .then(get(db, id));
            });
  }

  // ---- helpers
  // --------------------------------------------------------------------------------------

  record UserRow(
      UUID id,
      String subject,
      String displayName,
      Role role,
      String status,
      int rowVersion,
      java.time.Instant lastSignInAt) {}

  private Mono<UserRow> row(DatabaseClient db, UUID id) {
    return db.sql(
            "SELECT id, sso_subject, display_name, role, status, row_version, last_sign_in_at"
                + " FROM users WHERE id = :id")
        .bind("id", id)
        .map(
            (r, m) -> {
              OffsetDateTime last = r.get("last_sign_in_at", OffsetDateTime.class);
              return new UserRow(
                  r.get("id", UUID.class),
                  r.get("sso_subject", String.class),
                  r.get("display_name", String.class),
                  Role.fromPlatformName(r.get("role", String.class)).orElseThrow(),
                  r.get("status", String.class),
                  r.get("row_version", Integer.class),
                  last == null ? null : last.toInstant());
            })
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("The user")));
  }

  /** "First and last name come from display_name split at its last space" (PI 3.5). */
  static String[] splitName(String displayName) {
    String n = displayName.trim();
    int i = n.lastIndexOf(' ');
    return i < 0 ? new String[] {n, n} : new String[] {n.substring(0, i), n.substring(i + 1)};
  }

  static String realmRole(Role role) {
    return switch (role) {
      case ADMIN -> "procurement_admin";
      case MEMBER -> "committee_member";
      case OBSERVER -> "observer";
    };
  }
}
