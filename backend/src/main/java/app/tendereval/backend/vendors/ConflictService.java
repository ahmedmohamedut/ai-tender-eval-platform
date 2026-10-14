package app.tendereval.backend.vendors;

import app.tendereval.backend.api.model.ConflictDeclaration;
import app.tendereval.backend.api.model.ConflictDeclarationInput;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.ApiException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The conflict and confidentiality declaration a member makes before opening a vendor's proposal
 * (UX-TND-07). It is immutable. A declared conflict excludes the member from the vendor; the
 * database does that in the same transaction (trigger) and the policies do the rest.
 */
@Service
public class ConflictService {

  /** A new declaration with the tender it belongs to, so the caller can push after the commit. */
  public record Declared(ConflictDeclaration declaration, UUID tenderId) {}

  private final AuditService audit;

  public ConflictService(AuditService audit) {
    this.audit = audit;
  }

  public Mono<ConflictDeclaration> mine(DatabaseClient db, UUID tenderVendorId) {
    return db.sql("SELECT 1 AS x FROM tender_vendors WHERE id = :v AND removed_at IS NULL")
        .bind("v", tenderVendorId)
        .map((r, m) -> 1)
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("vendor of this tender")))
        .then(
            db.sql(
                    "SELECT tender_vendor_id, user_id, has_conflict, statement, declared_at"
                        + " FROM conflict_declarations WHERE tender_vendor_id = :v"
                        + " AND user_id = app_user_id()")
                .bind("v", tenderVendorId)
                .map((r, m) -> map(r))
                .one());
  }

  public Mono<Declared> declare(
      DatabaseClient db, CurrentUser actor, UUID tenderVendorId, ConflictDeclarationInput in) {
    boolean conflict = Boolean.TRUE.equals(in.getHasConflict());
    String statement = in.getStatement() == null ? null : in.getStatement().strip();
    if (statement != null && statement.isEmpty()) {
      statement = null;
    }
    if (conflict && (statement == null || statement.length() < 10)) {
      return Mono.error(
          ApiException.invalid(
              "statement", "required", "Describe the conflict in at least 10 characters"));
    }
    if (statement != null && statement.length() > 2000) {
      return Mono.error(
          ApiException.invalid("statement", "too_long", "Keep it under 2,000 characters"));
    }
    String saved = statement;
    return db.sql(
            "SELECT tender_id FROM tender_vendors WHERE id = :v AND removed_at IS NULL"
                + " AND app_is_member_of(tender_id)")
        .bind("v", tenderVendorId)
        .map((r, m) -> r.get("tender_id", UUID.class))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("vendor of this tender")))
        .flatMap(
            tenderId -> {
              DatabaseClient.GenericExecuteSpec insert =
                  db.sql(
                          "INSERT INTO conflict_declarations (tender_vendor_id, user_id, has_conflict,"
                              + " statement) VALUES (:v, :u, :c, :s)"
                              + " RETURNING id, tender_vendor_id, user_id, has_conflict, statement,"
                              + " declared_at")
                      .bind("v", tenderVendorId)
                      .bind("u", actor.id())
                      .bind("c", conflict);
              insert = saved == null ? insert.bindNull("s", String.class) : insert.bind("s", saved);
              return insert
                  .map((r, m) -> Map.entry(r.get("id", UUID.class), map(r)))
                  .one()
                  .onErrorMap(
                      DataIntegrityViolationException.class,
                      e ->
                          ApiException.conflict(
                              "already_declared", "You have already declared for this vendor"))
                  .flatMap(
                      e ->
                          audit
                              .record(
                                  db,
                                  actor,
                                  AuditEntry.of(
                                          "conflict_declared", "conflict_declaration", e.getKey())
                                      .tender(tenderId)
                                      .vendor(tenderVendorId)
                                      .created(Map.of("has_conflict", conflict))
                                      .build())
                              .thenReturn(new Declared(e.getValue(), tenderId)));
            });
  }

  private static ConflictDeclaration map(io.r2dbc.spi.Readable r) {
    return new ConflictDeclaration()
        .tenderVendorId(r.get("tender_vendor_id", UUID.class))
        .userId(r.get("user_id", UUID.class))
        .hasConflict(r.get("has_conflict", Boolean.class))
        .statement(r.get("statement", String.class))
        .declaredAt(r.get("declared_at", OffsetDateTime.class));
  }
}
