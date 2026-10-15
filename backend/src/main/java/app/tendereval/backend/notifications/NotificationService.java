package app.tendereval.backend.notifications;

import app.tendereval.backend.api.model.Notification;
import app.tendereval.backend.api.model.NotificationPage;
import app.tendereval.backend.api.model.NotificationPreference;
import app.tendereval.backend.api.model.NotificationPreferences;
import app.tendereval.backend.api.model.NotificationType;
import app.tendereval.backend.api.model.UnreadCount;
import app.tendereval.backend.api.model.UnreadCountByTenderInner;
import app.tendereval.backend.common.Cursor;
import app.tendereval.backend.messaging.PushPublisher;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.ApiException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-app notifications (UX-NOT-01..03, PG-18). A notification for someone else is created only
 * through {@code app_notify} (V24), which checks that the sender may notify and the recipient is on
 * the tender. The push to the recipient's open tabs goes out after the commit.
 */
@Service
public class NotificationService {

  private static final Map<String, String> TITLES = new LinkedHashMap<>();

  static {
    TITLES.put("added_to_tender", "You were added to a tender");
    TITLES.put("tender_opened", "A tender was opened");
    TITLES.put("job_completed", "A job finished");
    TITLES.put("job_failed", "A job failed");
    TITLES.put("requirements_confirmed", "Requirements were confirmed");
    TITLES.put("requirements_reopened", "Requirements were reopened");
    TITLES.put("proposal_screened", "A proposal was screened");
    TITLES.put("evaluation_ready", "An evaluation is ready");
    TITLES.put("evaluation_reopened", "Your evaluation was reopened");
    TITLES.put("new_proposal_version", "A new proposal version arrived");
    TITLES.put("all_members_submitted", "All members have submitted");
    TITLES.put("report_finalized", "The report was finalized");
    TITLES.put("due_date_reminder", "A stage is due soon");
    TITLES.put("manual_reminder", "Please finish your evaluation");
    TITLES.put("conflict_declared", "A conflict was declared");
    TITLES.put("resubmission_requested", "A resubmission was requested");
    TITLES.put("document_added", "A document was added");
  }

  private final PushPublisher push;
  private final JsonMapper json = JsonMapper.builder().build();

  public NotificationService(PushPublisher push) {
    this.push = push;
  }

  // ---- creating --------------------------------------------------------------------------------

  /**
   * One notification per recipient; those who are not on the tender are skipped by the database.
   */
  public Mono<Void> notify(
      DatabaseClient db,
      UUID tenderId,
      String type,
      String objectType,
      UUID objectId,
      Map<String, Object> params,
      Collection<UUID> recipients) {
    String body = json.writeValueAsString(params == null ? Map.of() : params);
    return Flux.fromIterable(new java.util.LinkedHashSet<>(recipients))
        .concatMap(
            user -> {
              DatabaseClient.GenericExecuteSpec q =
                  db.sql("SELECT app_notify(:u, :t, :type, :ot, :oid, CAST(:p AS jsonb)) AS id")
                      .bind("u", user)
                      .bind("t", tenderId)
                      .bind("type", type)
                      .bind("p", body);
              q = objectType == null ? q.bindNull("ot", String.class) : q.bind("ot", objectType);
              q = objectId == null ? q.bindNull("oid", UUID.class) : q.bind("oid", objectId);
              return q.map((r, m) -> java.util.Optional.ofNullable(r.get("id", UUID.class)))
                  .one()
                  .filter(java.util.Optional::isPresent)
                  .map(id -> Map.entry(user, id.get()));
            })
        .collectList()
        .flatMap(created -> afterCommit(created, type));
  }

  public Mono<List<UUID>> activeMembers(DatabaseClient db, UUID tenderId) {
    return db.sql("SELECT user_id FROM tender_members WHERE tender_id = :t AND status = 'active'")
        .bind("t", tenderId)
        .map((r, m) -> r.get("user_id", UUID.class))
        .all()
        .collectList();
  }

