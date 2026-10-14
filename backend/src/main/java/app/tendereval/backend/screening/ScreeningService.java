package app.tendereval.backend.screening;

import app.tendereval.backend.api.model.ActionState;
import app.tendereval.backend.api.model.ChecklistItem;
import app.tendereval.backend.api.model.ChecklistUpdate;
import app.tendereval.backend.api.model.ChecklistUpdateItemsInner;
import app.tendereval.backend.api.model.ChecksInput;
import app.tendereval.backend.api.model.ChecksInputChecksInner;
import app.tendereval.backend.api.model.PrecheckFinding;
import app.tendereval.backend.api.model.Recommendation;
import app.tendereval.backend.api.model.RecommendationInput;
import app.tendereval.backend.api.model.RecommendationView;
import app.tendereval.backend.api.model.ReinstateInput;
import app.tendereval.backend.api.model.ScreeningDecisionInput;
import app.tendereval.backend.api.model.ScreeningDecisionView;
import app.tendereval.backend.api.model.ScreeningState;
import app.tendereval.backend.api.model.ScreeningStateChecklistInner;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.common.Actions;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.vendors.ProposalService;
import app.tendereval.backend.web.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Screening of a vendor's proposal (UX-SCR-01..07). Members tick the checklist and recommend; the
 * Chair decides. The statuses move by database triggers (V20), the rules about who may do what by
 * policies, so this service validates input, shapes the state and writes the audit trail.
 */
@Service
public class ScreeningService {

  private static final int REASON_MIN = 10;

  private final AuditService audit;
  private final ProposalService proposals;

  public ScreeningService(AuditService audit, ProposalService proposals) {
    this.audit = audit;
    this.proposals = proposals;
  }

  /** The vendor's tender and its latest proposal version, or an error. */
  private record Target(
      UUID tenderId, UUID versionId, String status, boolean member, boolean chair) {}

