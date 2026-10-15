package app.tendereval.backend.audit;

import app.tendereval.backend.api.model.AuditAction;
import app.tendereval.backend.api.model.AuditEvent;
import app.tendereval.backend.api.model.AuditEventPage;
import app.tendereval.backend.api.model.AuditSource;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.common.Cursor;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Search of the audit trail (UX-AUD-01, UX-AUD-04, PG-20). Row-level security decides what the
 * caller reads (the Observer reads no member's grades; the administrator reads them only after
 * release, V22); this only filters and pages. Newest first; without {@code from} the last 30 days.
 */
@Service
public class AuditSearch {

  private final JsonMapper json = JsonMapper.builder().build();

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(String text) {
    return text == null ? null : json.readValue(text, Map.class);
  }

  public Mono<AuditEventPage> search(
      DatabaseClient db,
      Integer limit,
      String cursor,
      UUID tender,
      UUID vendor,
      UUID user,
      AuditAction action,
      AuditSource source,
      OffsetDateTime from,
      OffsetDateTime to) {
    int lim = Cursor.limit(limit);
    StringBuilder sql =
        new StringBuilder(
            "SELECT a.id, a.occurred_at, a.actor_user_id, u.display_name, a.actor_role, a.source,"
                + " a.tender_id, a.tender_vendor_id, a.object_type, a.object_id, a.action,"
                + " a.before::text AS before, a.after::text AS after, a.note"
                + " FROM audit_events a LEFT JOIN users u ON u.id = a.actor_user_id"
                + " WHERE a.occurred_at >= :from");
    Map<String, Object> binds = new LinkedHashMap<>();
    binds.put("from", from == null ? OffsetDateTime.now().minusDays(30) : from);
    if (to != null) {
      sql.append(" AND a.occurred_at <= :to");
      binds.put("to", to);
    }
    if (tender != null) {
      sql.append(" AND a.tender_id = :tender");
      binds.put("tender", tender);
    }
    if (vendor != null) {
      sql.append(" AND a.tender_vendor_id = :vendor");
      binds.put("vendor", vendor);
    }
    if (user != null) {
      sql.append(" AND a.actor_user_id = :user");
      binds.put("user", user);
    }
    if (action != null) {
      sql.append(" AND a.action = :action");
      binds.put("action", action.getValue());
    }
    if (source != null) {
      sql.append(" AND a.source = :source");
      binds.put("source", source.getValue());
    }
    Cursor cur = Cursor.decodeOrNull(cursor);
    if (cur != null) {
      sql.append(" AND (a.occurred_at, a.id) < (:after, :aid)");
      binds.put("after", OffsetDateTime.parse(cur.sortValue()));
      binds.put("aid", cur.id());
    }
    sql.append(" ORDER BY a.occurred_at DESC, a.id DESC LIMIT ").append(lim + 1);
    DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString());
    for (var e : binds.entrySet()) {
      spec = spec.bind(e.getKey(), e.getValue());
    }
    return spec.map(
            (r, m) -> {
              UUID actor = r.get("actor_user_id", UUID.class);
              AuditEvent ev =
                  new AuditEvent()
                      .id(r.get("id", UUID.class))
                      .occurredAt(r.get("occurred_at", OffsetDateTime.class))
                      .actor(
                          actor == null
                              ? null
                              : new UserRef()
                                  .id(actor)
                                  .displayName(r.get("display_name", String.class)))
                      .actorRole(r.get("actor_role", String.class))
                      .source(AuditSource.fromValue(r.get("source", String.class)))
                      .tenderId(r.get("tender_id", UUID.class))
                      .tenderVendorId(r.get("tender_vendor_id", UUID.class))
                      .objectType(r.get("object_type", String.class))
                      .objectId(r.get("object_id", UUID.class))
                      .action(AuditAction.fromValue(r.get("action", String.class)))
                      .before(map(r.get("before", String.class)))
                      .after(map(r.get("after", String.class)))
                      .note(r.get("note", String.class));
              return ev;
            })
        .all()
        .collectList()
        .map(
            all -> {
              boolean more = all.size() > lim;
              List<AuditEvent> page = more ? new ArrayList<>(all.subList(0, lim)) : all;
              String next = null;
              if (more) {
                AuditEvent last = page.get(page.size() - 1);
                next = new Cursor(last.getOccurredAt().toString(), last.getId()).encode();
              }
              return new AuditEventPage().items(page).nextCursor(next);
            });
  }
}