  public Mono<List<UUID>> chairs(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT user_id FROM tender_members WHERE tender_id = :t AND status = 'active'"
                + " AND is_chair")
        .bind("t", tenderId)
        .map((r, m) -> r.get("user_id", UUID.class))
        .all()
        .collectList();
  }

  /** Publishes {@code notification.created} to each recipient once the transaction committed. */
  Mono<Void> afterCommit(List<Map.Entry<UUID, UUID>> created, String type) {
    if (created.isEmpty()) {
      return Mono.empty();
    }
    Mono<Void> publish =
        Flux.fromIterable(created)
            .concatMap(
                e ->
                    push.user(
                        e.getKey(),
                        "notification.created",
                        Map.of(
                            "notification_id", e.getValue().toString(), "notification_type", type)))
            .then();
    return TransactionSynchronizationManager.forCurrentTransaction()
        .doOnNext(
            m -> {
              if (m.isSynchronizationActive()) {
                m.registerSynchronization(
                    new TransactionSynchronization() {
                      @Override
                      public Mono<Void> afterCommit() {
                        return publish;
                      }
                    });
              }
            })
        .then()
        .onErrorResume(e -> publish); // no transaction: nothing to wait for
  }

  /** Push of notifications made outside a request (the timer), after its own commit. */
  public Mono<Void> pushCreated(List<Map.Entry<UUID, UUID>> created, String type) {
    return Flux.fromIterable(created)
        .concatMap(
            e ->
                push.user(
                    e.getKey(),
                    "notification.created",
                    Map.of("notification_id", e.getValue().toString(), "notification_type", type)))
        .then();
  }

  // ---- reading ---------------------------------------------------------------------------------

  public static String titleOf(String type) {
    return TITLES.getOrDefault(type, type.replace('_', ' '));
  }

  public static String link(String type, UUID tender) {
    if (tender == null) {
      return null;
    }
    String base = "/tenders/" + tender;
    return switch (type) {
      case "report_finalized", "all_members_submitted" -> base + "/report";
      case "evaluation_reopened", "manual_reminder", "due_date_reminder", "evaluation_ready" ->
          base + "/evaluation";
      case "requirements_confirmed", "requirements_reopened" -> base + "/requirements";
      default -> base;
    };
  }

  @SuppressWarnings("unchecked")
  private Notification map(io.r2dbc.spi.Readable r) {
    String type = r.get("notification_type", String.class);
    UUID tender = r.get("tender_id", UUID.class);
    Map<String, Object> params =
        json.readValue(String.valueOf(r.get("params", String.class)), Map.class);
    String body = null;
    if ("due_date_reminder".equals(type)) {
      body = "The " + params.get("stage") + " stage is due on " + params.get("due_date");
    }
    Notification n =
        new Notification()
            .id(r.get("id", UUID.class))
            .notificationType(NotificationType.fromValue(type))
            .title(TITLES.getOrDefault(type, type.replace('_', ' ')))
            .body(body)
            .createdAt(r.get("created_at", OffsetDateTime.class))
            .readAt(r.get("read_at", OffsetDateTime.class))
            .objectId(r.get("object_id", UUID.class))
            .link(link(type, tender));
    if (r.get("object_type", String.class) != null) {
      n.setObjectType(r.get("object_type", String.class));
    }
    if (tender != null) {
      Map<String, Object> t = new LinkedHashMap<>();
      t.put("id", tender);
      t.put("name", r.get("tender_name", String.class));
      n.setTender(t);
    }
    return n;
  }

  public Mono<NotificationPage> list(
      DatabaseClient db,
      CurrentUser user,
      Integer limit,
      String cursor,
      Boolean unread,
      UUID tender) {
    int lim = Cursor.limit(limit);
    StringBuilder sql =
        new StringBuilder(
            "SELECT n.id, n.tender_id, n.notification_type, n.object_type, n.object_id,"
                + " n.params::text AS params, n.created_at, n.read_at, t.name AS tender_name"
                + " FROM notifications n LEFT JOIN tenders t ON t.id = n.tender_id"
                + " WHERE n.user_id = :u");
    Map<String, Object> binds = new LinkedHashMap<>();
    binds.put("u", user.id());
    if (Boolean.TRUE.equals(unread)) {
      sql.append(" AND n.read_at IS NULL");
    }
    if (tender != null) {
      sql.append(" AND n.tender_id = :t");
      binds.put("t", tender);
    }
    Cursor cur = Cursor.decodeOrNull(cursor);
    if (cur != null) {
      sql.append(" AND (n.created_at, n.id) < (:after, :aid)");
      binds.put("after", OffsetDateTime.parse(cur.sortValue()));
      binds.put("aid", cur.id());
    }
    sql.append(" ORDER BY n.created_at DESC, n.id DESC LIMIT ").append(lim + 1);
    DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString());
    for (var e : binds.entrySet()) {
      spec = spec.bind(e.getKey(), e.getValue());
    }
    return spec.map((r, m) -> map(r))
        .all()
        .collectList()
        .map(
            all -> {
              boolean more = all.size() > lim;
              List<Notification> page = more ? new ArrayList<>(all.subList(0, lim)) : all;
              String next = null;
              if (more) {
                Notification last = page.get(page.size() - 1);
                next = new Cursor(last.getCreatedAt().toString(), last.getId()).encode();
              }
              return new NotificationPage().items(page).nextCursor(next);
            });
  }

  public Mono<UnreadCount> unread(DatabaseClient db, CurrentUser user) {
    return db.sql(
            "SELECT tender_id, count(*) AS n FROM notifications WHERE user_id = :u"
                + " AND read_at IS NULL GROUP BY tender_id")
        .bind("u", user.id())
        .map((r, m) -> Map.entry(r.get("tender_id", UUID.class), r.get("n", Long.class)))
        .all()
        .collectList()
        .map(
            rows -> {
              UnreadCount out = new UnreadCount();
              int total = 0;
              for (var e : rows) {
                total += e.getValue().intValue();
                if (e.getKey() != null) {
                  out.addByTenderItem(
                      new UnreadCountByTenderInner()
                          .tenderId(e.getKey())
                          .count(e.getValue().intValue()));
                }
              }
              return out.count(total);
            });
  }

  // ---- state -----------------------------------------------------------------------------------

  public Mono<Void> markRead(DatabaseClient db, CurrentUser user, UUID id) {
    return db.sql(
            "UPDATE notifications SET read_at = coalesce(read_at, now()) WHERE id = :id"
                + " AND user_id = :u")
        .bind("id", id)
        .bind("u", user.id())
        .fetch()
        .rowsUpdated()
        .flatMap(
            n ->
                n == 0
                    ? Mono.<Void>error(ApiException.notFound("notification"))
                    : Mono.<Void>empty());
  }

  public Mono<Void> markAllRead(DatabaseClient db, CurrentUser user, UUID tender) {
    DatabaseClient.GenericExecuteSpec q =
        db.sql(
                "UPDATE notifications SET read_at = now() WHERE user_id = :u AND read_at IS NULL"
                    + (tender == null ? "" : " AND tender_id = :t"))
            .bind("u", user.id());
    if (tender != null) {
      q = q.bind("t", tender);
    }
    return q.fetch().rowsUpdated().then();
  }

  public Mono<NotificationPreferences> preferences(DatabaseClient db, CurrentUser user) {
    return db.sql(
            "SELECT notification_type, email_enabled FROM notification_preferences"
                + " WHERE user_id = :u")
        .bind("u", user.id())
        .map(
            (r, m) ->
                Map.entry(
                    r.get("notification_type", String.class),
                    r.get("email_enabled", Boolean.class)))
        .all()
        .collectMap(Map.Entry::getKey, Map.Entry::getValue)
        .map(
            set -> {
              List<NotificationPreference> items = new ArrayList<>();
              for (NotificationType t : NotificationType.values()) {
                items.add(
                    new NotificationPreference()
                        .notificationType(t)
                        .emailEnabled(Boolean.TRUE.equals(set.get(t.getValue()))));
              }
              return new NotificationPreferences().items(items);
            });
  }

  public Mono<NotificationPreferences> putPreferences(
      DatabaseClient db, CurrentUser user, NotificationPreferences in) {
    return Flux.fromIterable(in.getItems())
        .concatMap(
            p ->
                db.sql(
                        "INSERT INTO notification_preferences (user_id, notification_type,"
                            + " email_enabled) VALUES (:u, :t, :e) ON CONFLICT (user_id,"
                            + " notification_type) DO UPDATE SET email_enabled = EXCLUDED.email_enabled,"
                            + " updated_at = now()")
                    .bind("u", user.id())
                    .bind("t", p.getNotificationType().getValue())
                    .bind("e", p.getEmailEnabled())
                    .fetch()
                    .rowsUpdated())
        .then(preferences(db, user));
  }
}
