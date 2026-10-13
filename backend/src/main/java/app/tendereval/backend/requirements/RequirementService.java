package app.tendereval.backend.requirements;

import app.tendereval.backend.api.model.AuditAction;
import app.tendereval.backend.api.model.AuditSource;
import app.tendereval.backend.api.model.BulkRequirements;
import app.tendereval.backend.api.model.BulkResult;
import app.tendereval.backend.api.model.BulkResultSkippedInner;
import app.tendereval.backend.api.model.Category;
import app.tendereval.backend.api.model.CategoryInput;
import app.tendereval.backend.api.model.DeleteRequirement;
import app.tendereval.backend.api.model.DocType;
import app.tendereval.backend.api.model.FlagType;
import app.tendereval.backend.api.model.History;
import app.tendereval.backend.api.model.HistoryEvent;
import app.tendereval.backend.api.model.Importance;
import app.tendereval.backend.api.model.MergeRequirements;
import app.tendereval.backend.api.model.Requirement;
import app.tendereval.backend.api.model.RequirementCreate;
import app.tendereval.backend.api.model.RequirementFlag;
import app.tendereval.backend.api.model.RequirementOrigin;
import app.tendereval.backend.api.model.RequirementPage;
import app.tendereval.backend.api.model.RequirementPart;
import app.tendereval.backend.api.model.RequirementPatch;
import app.tendereval.backend.api.model.RequirementReviewState;
import app.tendereval.backend.api.model.RequirementSource;
import app.tendereval.backend.api.model.RequirementsSummary;
import app.tendereval.backend.api.model.SplitRequirement;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.common.Actions;
import app.tendereval.backend.common.Cursor;
import app.tendereval.backend.common.ETags;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.ApiException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared requirements list (UX-REQ-01..14). Reads and writes run as the request role: row level
 * security decides who sees a tender's list and who may edit it (members, while no baseline is
 * confirmed). Every write also writes its audit event in the same transaction; history is read back
 * from those events.
 */
@Service
public class RequirementService {

  private static final String SELECT =
      "SELECT r.id, r.tender_id, r.display_no, r.text, r.category_id, c.name AS category_name,"
          + " c.sort_order AS category_order, c.is_active AS category_active, r.importance,"
          + " r.acceptance_criteria, r.origin, r.review_state, r.ai_original::text AS ai_original,"
          + " r.last_changed_by, u.display_name AS changed_by_name, r.last_changed_at,"
          + " r.deleted_at, r.delete_reason, r.duplicate_of_requirement_id, r.row_version,"
          + " app_can_edit_requirements(r.tender_id) AS can_edit,"
          + " app_baseline_locked(r.tender_id) AS locked"
          + " FROM requirements r LEFT JOIN requirement_categories c ON c.id = r.category_id"
          + " LEFT JOIN users u ON u.id = r.last_changed_by";

  private final AuditService audit;
  private final JsonMapper json = JsonMapper.builder().build();

  public RequirementService(AuditService audit) {
    this.audit = audit;
  }