  private Mono<Target> target(DatabaseClient db, UUID tenderVendorId) {
    return db.sql(
            "SELECT tv.tender_id, p.id AS pv, p.status, app_is_member_of(tv.tender_id) AS member,"
                + " app_is_chair_of(tv.tender_id) AS chair FROM tender_vendors tv"
                + " LEFT JOIN LATERAL (SELECT id, status FROM proposal_versions x"
                + "   WHERE x.tender_vendor_id = tv.id ORDER BY x.version_no DESC LIMIT 1) p ON true"
                + " WHERE tv.id = :v AND tv.removed_at IS NULL")
        .bind("v", tenderVendorId)
        .map(
            (r, m) ->
                new Target(
                    r.get("tender_id", UUID.class),
                    r.get("pv", UUID.class),
                    r.get("status", String.class),
                    Boolean.TRUE.equals(r.get("member", Boolean.class)),
                    Boolean.TRUE.equals(r.get("chair", Boolean.class))))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("vendor of this tender")));
  }

  private Mono<Target> withProposal(DatabaseClient db, UUID tenderVendorId) {
    return target(db, tenderVendorId)
        .flatMap(
            t ->
                t.versionId() == null
                    ? Mono.<Target>error(
                        ApiException.conflict("no_proposal", "This vendor has no proposal yet"))
                    : Mono.just(t));
  }

  private static boolean open(Target t) {
    return "uploaded".equals(t.status()) || "in_screening".equals(t.status());
  }

  // ---- state
  // ---------------------------------------------------------------------------------------

  public Mono<ScreeningState> state(DatabaseClient db, UUID tenderVendorId) {
    return withProposal(db, tenderVendorId)
        .flatMap(
            t ->
                Mono.zip(
                        proposals
                            .list(db, tenderVendorId)
                            .map(
                                l ->
                                    l.stream()
                                        .filter(v -> v.getId().equals(t.versionId()))
                                        .findFirst()
                                        .orElse(l.get(0))),
                        checklist(db, t),
                        recommendations(db, t),
                        decision(db, t)
                            .map(java.util.Optional::of)
                            .defaultIfEmpty(java.util.Optional.empty()),
                        prechecks(db, t),
                        cleared(db, tenderVendorId))
                    .map(
                        z ->
                            new ScreeningState()
                                .tenderVendorId(tenderVendorId)
                                .proposalVersion(z.getT1())
                                .checklist(z.getT2())
                                .recommendations(z.getT3())
                                .decision(z.getT4().orElse(null))
                                .prechecks(z.getT5())
                                .actions(actions(t, z.getT6()))));
  }

  private Mono<Boolean> cleared(DatabaseClient db, UUID tv) {
    return db.sql("SELECT app_cleared_for_vendor(:v) AS ok")
        .bind("v", tv)
        .map((r, m) -> Boolean.TRUE.equals(r.get("ok", Boolean.class)))
        .one();
  }

  private Map<String, ActionState> actions(Target t, boolean cleared) {
    boolean screening = open(t);
    String closed = "Screening of this proposal is closed";
    String declare = "Declare that you have no conflict first";
    return Actions.builder()
        .when("save_checks", t.member() && screening && cleared, !screening ? closed : declare)
        .when(
            "save_recommendation",
            t.member() && screening && cleared,
            !screening ? closed : declare)
        .when("start_precheck", t.member() && screening && cleared, !screening ? closed : declare)
        .when(
            "confirm_decision",
            t.chair() && "in_screening".equals(t.status()),
            "Only the Chair decides, once screening has started")
        .when(
            "reinstate",
            t.chair() && "rejected".equals(t.status()),
            "Only a rejected vendor is reinstated")
        .build();
  }

  private Mono<List<ScreeningStateChecklistInner>> checklist(DatabaseClient db, Target t) {
    return db.sql(
            "SELECT i.id, i.label, i.sort_order, i.is_default, i.is_active, c.is_checked, c.note"
                + " FROM screening_checklist_items i LEFT JOIN screening_checks c"
                + " ON c.checklist_item_id = i.id AND c.proposal_version_id = :pv"
                + " AND c.user_id = app_user_id() WHERE i.tender_id = :t AND i.is_active"
                + " ORDER BY i.sort_order, i.created_at")
        .bind("pv", t.versionId())
        .bind("t", t.tenderId())
        .map(
            (r, m) ->
                new ScreeningStateChecklistInner()
                    .item(
                        new ChecklistItem()
                            .id(r.get("id", UUID.class))
                            .label(r.get("label", String.class))
                            .sortOrder(r.get("sort_order", Integer.class))
                            .isDefault(r.get("is_default", Boolean.class))
                            .isActive(r.get("is_active", Boolean.class)))
                    .myCheck(Boolean.TRUE.equals(r.get("is_checked", Boolean.class)))
                    .myNote(r.get("note", String.class)))
        .all()
        .collectList();
  }

  private Mono<List<RecommendationView>> recommendations(DatabaseClient db, Target t) {
    return db.sql(
            "SELECT r.recommendation, r.reason, r.updated_at, r.user_id, u.display_name"
                + " FROM screening_recommendations r JOIN users u ON u.id = r.user_id"
                + " WHERE r.proposal_version_id = :pv ORDER BY r.updated_at")
        .bind("pv", t.versionId())
        .map(
            (r, m) ->
                new RecommendationView()
                    .user(
                        new UserRef()
                            .id(r.get("user_id", UUID.class))
                            .displayName(r.get("display_name", String.class)))
                    .recommendation(Recommendation.fromValue(r.get("recommendation", String.class)))
                    .reason(r.get("reason", String.class))
                    .at(r.get("updated_at", OffsetDateTime.class)))
        .all()
        .collectList();
  }

  private Mono<ScreeningDecisionView> decision(DatabaseClient db, Target t) {
    return db.sql(
            "SELECT d.decision, d.reason, d.decided_at, d.is_reinstatement, d.decided_by, u.display_name"
                + " FROM screening_decisions d JOIN users u ON u.id = d.decided_by"
                + " WHERE d.proposal_version_id = :pv ORDER BY d.decided_at DESC, d.id DESC LIMIT 1")
        .bind("pv", t.versionId())
        .map(
            (r, m) ->
                new ScreeningDecisionView()
                    .decision(Recommendation.fromValue(r.get("decision", String.class)))
                    .reason(r.get("reason", String.class))
                    .decidedAt(r.get("decided_at", OffsetDateTime.class))
                    .isReinstatement(r.get("is_reinstatement", Boolean.class))
                    .decidedBy(
                        new UserRef()
                            .id(r.get("decided_by", UUID.class))
                            .displayName(r.get("display_name", String.class))))
        .one();
  }

  private Mono<List<PrecheckFinding>> prechecks(DatabaseClient db, Target t) {
    return db.sql(
            "SELECT expected_section, found, note FROM screening_prechecks"
                + " WHERE proposal_version_id = :pv ORDER BY created_at, id")
        .bind("pv", t.versionId())
        .map(
            (r, m) ->
                new PrecheckFinding()
                    .expectedSection(r.get("expected_section", String.class))
                    .found(r.get("found", Boolean.class))
                    .note(r.get("note", String.class)))
        .all()
        .collectList();
  }

  // ---- writes
  // ----------------------------------------------------------------------------------------

  public Mono<ScreeningState> saveChecks(
      DatabaseClient db, CurrentUser actor, UUID tenderVendorId, ChecksInput in) {
    if (in.getChecks() == null || in.getChecks().isEmpty()) {
      return Mono.error(ApiException.invalid("checks", "required", "Tick at least one item"));
    }
    return withProposal(db, tenderVendorId)
        .flatMap(
            t ->
                guarded(
                    db,
                    t,
                    tenderVendorId,
                    () -> {
                      Mono<Void> writes = Mono.empty();
                      for (ChecksInputChecksInner c : in.getChecks()) {
                        DatabaseClient.GenericExecuteSpec spec =
                            db.sql(
                                    "INSERT INTO screening_checks (proposal_version_id, checklist_item_id,"
                                        + " user_id, is_checked, note) VALUES (:pv, :i, :u, :c, :n)"
                                        + " ON CONFLICT (proposal_version_id, checklist_item_id, user_id)"
                                        + " DO UPDATE SET is_checked = EXCLUDED.is_checked, note = EXCLUDED.note")
                                .bind("pv", t.versionId())
                                .bind("i", c.getChecklistItemId())
                                .bind("u", actor.id())
                                .bind("c", Boolean.TRUE.equals(c.getIsChecked()));
                        String note =
                            c.getNote() == null || c.getNote().isBlank()
                                ? null
                                : c.getNote().strip();
                        spec =
                            note == null ? spec.bindNull("n", String.class) : spec.bind("n", note);
                        writes = writes.then(spec.fetch().rowsUpdated().then());
                      }
                      return writes
                          .onErrorMap(DataIntegrityViolationException.class, e -> clash(e))
                          .then(
                              audit.record(
                                  db,
                                  actor,
                                  AuditEntry.of(
                                          "screening_checks_saved",
                                          "proposal_version",
                                          t.versionId())
                                      .tender(t.tenderId())
                                      .vendor(tenderVendorId)
                                      .created(Map.of("items", in.getChecks().size()))
                                      .build()))
                          .then(state(db, actor, tenderVendorId));
                    }));
  }

  public Mono<ScreeningState> saveRecommendation(
      DatabaseClient db, CurrentUser actor, UUID tenderVendorId, RecommendationInput in) {
    String rec = in.getRecommendation() == null ? null : in.getRecommendation().getValue();
    String reason = in.getReason() == null ? null : in.getReason().strip();
    if (rec == null) {
      return Mono.error(
          ApiException.invalid("recommendation", "required", "Choose a recommendation"));
    }
    if (!"accept".equals(rec) && (reason == null || reason.length() < 1)) {
      return Mono.error(ApiException.invalid("reason", "required", "Give a reason"));
    }
    return withProposal(db, tenderVendorId)
        .flatMap(
            t ->
                guarded(
                    db,
                    t,
                    tenderVendorId,
                    () -> {
                      DatabaseClient.GenericExecuteSpec spec =
                          db.sql(
                                  "INSERT INTO screening_recommendations (proposal_version_id, user_id,"
                                      + " recommendation, reason) VALUES (:pv, :u, :r, :n)"
                                      + " ON CONFLICT (proposal_version_id, user_id) DO UPDATE SET"
                                      + " recommendation = EXCLUDED.recommendation, reason = EXCLUDED.reason")
                              .bind("pv", t.versionId())
                              .bind("u", actor.id())
                              .bind("r", rec);
                      spec =
                          reason == null || reason.isEmpty()
                              ? spec.bindNull("n", String.class)
                              : spec.bind("n", reason);
                      return spec.fetch()
                          .rowsUpdated()
                          .onErrorMap(DataIntegrityViolationException.class, e -> clash(e))
                          .then(
                              audit.record(
                                  db,
                                  actor,
                                  AuditEntry.of(
                                          "screening_recommended",
                                          "proposal_version",
                                          t.versionId())
                                      .tender(t.tenderId())
                                      .vendor(tenderVendorId)
                                      .created(Map.of("recommendation", rec))
                                      .build()))
                          .then(state(db, actor, tenderVendorId));
                    }));
  }

  /** A member's own screening write: a committee member, still screening, and cleared. */
  private Mono<ScreeningState> guarded(
      DatabaseClient db,
      Target t,
      UUID tenderVendorId,
      java.util.function.Supplier<Mono<ScreeningState>> write) {
    if (!t.member()) {
      return Mono.error(
          ApiException.forbidden("forbidden", "Only the committee screens proposals"));
    }
    if (!open(t)) {
      return Mono.error(
          ApiException.conflict("screening_closed", "Screening of this proposal is closed"));
    }
    return cleared(db, tenderVendorId)
        .flatMap(
            ok ->
                ok
                    ? write.get()
                    : Mono.<ScreeningState>error(
                        ApiException.forbidden(
                            "declaration_required",
                            "Declare that you have no conflict for this vendor first")));
  }

  public Mono<ScreeningState> decide(
      DatabaseClient db, CurrentUser actor, UUID tenderVendorId, ScreeningDecisionInput in) {
    String decision = in.getDecision() == null ? null : in.getDecision().getValue();
    String reason = in.getReason() == null ? null : in.getReason().strip();
    if (decision == null) {
      return Mono.error(ApiException.invalid("decision", "required", "Choose a decision"));
    }
    if (!"accept".equals(decision) && (reason == null || reason.length() < REASON_MIN)) {
      return Mono.error(
          ApiException.invalid("reason", "too_short", "Give a reason of at least 10 characters"));
    }
    return withProposal(db, tenderVendorId)
        .flatMap(
            t -> {
              if (!t.chair()) {
                return Mono.<ScreeningState>error(
                    ApiException.forbidden("forbidden", "Only the Chair confirms a decision"));
              }
              if (!"in_screening".equals(t.status())) {
                return Mono.<ScreeningState>error(
                    ApiException.conflict(
                        "not_in_screening", "A decision needs a proposal that is in screening"));
              }
              return insertDecision(db, actor, t, decision, reason, false)
                  .then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("screening_decided", "proposal_version", t.versionId())
                              .tender(t.tenderId())
                              .vendor(tenderVendorId)
                              .created(Map.of("decision", decision))
                              .note(reason)
                              .build()))
                  .then(state(db, actor, tenderVendorId));
            });
  }

  public Mono<ScreeningState> reinstate(
      DatabaseClient db, CurrentUser actor, UUID tenderVendorId, ReinstateInput in) {
    String note = in.getNote() == null ? null : in.getNote().strip();
    if (note == null || note.length() < REASON_MIN) {
      return Mono.error(
          ApiException.invalid("note", "too_short", "Give a note of at least 10 characters"));
    }
    return withProposal(db, tenderVendorId)
        .flatMap(
            t -> {
              if (!t.chair()) {
                return Mono.<ScreeningState>error(
                    ApiException.forbidden("forbidden", "Only the Chair reinstates a vendor"));
              }
              if (!"rejected".equals(t.status())) {
                return Mono.<ScreeningState>error(
                    ApiException.conflict("not_rejected", "Only a rejected vendor is reinstated"));
              }
              return insertDecision(db, actor, t, "accept", note, true)
                  .then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("screening_reinstated", "proposal_version", t.versionId())
                              .tender(t.tenderId())
                              .vendor(tenderVendorId)
                              .note(note)
                              .build()))
                  .then(state(db, actor, tenderVendorId));
            });
  }

  private Mono<Void> insertDecision(
      DatabaseClient db,
      CurrentUser actor,
      Target t,
      String decision,
      String reason,
      boolean reinstated) {
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
                "INSERT INTO screening_decisions (proposal_version_id, decision, reason, decided_by,"
                    + " is_reinstatement) VALUES (:pv, :d, :r, :u, :x)")
            .bind("pv", t.versionId())
            .bind("d", decision)
            .bind("u", actor.id())
            .bind("x", reinstated);
    spec =
        reason == null || reason.isEmpty()
            ? spec.bindNull("r", String.class)
            : spec.bind("r", reason);
    return spec.fetch()
        .rowsUpdated()
        .onErrorMap(DataIntegrityViolationException.class, e -> clash(e))
        .then();
  }

  /** The state for the signed-in person: their own check marks and recommendation filled in. */
  public Mono<ScreeningState> state(DatabaseClient db, CurrentUser actor, UUID tenderVendorId) {
    return state(db, tenderVendorId)
        .map(
            s -> {
              RecommendationView mine =
                  s.getRecommendations().stream()
                      .filter(r -> r.getUser() != null && actor.id().equals(r.getUser().getId()))
                      .findFirst()
                      .orElse(null);
              return s.myRecommendation(mine);
            });
  }

  private static ApiException clash(DataIntegrityViolationException e) {
    String m = String.valueOf(e.getMessage());
    if (m.contains("closed")) {
      return ApiException.conflict("screening_closed", "Screening of this proposal is closed");
    }
    if (m.contains("another tender")) {
      return ApiException.invalid(
          "checklist_item_id", "unknown_item", "Choose an item of this tender");
    }
    return ApiException.conflict("not_allowed", "This change is not allowed now");
  }

  // ---- checklist
  // -------------------------------------------------------------------------------------

  public Mono<List<ChecklistItem>> checklistOf(DatabaseClient db, UUID tenderId) {
    return db.sql("SELECT 1 AS x FROM tenders WHERE id = :t")
        .bind("t", tenderId)
        .map((r, m) -> 1)
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("tender")))
        .thenMany(
            db.sql(
                    "SELECT id, label, sort_order, is_default, is_active FROM screening_checklist_items"
                        + " WHERE tender_id = :t ORDER BY sort_order, created_at")
                .bind("t", tenderId)
                .map(
                    (r, m) ->
                        new ChecklistItem()
                            .id(r.get("id", UUID.class))
                            .label(r.get("label", String.class))
                            .sortOrder(r.get("sort_order", Integer.class))
                            .isDefault(r.get("is_default", Boolean.class))
                            .isActive(r.get("is_active", Boolean.class)))
                .all())
        .collectList();
  }

  public Mono<List<ChecklistItem>> setChecklist(
      DatabaseClient db, CurrentUser actor, UUID tenderId, ChecklistUpdate in) {
    if (in.getItems() == null || in.getItems().isEmpty()) {
      return Mono.error(ApiException.invalid("items", "required", "Send the checklist items"));
    }
    for (ChecklistUpdateItemsInner i : in.getItems()) {
      String label = i.getLabel() == null ? "" : i.getLabel().strip();
      if (label.isEmpty() || label.length() > 200) {
        return Mono.error(
            ApiException.invalid(
                "items", "label", "Each item needs a label of up to 200 characters"));
      }
    }
    return db.sql("SELECT app_is_chair_of(:t) AS chair, app_screening_started(:t) AS started")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new boolean[] {
                  Boolean.TRUE.equals(r.get("chair", Boolean.class)),
                  Boolean.TRUE.equals(r.get("started", Boolean.class))
                })
        .one()
        .flatMap(
            f -> {
              if (!f[0]) {
                return Mono.<List<ChecklistItem>>error(
                    ApiException.forbidden("forbidden", "Only the Chair edits the checklist"));
              }
              if (f[1]) {
                return Mono.<List<ChecklistItem>>error(
                    ApiException.conflict(
                        "checklist_locked", "The checklist is locked once screening has started"));
              }
              Mono<Void> writes = Mono.empty();
              int n = 0;
              for (ChecklistUpdateItemsInner i : in.getItems()) {
                int order = i.getSortOrder() == null ? ++n : i.getSortOrder();
                boolean active = i.getIsActive() == null || i.getIsActive();
                String label = i.getLabel().strip();
                DatabaseClient.GenericExecuteSpec spec =
                    i.getId() == null
                        ? db.sql(
                                "INSERT INTO screening_checklist_items (tender_id, label, sort_order,"
                                    + " is_active) VALUES (:t, :l, :o, :a)")
                            .bind("t", tenderId)
                        : db.sql(
                                "UPDATE screening_checklist_items SET label = :l, sort_order = :o,"
                                    + " is_active = :a WHERE id = :i AND tender_id = :t")
                            .bind("t", tenderId)
                            .bind("i", i.getId());
                spec = spec.bind("l", label).bind("o", order).bind("a", active);
                writes = writes.then(spec.fetch().rowsUpdated().then());
              }
              return writes
                  .onErrorMap(DataIntegrityViolationException.class, e -> clash(e))
                  .then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("screening_checklist_changed", "tender", tenderId)
                              .tender(tenderId)
                              .created(Map.of("items", in.getItems().size()))
                              .build()))
                  .then(checklistOf(db, tenderId));
            });
  }
}
