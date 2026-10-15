package app.tendereval.backend.audit;

import app.tendereval.backend.api.model.AccessEvent;
import app.tendereval.backend.api.model.AccessEventPage;
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

/**
 * Search of the document access log (UX-NFR-07, PG-20): who opened which proposal. Row-level
 * security limits it to the administrator and the Observer. Newest first; without {@code from} the
 * last 30 days.
 */
@Service
public class AccessSearch {

  public Mono<AccessEventPage> search(
      DatabaseClient db,
      Integer limit,
      String cursor,
      UUID tender,
      UUID user,
      OffsetDateTime from,
      OffsetDateTime to) {
    int lim = Cursor.limit(limit);
    StringBuilder sql =
        new StringBuilder(
            "SELECT e.id, e.opened_at, e.user_id, u.display_name, e.document_version_id,"
                + " e.tender_vendor_id, e.watermark_shown FROM document_access_events e"
                + " JOIN users u ON u.id = e.user_id WHERE e.opened_at >= :from");
    Map<String, Object> binds = new LinkedHashMap<>();
    binds.put("from", from == null ? OffsetDateTime.now().minusDays(30) : from);
    if (to != null) {
      sql.append(" AND e.opened_at <= :to");
      binds.put("to", to);
    }
    if (tender != null) {
      sql.append(
          " AND EXISTS (SELECT 1 FROM document_versions dv JOIN documents d ON d.id ="
              + " dv.document_id WHERE dv.id = e.document_version_id AND d.tender_id = :tender)");
      binds.put("tender", tender);
    }
    if (user != null) {
      sql.append(" AND e.user_id = :user");
      binds.put("user", user);
    }
    Cursor cur = Cursor.decodeOrNull(cursor);
    if (cur != null) {
      sql.append(" AND (e.opened_at, e.id) < (:after, :aid)");
      binds.put("after", OffsetDateTime.parse(cur.sortValue()));
      binds.put("aid", cur.id());
    }
    sql.append(" ORDER BY e.opened_at DESC, e.id DESC LIMIT ").append(lim + 1);
    DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString());
    for (var e : binds.entrySet()) {
      spec = spec.bind(e.getKey(), e.getValue());
    }
    return spec.map(
            (r, m) ->
                new AccessEvent()
                    .id(r.get("id", UUID.class))
                    .user(
                        new UserRef()
                            .id(r.get("user_id", UUID.class))
                            .displayName(r.get("display_name", String.class)))
                    .documentVersionId(r.get("document_version_id", UUID.class))
                    .tenderVendorId(r.get("tender_vendor_id", UUID.class))
                    .openedAt(r.get("opened_at", OffsetDateTime.class))
                    .watermarkShown(r.get("watermark_shown", Boolean.class)))
        .all()
        .collectList()
        .map(
            all -> {
              boolean more = all.size() > lim;
              List<AccessEvent> page = more ? new ArrayList<>(all.subList(0, lim)) : all;
              String next = null;
              if (more) {
                AccessEvent last = page.get(page.size() - 1);
                next = new Cursor(last.getOpenedAt().toString(), last.getId()).encode();
              }
              return new AccessEventPage().items(page).nextCursor(next);
            });
  }
}