  // ---- mapping
  // -------------------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private Requirement map(io.r2dbc.spi.Readable r) {
    boolean deleted = r.get("deleted_at", OffsetDateTime.class) != null;
    boolean canEdit = Boolean.TRUE.equals(r.get("can_edit", Boolean.class));
    boolean locked = Boolean.TRUE.equals(r.get("locked", Boolean.class));
    UUID by = r.get("last_changed_by", UUID.class);
    String ai = r.get("ai_original", String.class);
    String reason = r.get("delete_reason", String.class);
    UUID categoryId = r.get("category_id", UUID.class);
    String lockedReason = "The baseline is confirmed. Reopen it to edit";
    Actions actions =
        Actions.builder()
            .when("edit", canEdit && !deleted, locked ? lockedReason : "You cannot edit this list")
            .when(
                "delete", canEdit && !deleted, locked ? lockedReason : "You cannot edit this list")
            .when("restore", canEdit && deleted, "Only a deleted requirement can be restored")
            .when(
                "review", canEdit && !deleted, locked ? lockedReason : "You cannot edit this list")
            .when("merge", canEdit && !deleted, locked ? lockedReason : "You cannot edit this list")
            .when(
                "split", canEdit && !deleted, locked ? lockedReason : "You cannot edit this list");
    Requirement out =
        new Requirement()
            .id(r.get("id", UUID.class))
            .tenderId(r.get("tender_id", UUID.class))
            .displayNo(r.get("display_no", String.class))
            .text(r.get("text", String.class))
            .importance(Importance.fromValue(r.get("importance", String.class)))
            .acceptanceCriteria(r.get("acceptance_criteria", String.class))
            .origin(RequirementOrigin.fromValue(r.get("origin", String.class)))
            .reviewState(RequirementReviewState.fromValue(r.get("review_state", String.class)))
            .sources(new ArrayList<>())
            .flags(new ArrayList<>())
            .lastChangedBy(
                by == null || r.get("changed_by_name", String.class) == null
                    ? null
                    : new UserRef().id(by).displayName(r.get("changed_by_name", String.class)))
            .lastChangedAt(r.get("last_changed_at", OffsetDateTime.class))
            .deleted(deleted)
            .duplicateOfId(r.get("duplicate_of_requirement_id", UUID.class))
            .rowVersion(r.get("row_version", Integer.class))
            .actions(actions.build());
    if (categoryId != null) {
      out.category(
          new Category()
              .id(categoryId)
              .name(r.get("category_name", String.class))
              .sortOrder(r.get("category_order", Integer.class))
              .isActive(r.get("category_active", Boolean.class)));
    }
    if (reason != null) {
      out.deleteReason(app.tendereval.backend.api.model.DeleteReason.fromValue(reason));
    }
    if (ai != null) {
      out.aiOriginal((Map<String, Object>) json.readValue(ai, Map.class));
    }
    return out;
  }

  private Mono<List<Requirement>> withDetail(DatabaseClient db, List<Requirement> items) {
    if (items.isEmpty()) {
      return Mono.just(items);
    }
    List<UUID> ids = items.stream().map(Requirement::getId).toList();
    Mono<Map<UUID, List<RequirementSource>>> sources =
        db.sql(
                "SELECT s.requirement_id, s.document_version_id, s.passage_id, s.page_no, s.quote,"
                    + " d.id AS document_id, d.doc_type FROM requirement_sources s"
                    + " JOIN document_versions v ON v.id = s.document_version_id"
                    + " JOIN documents d ON d.id = v.document_id"
                    + " WHERE s.requirement_id IN (:ids) ORDER BY s.created_at, s.id")
            .bind("ids", ids)
            .map(
                (r, m) ->
                    Map.entry(
                        r.get("requirement_id", UUID.class),
                        new RequirementSource()
                            .documentVersionId(r.get("document_version_id", UUID.class))
                            .documentId(r.get("document_id", UUID.class))
                            .docType(DocType.fromValue(r.get("doc_type", String.class)))
                            .pageNo(r.get("page_no", Integer.class))
                            .passageId(r.get("passage_id", UUID.class))
                            .quote(r.get("quote", String.class))))
            .all()
            .collectList()
            .map(
                l ->
                    l.stream()
                        .collect(
                            Collectors.groupingBy(
                                Map.Entry::getKey,
                                Collectors.mapping(Map.Entry::getValue, Collectors.toList()))));
    Mono<Map<UUID, List<RequirementFlag>>> flags =
        db.sql(
                "SELECT id, requirement_id, flag_type, detail, related_requirement_id, resolved_at"
                    + " FROM requirement_flags WHERE requirement_id IN (:ids) ORDER BY created_at, id")
            .bind("ids", ids)
            .map(
                (r, m) ->
                    Map.entry(
                        r.get("requirement_id", UUID.class),
                        new RequirementFlag()
                            .id(r.get("id", UUID.class))
                            .flagType(FlagType.fromValue(r.get("flag_type", String.class)))
                            .detail(r.get("detail", String.class))
                            .relatedRequirementId(r.get("related_requirement_id", UUID.class))
                            .resolvedAt(r.get("resolved_at", OffsetDateTime.class))))
            .all()
            .collectList()
            .map(
                l ->
                    l.stream()
                        .collect(
                            Collectors.groupingBy(
                                Map.Entry::getKey,
                                Collectors.mapping(Map.Entry::getValue, Collectors.toList()))));
    return Mono.zip(sources, flags)
        .map(
            t -> {
              for (Requirement r : items) {
                r.setSources(t.getT1().getOrDefault(r.getId(), List.of()));
                r.setFlags(t.getT2().getOrDefault(r.getId(), List.of()));
              }
              return items;
            });
  }

  // ---- reads
  // ----------------------------------------------------------------------------------------

  public Mono<Requirement> get(DatabaseClient db, UUID id) {
    return db.sql(SELECT + " WHERE r.id = :id")
        .bind("id", id)
        .map((r, m) -> map(r))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("requirement")))
        .flatMap(r -> withDetail(db, List.of(r)).map(l -> l.get(0)));
  }

  public Mono<RequirementPage> list(
      DatabaseClient db,
      UUID tenderId,
      Integer limit,
      String cursor,
      String q,
      UUID categoryId,
      Importance importance,
      RequirementOrigin origin,
      RequirementReviewState state,
      Boolean flagged,
      Boolean deleted,
      UUID documentVersionId) {
    int size = Cursor.limit(limit);
    Cursor c = Cursor.decodeOrNull(cursor);
    StringBuilder where = new StringBuilder(" WHERE r.tender_id = :t");
    Map<String, Object> binds = new LinkedHashMap<>();
    binds.put("t", tenderId);
    where.append(
        Boolean.TRUE.equals(deleted)
            ? " AND r.deleted_at IS NOT NULL"
            : " AND r.deleted_at IS NULL");
    if (q != null && !q.isBlank()) {
      where.append(
          " AND (to_tsvector('english', r.text) @@ plainto_tsquery('english', :q)"
              + " OR r.display_no ILIKE :qlike OR r.text ILIKE :qlike)");
      binds.put("q", q.trim());
      binds.put("qlike", "%" + q.trim() + "%");
    }
    if (categoryId != null) {
      where.append(" AND r.category_id = :cat");
      binds.put("cat", categoryId);
    }
    if (importance != null) {
      where.append(" AND r.importance = :imp");
      binds.put("imp", importance.getValue());
    }
    if (origin != null) {
      where.append(" AND r.origin = :org");
      binds.put("org", origin.getValue());
    }
    if (state != null) {
      where.append(" AND r.review_state = :st");
      binds.put("st", state.getValue());
    }
    if (Boolean.TRUE.equals(flagged)) {
      where.append(
          " AND EXISTS (SELECT 1 FROM requirement_flags f WHERE f.requirement_id = r.id"
              + " AND f.resolved_at IS NULL)");
    }
    if (documentVersionId != null) {
      where.append(
          " AND EXISTS (SELECT 1 FROM requirement_sources s WHERE s.requirement_id = r.id"
              + " AND s.document_version_id = :dv)");
      binds.put("dv", documentVersionId);
    }
    String filter = where.toString();
    String page = filter;
    Map<String, Object> pageBinds = new LinkedHashMap<>(binds);
    String order = "(substring(r.display_no FROM 5))::integer, r.id";
    if (c != null) {
      page += " AND ((substring(r.display_no FROM 5))::integer, r.id) > (:no, :cid)";
      try {
        pageBinds.put("no", Integer.parseInt(c.sortValue()));
      } catch (NumberFormatException e) {
        return Mono.error(
            new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor", "The cursor is not valid"));
      }
      pageBinds.put("cid", c.id());
    }
    DatabaseClient.GenericExecuteSpec rows =
        db.sql(SELECT + page + " ORDER BY " + order + " LIMIT " + (size + 1));
    for (var e : pageBinds.entrySet()) {
      rows = rows.bind(e.getKey(), e.getValue());
    }
    DatabaseClient.GenericExecuteSpec count =
        db.sql("SELECT count(*) AS n FROM requirements r" + filter);
    for (var e : binds.entrySet()) {
      count = count.bind(e.getKey(), e.getValue());
    }
    return rows.map((r, m) -> map(r))
        .all()
        .collectList()
        .zipWith(count.map((r, m) -> r.get("n", Long.class)).one())
        .flatMap(
            t -> {
              List<Requirement> items = t.getT1();
              String next = null;
              if (items.size() > size) {
                items = new ArrayList<>(items.subList(0, size));
                Requirement last = items.get(size - 1);
                next =
                    new Cursor(
                            last.getDisplayNo().substring(4).replaceFirst("^0+(?!$)", ""),
                            last.getId())
                        .encode();
              }
              final String nextCursor = next;
              return withDetail(db, items)
                  .map(
                      l ->
                          new RequirementPage()
                              .items(l)
                              .nextCursor(nextCursor)
                              .total(t.getT2().intValue()));
            });
  }

  public Mono<RequirementsSummary> summary(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT count(*) FILTER (WHERE deleted_at IS NULL) AS total,"
                + " count(*) FILTER (WHERE deleted_at IS NULL AND importance = 'mandatory') AS mandatory,"
                + " count(*) FILTER (WHERE deleted_at IS NULL AND importance = 'optional') AS optional,"
                + " count(*) FILTER (WHERE deleted_at IS NULL AND review_state <> 'unreviewed') AS reviewed,"
                + " count(*) FILTER (WHERE deleted_at IS NOT NULL) AS deleted,"
                + " count(*) FILTER (WHERE deleted_at IS NULL AND EXISTS (SELECT 1 FROM requirement_flags f"
                + "   WHERE f.requirement_id = r.id AND f.resolved_at IS NULL)) AS flagged"
                + " FROM requirements r WHERE tender_id = :t")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new RequirementsSummary()
                    .total(r.get("total", Long.class).intValue())
                    .mandatory(r.get("mandatory", Long.class).intValue())
                    .optional(r.get("optional", Long.class).intValue())
                    .reviewed(r.get("reviewed", Long.class).intValue())
                    .flagged(r.get("flagged", Long.class).intValue())
                    .deleted(r.get("deleted", Long.class).intValue()))
        .one();
  }

  public Mono<History> history(DatabaseClient db, UUID id) {
    return get(db, id)
        .flatMap(
            req ->
                db.sql(
                        "SELECT id, occurred_at, source, action, actor_user_id, display_name,"
                            + " before_json AS before, after_json AS after, note"
                            + " FROM app_requirement_history(:id)")
                    .bind("id", id)
                    .map(
                        (r, m) -> {
                          UUID actor = r.get("actor_user_id", UUID.class);
                          String name = r.get("display_name", String.class);
                          HistoryEvent ev =
                              new HistoryEvent()
                                  .id(r.get("id", UUID.class))
                                  .occurredAt(r.get("occurred_at", OffsetDateTime.class))
                                  .source(AuditSource.fromValue(r.get("source", String.class)))
                                  .action(AuditAction.fromValue(r.get("action", String.class)))
                                  .actor(
                                      actor == null || name == null
                                          ? null
                                          : new UserRef().id(actor).displayName(name))
                                  .note(r.get("note", String.class));
                          ev.setBefore(toMap(r.get("before", String.class)));
                          ev.setAfter(toMap(r.get("after", String.class)));
                          return ev;
                        })
                    .all()
                    .collectList()
                    .map(
                        events -> {
                          History h = new History().events(events);
                          h.setAiOriginal(req.getAiOriginal());
                          return h;
                        }));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> toMap(String jsonText) {
    if (jsonText == null) {
      return null;
    }
    return (Map<String, Object>) json.readValue(jsonText, Map.class);
  }

  // ---- writes
  // ---------------------------------------------------------------------------------------

  /** The row as it stands, for a write: 404 when unseen, 409 when locked, 403 when not allowed. */
  private Mono<Requirement> writable(DatabaseClient db, UUID id) {
    return get(db, id)
        .flatMap(
            r -> {
              Boolean allowed =
                  r.getActions() != null
                      && r.getActions().get("edit") != null
                      && r.getActions().get("edit").getAllowed();
              if (Boolean.TRUE.equals(allowed) || r.getDeleted()) {
                return Mono.just(r);
              }
              return Mono.<Requirement>error(refusal(db, r));
            });
  }

  private ApiException refusal(DatabaseClient db, Requirement r) {
    String reason =
        r.getActions().get("edit").getReason() == null
            ? ""
            : r.getActions().get("edit").getReason();
    return reason.startsWith("The baseline")
        ? ApiException.conflict("baseline_locked", "The baseline is confirmed. Reopen it to edit")
        : ApiException.forbidden("forbidden", "You cannot edit this list");
  }

  private Map<String, Object> fields(Requirement r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("text", r.getText());
    m.put("category_id", r.getCategory() == null ? null : r.getCategory().getId().toString());
    m.put("importance", r.getImportance().getValue());
    m.put("acceptance_criteria", r.getAcceptanceCriteria());
    return m;
  }

  public Mono<Requirement> create(
      DatabaseClient db, CurrentUser actor, UUID tenderId, RequirementCreate in) {
    if (in.getText() == null || in.getText().isBlank()) {
      return Mono.error(ApiException.invalid("text", "required", "Enter the requirement"));
    }
    RequirementSource src = in.getSource();
    return db.sql("SELECT app_baseline_locked(:t) AS locked, app_can_edit_requirements(:t) AS can")
        .bind("t", tenderId)
        .map((r, m) -> new boolean[] {r.get("locked", Boolean.class), r.get("can", Boolean.class)})
        .one()
        .flatMap(
            s -> {
              if (!s[1]) {
                return Mono.<Requirement>error(
                    s[0]
                        ? ApiException.conflict(
                            "baseline_locked", "The baseline is confirmed. Reopen it to edit")
                        : ApiException.forbidden("forbidden", "You cannot edit this list"));
              }
              return db.sql(
                      "INSERT INTO requirements (tender_id, text, category_id, importance,"
                          + " acceptance_criteria, origin, review_state, last_changed_by,"
                          + " last_changed_at) VALUES (:t, :text, :cat, :imp, :ac, 'manual',"
                          + " 'reviewed', :u, now()) RETURNING id")
                  .bind("t", tenderId)
                  .bind("text", in.getText().trim())
                  .bind("cat", in.getCategoryId())
                  .bind("imp", in.getImportance().getValue())
                  .bind("ac", in.getAcceptanceCriteria() == null ? "" : in.getAcceptanceCriteria())
                  .bind("u", actor.id())
                  .map((r, m) -> r.get("id", UUID.class))
                  .one()
                  .flatMap(
                      id ->
                          insertSource(db, id, src)
                              .then(
                                  audit.record(
                                      db,
                                      actor,
                                      AuditEntry.of("requirement_created", "requirement", id)
                                          .tender(tenderId)
                                          .created(Map.of("text", in.getText().trim()))
                                          .note(in.getNote())
                                          .build()))
                              .then(get(db, id)));
            });
  }

  private Mono<Void> insertSource(DatabaseClient db, UUID requirementId, RequirementSource s) {
    if (s == null || s.getDocumentVersionId() == null) {
      return Mono.empty();
    }
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
                "INSERT INTO requirement_sources (requirement_id, document_version_id, passage_id,"
                    + " page_no, quote) VALUES (:r, :v, :p, :n, :q)")
            .bind("r", requirementId)
            .bind("v", s.getDocumentVersionId());
    spec =
        s.getPassageId() == null
            ? spec.bindNull("p", UUID.class)
            : spec.bind("p", s.getPassageId());
    spec =
        s.getPageNo() == null ? spec.bindNull("n", Integer.class) : spec.bind("n", s.getPageNo());
    spec = s.getQuote() == null ? spec.bindNull("q", String.class) : spec.bind("q", s.getQuote());
    return spec.fetch().rowsUpdated().then();
  }

  /** The 412 of PI 2.1: the current row, who changed it and when, and which fields differ. */
  @SuppressWarnings("unchecked")
  private ApiException conflict(Requirement current, Map<String, Object> mine) {
    Map<String, Object> theirs = fields(current);
    List<String> changed = new ArrayList<>();
    for (var e : mine.entrySet()) {
      if (!Objects.equals(String.valueOf(e.getValue()), String.valueOf(theirs.get(e.getKey())))) {
        changed.add(e.getKey());
      }
    }
    ApiException ex = ETags.stale().with("current", json.convertValue(current, Map.class));
    if (current.getLastChangedBy() != null) {
      ex.with("changed_by", json.convertValue(current.getLastChangedBy(), Map.class));
    }
    if (current.getLastChangedAt() != null) {
      ex.with("changed_at", current.getLastChangedAt().toString());
    }
    return ex.with("changed_fields", changed);
  }

  public Mono<Requirement> patch(
      DatabaseClient db, CurrentUser actor, UUID id, int ifMatch, RequirementPatch in) {
    return writable(db, id)
        .flatMap(
            current -> {
              if (current.getDeleted()) {
                return Mono.error(
                    ApiException.conflict("requirement_deleted", "Restore the requirement first"));
              }
              Map<String, Object> mine = new LinkedHashMap<>();
              mine.put("text", in.getText() != null ? in.getText().trim() : current.getText());
              mine.put(
                  "category_id",
                  in.getCategoryId() != null
                      ? in.getCategoryId().toString()
                      : (current.getCategory() == null
                          ? null
                          : current.getCategory().getId().toString()));
              mine.put(
                  "importance",
                  in.getImportance() != null
                      ? in.getImportance().getValue()
                      : current.getImportance().getValue());
              mine.put(
                  "acceptance_criteria",
                  in.getAcceptanceCriteria() != null
                      ? in.getAcceptanceCriteria()
                      : current.getAcceptanceCriteria());
              if (current.getRowVersion() != ifMatch) {
                return Mono.error(conflict(current, mine));
              }
              Map<String, Object> before = fields(current);
              if (before.equals(mine)
                  && current.getReviewState() != RequirementReviewState.CONFIRMED) {
                // Nothing changed: a no-op write is not an edit (and needs no note).
                return Mono.just(current);
              }
              if (String.valueOf(mine.get("text")).isBlank()) {
                return Mono.error(
                    ApiException.invalid("text", "required", "Enter the requirement"));
              }
              String nextState =
                  current.getReviewState() == RequirementReviewState.CONFIRMED
                      ? "reviewed"
                      : "reviewed";
              boolean keepFirst =
                  current.getOrigin() == RequirementOrigin.AI && current.getAiOriginal() == null;
              String firstJson = keepFirst ? json.writeValueAsString(before) : null;
              DatabaseClient.GenericExecuteSpec spec =
                  db.sql(
                          "UPDATE requirements SET text = :text, category_id = :cat, importance = :imp,"
                              + " acceptance_criteria = :ac, review_state = :st, last_changed_by = :u,"
                              + " last_changed_at = now(),"
                              + " ai_original = coalesce(ai_original, CAST(:first AS jsonb))"
                              + " WHERE id = :id AND row_version = :v")
                      .bind("text", mine.get("text"))
                      .bind("imp", mine.get("importance"))
                      .bind("ac", mine.get("acceptance_criteria"))
                      .bind("st", nextState)
                      .bind("u", actor.id())
                      .bind("id", id)
                      .bind("v", ifMatch);
              spec =
                  mine.get("category_id") == null
                      ? spec.bindNull("cat", UUID.class)
                      : spec.bind("cat", UUID.fromString((String) mine.get("category_id")));
              spec =
                  firstJson == null
                      ? spec.bindNull("first", String.class)
                      : spec.bind("first", firstJson);
              return spec.fetch()
                  .rowsUpdated()
                  .flatMap(
                      n ->
                          n == 0
                              ? get(db, id)
                                  .flatMap(now -> Mono.<Requirement>error(conflict(now, mine)))
                              : audit
                                  .record(
                                      db,
                                      actor,
                                      AuditEntry.of("requirement_edited", "requirement", id)
                                          .tender(current.getTenderId())
                                          .changed(before, mine)
                                          .note(in.getNote())
                                          .build())
                                  .then(get(db, id)));
            });
  }

  public Mono<Requirement> delete(
      DatabaseClient db, CurrentUser actor, UUID id, DeleteRequirement in) {
    if (in.getReason() == app.tendereval.backend.api.model.DeleteReason.DUPLICATE
        && in.getDuplicateOfId() == null) {
      return Mono.error(
          ApiException.invalid(
              "duplicate_of_id", "required", "Choose the requirement that is kept"));
    }
    return writable(db, id)
        .flatMap(
            current -> {
              if (current.getDeleted()) {
                return Mono.error(
                    ApiException.conflict(
                        "requirement_deleted", "The requirement is already deleted"));
              }
              if (id.equals(in.getDuplicateOfId())) {
                return Mono.error(
                    ApiException.invalid(
                        "duplicate_of_id", "same", "A requirement cannot duplicate itself"));
              }
              DatabaseClient.GenericExecuteSpec spec =
                  db.sql(
                          "UPDATE requirements SET deleted_at = now(), deleted_by = :u,"
                              + " delete_reason = :why, duplicate_of_requirement_id = :dup,"
                              + " last_changed_by = :u, last_changed_at = now() WHERE id = :id")
                      .bind("u", actor.id())
                      .bind("why", in.getReason().getValue())
                      .bind("id", id);
              spec =
                  in.getDuplicateOfId() == null
                      ? spec.bindNull("dup", UUID.class)
                      : spec.bind("dup", in.getDuplicateOfId());
              return spec.fetch()
                  .rowsUpdated()
                  .then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("requirement_deleted", "requirement", id)
                              .tender(current.getTenderId())
                              .changed(
                                  Map.of("deleted", false),
                                  Map.of("deleted", true, "reason", in.getReason().getValue()))
                              .note(in.getNote())
                              .build()))
                  .then(get(db, id));
            });
  }

  public Mono<Requirement> restore(DatabaseClient db, CurrentUser actor, UUID id, String note) {
    return get(db, id)
        .flatMap(
            current -> {
              if (!current.getDeleted()) {
                return Mono.error(
                    ApiException.conflict("not_deleted", "The requirement is not deleted"));
              }
              Boolean allowed =
                  current.getActions().get("restore") != null
                      && current.getActions().get("restore").getAllowed();
              if (!Boolean.TRUE.equals(allowed)) {
                return Mono.error(refusalFor(current));
              }
              return db.sql(
                      "UPDATE requirements SET deleted_at = NULL, deleted_by = NULL,"
                          + " delete_reason = NULL, duplicate_of_requirement_id = NULL,"
                          + " last_changed_by = :u, last_changed_at = now() WHERE id = :id")
                  .bind("u", actor.id())
                  .bind("id", id)
                  .fetch()
                  .rowsUpdated()
                  .then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("requirement_restored", "requirement", id)
                              .tender(current.getTenderId())
                              .changed(Map.of("deleted", true), Map.of("deleted", false))
                              .note(note)
                              .build()))
                  .then(get(db, id));
            });
  }

  private ApiException refusalFor(Requirement r) {
    String reason = r.getActions().get("restore").getReason();
    return reason != null && reason.startsWith("The baseline")
        ? ApiException.conflict("baseline_locked", "The baseline is confirmed. Reopen it to edit")
        : ApiException.forbidden("forbidden", "You cannot edit this list");
  }

  public Mono<Requirement> markReviewed(DatabaseClient db, CurrentUser actor, UUID id) {
    return writable(db, id)
        .flatMap(
            current -> {
              if (current.getReviewState() != RequirementReviewState.UNREVIEWED) {
                return Mono.just(current);
              }
              return db.sql(
                      "UPDATE requirements SET review_state = 'reviewed', last_changed_by = :u,"
                          + " last_changed_at = now() WHERE id = :id")
                  .bind("u", actor.id())
                  .bind("id", id)
                  .fetch()
                  .rowsUpdated()
                  .then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("requirement_reviewed", "requirement", id)
                              .tender(current.getTenderId())
                              .changed(
                                  Map.of("review_state", "unreviewed"),
                                  Map.of("review_state", "reviewed"))
                              .build()))
                  .then(get(db, id));
            });
  }

  public Mono<Requirement> resolveFlag(
      DatabaseClient db, CurrentUser actor, UUID requirementId, UUID flagId, String note) {
    return writable(db, requirementId)
        .flatMap(
            current ->
                db.sql(
                        "UPDATE requirement_flags SET resolved_at = now(), resolved_by = :u"
                            + " WHERE id = :f AND requirement_id = :r AND resolved_at IS NULL")
                    .bind("u", actor.id())
                    .bind("f", flagId)
                    .bind("r", requirementId)
                    .fetch()
                    .rowsUpdated()
                    .flatMap(
                        n ->
                            n == 0
                                ? Mono.<Void>error(
                                    ApiException.notFound("open flag of this requirement"))
                                : audit.record(
                                    db,
                                    actor,
                                    AuditEntry.of(
                                            "requirement_flag_resolved",
                                            "requirement",
                                            requirementId)
                                        .tender(current.getTenderId())
                                        .created(Map.of("flag_id", flagId.toString()))
                                        .note(note)
                                        .build()))
                    .then(get(db, requirementId)));
  }

  // ---- bulk, merge, split
  // ---------------------------------------------------------------------------

  public Mono<BulkResult> bulk(
      DatabaseClient db, CurrentUser actor, UUID tenderId, BulkRequirements in) {
    UUID batch = UUID.randomUUID();
    if (in.getAction() == BulkRequirements.ActionEnum.CHANGE_CATEGORY
        && in.getCategoryId() == null) {
      return Mono.error(ApiException.invalid("category_id", "required", "Choose a category"));
    }
    if (in.getAction() == BulkRequirements.ActionEnum.CHANGE_IMPORTANCE
        && in.getImportance() == null) {
      return Mono.error(ApiException.invalid("importance", "required", "Choose an importance"));
    }
    List<BulkResultSkippedInner> skipped = new ArrayList<>();
    Set<UUID> handled = new java.util.HashSet<>();
    int[] updated = {0};
    Mono<Void> all = Mono.empty();
    for (UUID id : new java.util.LinkedHashSet<>(in.getIds())) {
      all =
          all.then(
              get(db, id)
                  .onErrorResume(e -> Mono.empty())
                  .flatMap(
                      r -> {
                        handled.add(id);
                        if (!r.getTenderId().equals(tenderId)) {
                          skipped.add(new BulkResultSkippedInner().id(id).code("other_tender"));
                          return Mono.empty();
                        }
                        if (r.getDeleted()) {
                          skipped.add(new BulkResultSkippedInner().id(id).code("deleted"));
                          return Mono.empty();
                        }
                        if (!r.getActions().get("edit").getAllowed()) {
                          skipped.add(new BulkResultSkippedInner().id(id).code("locked"));
                          return Mono.empty();
                        }
                        return applyBulk(db, actor, r, in, batch)
                            .doOnNext(
                                changed -> {
                                  if (changed) {
                                    updated[0]++;
                                  } else {
                                    skipped.add(
                                        new BulkResultSkippedInner().id(id).code("no_change"));
                                  }
                                })
                            .then();
                      })
                  .switchIfEmpty(
                      Mono.defer(
                          () -> {
                            if (!handled.contains(id)) {
                              skipped.add(new BulkResultSkippedInner().id(id).code("not_found"));
                            }
                            return Mono.empty();
                          })));
    }
    return all.then(Mono.fromSupplier(() -> new BulkResult().updated(updated[0]).skipped(skipped)));
  }

  private Mono<Boolean> applyBulk(
      DatabaseClient db, CurrentUser actor, Requirement r, BulkRequirements in, UUID batch) {
    Map<String, Object> before = new LinkedHashMap<>();
    Map<String, Object> after = new LinkedHashMap<>();
    String set;
    DatabaseClient.GenericExecuteSpec spec;
    String action;
    switch (in.getAction()) {
      case MARK_REVIEWED -> {
        if (r.getReviewState() != RequirementReviewState.UNREVIEWED) {
          return Mono.just(false);
        }
        before.put("review_state", "unreviewed");
        after.put("review_state", "reviewed");
        set = "review_state = 'reviewed'";
        action = "requirement_reviewed";
        spec = null;
      }
      case CHANGE_CATEGORY -> {
        if (r.getCategory() != null && r.getCategory().getId().equals(in.getCategoryId())) {
          return Mono.just(false);
        }
        before.put(
            "category_id", r.getCategory() == null ? null : r.getCategory().getId().toString());
        after.put("category_id", in.getCategoryId().toString());
        set = "category_id = :v, review_state = 'reviewed'";
        action = "requirement_edited";
        spec = null;
      }
      default -> {
        if (r.getImportance() == in.getImportance()) {
          return Mono.just(false);
        }
        before.put("importance", r.getImportance().getValue());
        after.put("importance", in.getImportance().getValue());
        set = "importance = :v, review_state = 'reviewed'";
        action = "requirement_edited";
        spec = null;
      }
    }
    DatabaseClient.GenericExecuteSpec upd =
        db.sql(
                "UPDATE requirements SET "
                    + set
                    + ", last_changed_by = :u, last_changed_at = now() WHERE id = :id")
            .bind("u", actor.id())
            .bind("id", r.getId());
    if (set.contains(":v")) {
      upd =
          in.getAction() == BulkRequirements.ActionEnum.CHANGE_CATEGORY
              ? upd.bind("v", in.getCategoryId())
              : upd.bind("v", in.getImportance().getValue());
    }
    return upd.fetch()
        .rowsUpdated()
        .then(
            audit.record(
                db,
                actor,
                AuditEntry.of(action, "requirement", r.getId())
                    .tender(r.getTenderId())
                    .changed(before, after)
                    .note(in.getNote())
                    .batch(batch)
                    .build()))
        .thenReturn(true);
  }

  private Mono<UUID> insertDerived(
      DatabaseClient db,
      CurrentUser actor,
      UUID tenderId,
      RequirementPart part,
      String origin,
      UUID splitFrom) {
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
                "INSERT INTO requirements (tender_id, text, category_id, importance,"
                    + " acceptance_criteria, origin, review_state, split_from_requirement_id,"
                    + " last_changed_by, last_changed_at) VALUES (:t, :text, :cat, :imp, :ac,"
                    + " 'manual', 'reviewed', :split, :u, now()) RETURNING id")
            .bind("t", tenderId)
            .bind("text", part.getText().trim())
            .bind("imp", part.getImportance().getValue())
            .bind("ac", part.getAcceptanceCriteria() == null ? "" : part.getAcceptanceCriteria())
            .bind("u", actor.id());
    spec =
        part.getCategoryId() == null
            ? spec.bindNull("cat", UUID.class)
            : spec.bind("cat", part.getCategoryId());
    spec = splitFrom == null ? spec.bindNull("split", UUID.class) : spec.bind("split", splitFrom);
    return spec.map((r, m) -> r.get("id", UUID.class)).one();
  }

  private Mono<Void> copySources(DatabaseClient db, UUID from, UUID to) {
    return db.sql(
            "INSERT INTO requirement_sources (requirement_id, document_version_id, passage_id,"
                + " page_no, quote) SELECT :to, document_version_id, passage_id, page_no, quote"
                + " FROM requirement_sources WHERE requirement_id = :from"
                + " ON CONFLICT DO NOTHING")
        .bind("to", to)
        .bind("from", from)
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<Void> retire(
      DatabaseClient db, CurrentUser actor, UUID id, UUID mergedInto, String columnNote) {
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
                "UPDATE requirements SET deleted_at = now(), deleted_by = :u, delete_reason = 'other',"
                    + " merged_into_requirement_id = :into, last_changed_by = :u,"
                    + " last_changed_at = now() WHERE id = :id")
            .bind("u", actor.id())
            .bind("id", id);
    spec = mergedInto == null ? spec.bindNull("into", UUID.class) : spec.bind("into", mergedInto);
    return spec.fetch().rowsUpdated().then();
  }

  public Mono<Requirement> merge(
      DatabaseClient db, CurrentUser actor, UUID tenderId, MergeRequirements in) {
    Set<UUID> ids = new java.util.LinkedHashSet<>(in.getRequirementIds());
    if (ids.size() < 2) {
      return Mono.error(
          ApiException.invalid("requirement_ids", "min", "Choose at least two requirements"));
    }
    return Flux.fromIterable(ids)
        .concatMap(id -> writable(db, id))
        .collectList()
        .flatMap(
            rows -> {
              for (Requirement r : rows) {
                if (!r.getTenderId().equals(tenderId) || r.getDeleted()) {
                  return Mono.error(
                      ApiException.invalid(
                          "requirement_ids", "invalid", "Choose requirements of this tender"));
                }
              }
              return insertDerived(db, actor, tenderId, in.getResult(), "manual", null)
                  .flatMap(
                      newId -> {
                        Mono<Void> work = Mono.empty();
                        for (Requirement r : rows) {
                          work =
                              work.then(copySources(db, r.getId(), newId))
                                  .then(retire(db, actor, r.getId(), newId, null));
                        }
                        return work.then(
                                audit.record(
                                    db,
                                    actor,
                                    AuditEntry.of("requirement_merged", "requirement", newId)
                                        .tender(tenderId)
                                        .created(
                                            Map.of(
                                                "merged_from",
                                                rows.stream()
                                                    .map(r -> r.getId().toString())
                                                    .toList()))
                                        .note(in.getNote())
                                        .build()))
                            .then(get(db, newId));
                      });
            });
  }

  public Mono<List<Requirement>> split(
      DatabaseClient db, CurrentUser actor, UUID id, SplitRequirement in) {
    if (in.getParts() == null || in.getParts().size() < 2) {
      return Mono.error(ApiException.invalid("parts", "min", "A split makes at least two parts"));
    }
    return writable(db, id)
        .flatMap(
            current -> {
              if (current.getDeleted()) {
                return Mono.error(
                    ApiException.conflict("requirement_deleted", "Restore the requirement first"));
              }
              return Flux.fromIterable(in.getParts())
                  .concatMap(
                      p ->
                          insertDerived(db, actor, current.getTenderId(), p, "manual", id)
                              .flatMap(newId -> copySources(db, id, newId).thenReturn(newId)))
                  .collectList()
                  .flatMap(
                      ids ->
                          retire(db, actor, id, null, null)
                              .then(
                                  audit.record(
                                      db,
                                      actor,
                                      AuditEntry.of("requirement_split", "requirement", id)
                                          .tender(current.getTenderId())
                                          .created(
                                              Map.of(
                                                  "parts",
                                                  ids.stream().map(UUID::toString).toList()))
                                          .note(in.getNote())
                                          .build()))
                              .thenMany(Flux.fromIterable(ids).concatMap(n -> get(db, n)))
                              .collectList());
            });
  }

  // ---- categories
  // -----------------------------------------------------------------------------------

  public Mono<List<Category>> categories(DatabaseClient db, boolean includeInactive) {
    return db.sql(
            "SELECT id, name, sort_order, is_active FROM requirement_categories"
                + (includeInactive ? "" : " WHERE is_active")
                + " ORDER BY sort_order, name")
        .map(
            (r, m) ->
                new Category()
                    .id(r.get("id", UUID.class))
                    .name(r.get("name", String.class))
                    .sortOrder(r.get("sort_order", Integer.class))
                    .isActive(r.get("is_active", Boolean.class)))
        .all()
        .collectList();
  }

  public Mono<Category> createCategory(DatabaseClient db, CurrentUser actor, CategoryInput in) {
    if (in.getName() == null || in.getName().isBlank()) {
      return Mono.error(ApiException.invalid("name", "required", "Enter the category name"));
    }
    return db.sql(
            "INSERT INTO requirement_categories (name, sort_order, is_active)"
                + " VALUES (:n, :o, :a) RETURNING id, name, sort_order, is_active")
        .bind("n", in.getName().trim())
        .bind("o", in.getSortOrder() == null ? 0 : in.getSortOrder())
        .bind("a", in.getIsActive() == null || in.getIsActive())
        .map(
            (r, m) ->
                new Category()
                    .id(r.get("id", UUID.class))
                    .name(r.get("name", String.class))
                    .sortOrder(r.get("sort_order", Integer.class))
                    .isActive(r.get("is_active", Boolean.class)))
        .one()
        .flatMap(
            c ->
                audit
                    .record(
                        db,
                        actor,
                        AuditEntry.of("category_created", "requirement_category", c.getId())
                            .created(Map.of("name", c.getName()))
                            .build())
                    .thenReturn(c));
  }

  public Mono<Category> updateCategory(
      DatabaseClient db, CurrentUser actor, UUID id, CategoryInput in) {
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
                "UPDATE requirement_categories SET name = coalesce(:n, name),"
                    + " sort_order = coalesce(:o, sort_order), is_active = coalesce(:a, is_active)"
                    + " WHERE id = :id RETURNING id, name, sort_order, is_active")
            .bind("id", id);
    spec =
        in.getName() == null
            ? spec.bindNull("n", String.class)
            : spec.bind("n", in.getName().trim());
    spec =
        in.getSortOrder() == null
            ? spec.bindNull("o", Integer.class)
            : spec.bind("o", in.getSortOrder());
    spec =
        in.getIsActive() == null
            ? spec.bindNull("a", Boolean.class)
            : spec.bind("a", in.getIsActive());
    return spec.map(
            (r, m) ->
                new Category()
                    .id(r.get("id", UUID.class))
                    .name(r.get("name", String.class))
                    .sortOrder(r.get("sort_order", Integer.class))
                    .isActive(r.get("is_active", Boolean.class)))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("category")))
        .flatMap(
            c ->
                audit
                    .record(
                        db,
                        actor,
                        AuditEntry.of("category_updated", "requirement_category", c.getId())
                            .created(Map.of("name", c.getName(), "is_active", c.getIsActive()))
                            .build())
                    .thenReturn(c));
  }
}
