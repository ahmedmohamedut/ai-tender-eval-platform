package app.tendereval.backend.audit;

import app.tendereval.backend.common.Notes;
import app.tendereval.backend.common.Settings;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.TraceFilter;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the audit event of a change in the same transaction as the change (UX-AUD-01, UX-GEN-05):
 * every write goes through a service that calls this with the transaction's {@link DatabaseClient},
 * so there is never a change without an event or an event without a change. The table is
 * append-only for every role; a database check refuses the actions that need a justification
 * without one, and this service refuses them first with a message the user can act on.
 */
@Component
public class AuditService {

  /** Actions that carry a note of at least the minimum length (DM, kept equal to the check). */
  static final Set<String> NOTE_REQUIRED =
      Set.of(
          "requirement_edited",
          "requirement_deleted",
          "requirement_restored",
          "grade_overridden",
          "screening_reinstated",
          "baseline_reopened",
          "evaluation_reopened",
          "report_reopened",
          "member_excluded");

  private static final String INSERT =
      "INSERT INTO audit_events (source, actor_user_id, actor_role, tender_id, tender_vendor_id,"
          + " object_type, object_id, action, before, after, note, batch_id, request_id)"
          + " VALUES (:source, :actor, :role, :tender, :vendor, :otype, :oid, :action,"
          + " CAST(:before AS jsonb), CAST(:after AS jsonb), :note, :batch, :request)";

  private final JsonMapper json = JsonMapper.builder().build();

  /** A change made by a person. */
  public Mono<Void> record(DatabaseClient db, CurrentUser actor, AuditEntry entry) {
    Mono<String> role =
        entry.tenderId() == null
            ? Mono.just(actor.role().platformName())
            : db.sql("SELECT app_is_chair_of(:t) AS chair")
                .bind("t", entry.tenderId())
                .map((row, meta) -> Boolean.TRUE.equals(row.get("chair", Boolean.class)))
                .one()
                .map(chair -> chair ? "chair" : actor.role().platformName());
    return role.flatMap(r -> insert(db, "person", actor.id(), r, entry));
  }

  /** A change made by the platform itself: a worker's status or result, a timer (source system). */
  public Mono<Void> recordSystem(DatabaseClient db, AuditEntry entry) {
    return insert(db, "system", null, null, entry);
  }

  private Mono<Void> insert(
      DatabaseClient db, String source, UUID actor, String role, AuditEntry e) {
    Mono<String> note = Mono.justOrEmpty(e.note()).map(String::trim);
    if (NOTE_REQUIRED.contains(e.action())) {
      note = Settings.load(db).map(s -> Notes.require(e.note(), s.minNoteLength(), "note"));
    }
    return note.defaultIfEmpty("")
        .flatMap(
            n ->
                Mono.deferContextual(
                    ctx -> {
                      DatabaseClient.GenericExecuteSpec spec =
                          db.sql(INSERT)
                              .bind("source", source)
                              .bind("action", e.action())
                              .bind("otype", e.objectType())
                              .bind("before", json.writeValueAsString(e.before()))
                              .bind("after", json.writeValueAsString(e.after()));
                      spec = nullable(spec, "actor", actor, UUID.class);
                      spec = nullable(spec, "role", role, String.class);
                      spec = nullable(spec, "tender", e.tenderId(), UUID.class);
                      spec = nullable(spec, "vendor", e.tenderVendorId(), UUID.class);
                      spec = nullable(spec, "oid", e.objectId(), UUID.class);
                      spec = nullable(spec, "note", n.isEmpty() ? null : n, String.class);
                      spec = nullable(spec, "batch", e.batchId(), UUID.class);
                      Object trace = ctx.getOrDefault(TraceFilter.CONTEXT_KEY, null);
                      spec =
                          nullable(
                              spec,
                              "request",
                              trace == null ? null : trace.toString(),
                              String.class);
                      return spec.fetch().rowsUpdated().then();
                    }));
  }

  private static <T> DatabaseClient.GenericExecuteSpec nullable(
      DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
    return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
  }
}
