package app.tendereval.backend.tenders;

import app.tendereval.backend.api.model.AddMember;
import app.tendereval.backend.api.model.Member;
import app.tendereval.backend.api.model.MemberPage;
import app.tendereval.backend.api.model.NextStep;
import app.tendereval.backend.api.model.ProgressBoard;
import app.tendereval.backend.api.model.ProgressBoardMembersInner;
import app.tendereval.backend.api.model.Readiness;
import app.tendereval.backend.api.model.ReadinessItemsInner;
import app.tendereval.backend.api.model.RemovalHandling;
import app.tendereval.backend.api.model.RemoveMember;
import app.tendereval.backend.api.model.RequirementsSummary;
import app.tendereval.backend.api.model.SetChair;
import app.tendereval.backend.api.model.Stage;
import app.tendereval.backend.api.model.StageDate;
import app.tendereval.backend.api.model.StageDates;
import app.tendereval.backend.api.model.Tender;
import app.tendereval.backend.api.model.TenderCreate;
import app.tendereval.backend.api.model.TenderHome;
import app.tendereval.backend.api.model.TenderStatus;
import app.tendereval.backend.api.model.TenderSummary;
import app.tendereval.backend.api.model.TenderSummaryPage;
import app.tendereval.backend.api.model.TenderUpdate;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.common.Actions;
import app.tendereval.backend.common.Cursor;
import app.tendereval.backend.common.ETags;
import app.tendereval.backend.common.Keyset;
import app.tendereval.backend.common.Sql;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.security.Role;
import app.tendereval.backend.web.ApiException;
import io.r2dbc.spi.Row;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tenders and their committees (UX-TND-01 to UX-TND-06, UX-ROL-02). The administrator writes;
 * members and the Observer read what the policies let them see. Every change writes its audit event
 * in the same transaction.
 */
@Component
public class TenderService {

  private static final Map<String, Keyset.Field> SORTS =
      Map.of(
          "name", new Keyset.Field("lower(x.name)", Keyset.Type.TEXT),
          "last_activity_at", new Keyset.Field("x.last_activity_at", Keyset.Type.TIMESTAMP),
          "created_at", new Keyset.Field("x.created_at", Keyset.Type.TIMESTAMP));

  private static final String SUMMARY_SELECT =
      "SELECT x.* FROM ("
          + " SELECT t.id, t.name, t.reference_no, t.description, t.status, t.opened_at,"
          + " t.archived_at, t.created_at, t.row_version,"
          + " greatest(t.updated_at, coalesce(app_tender_last_activity(t.id), t.updated_at))"
          + "   AS last_activity_at,"
          + " (SELECT count(*) FROM tender_members m WHERE m.tender_id = t.id AND m.status = 'active')"
          + "   AS committee_size,"
          + " (SELECT count(*) FROM tender_vendors v WHERE v.tender_id = t.id AND v.removed_at IS NULL)"
          + "   AS vendor_count,"
          + " (SELECT m.user_id FROM tender_members m WHERE m.tender_id = t.id AND m.status = 'active'"
          + "   AND m.is_chair) AS chair_id,"
          + " (SELECT d.due_date FROM tender_stage_dates d WHERE d.tender_id = t.id"
          + "   AND d.stage = 'evaluation' AND d.removed_at IS NULL) AS due_date"
          + " FROM tenders t WHERE t.deleted_at IS NULL) x";

  private final AuditService audit;

  public TenderService(AuditService audit) {
    this.audit = audit;
  }

  // ---- rows -----------------------------------------------------------------------------------

  record TenderRow(
      UUID id,
      String name,
      String referenceNo,
      String description,
      TenderStatus status,
      OffsetDateTime openedAt,
      OffsetDateTime archivedAt,
      OffsetDateTime createdAt,
      OffsetDateTime lastActivityAt,
      int committeeSize,
      int vendorCount,
      UUID chairId,
      LocalDate dueDate,
      int rowVersion) {}

  private static TenderRow toRow(Row r) {
    return new TenderRow(
        r.get("id", UUID.class),
        r.get("name", String.class),
        r.get("reference_no", String.class),
        r.get("description", String.class),
        TenderStatus.fromValue(r.get("status", String.class)),
        r.get("opened_at", OffsetDateTime.class),
        r.get("archived_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("last_activity_at", OffsetDateTime.class),
        r.get("committee_size", Long.class).intValue(),
        r.get("vendor_count", Long.class).intValue(),
        r.get("chair_id", UUID.class),
        r.get("due_date", LocalDate.class),
        r.get("row_version", Integer.class));
  }

  /** The tender if the caller may read it; 403 with whom to ask when it exists but is closed. */
  Mono<TenderRow> load(DatabaseClient db, UUID id) {
    return db.sql(SUMMARY_SELECT + " WHERE x.id = :id")
        .bind("id", id)
        .map((r, m) -> toRow(r))
        .one()
        .switchIfEmpty(Mono.defer(() -> refusal(db, id)));
  }

  private Mono<TenderRow> refusal(DatabaseClient db, UUID id) {
    return db.sql("SELECT app_tender_exists(:id) AS e")
        .bind("id", id)
        .map((r, m) -> Boolean.TRUE.equals(r.get("e", Boolean.class)))
        .one()
        .flatMap(
            exists -> {
              if (!exists) {
                return Mono.error(ApiException.notFound("The tender"));
              }
              return db.sql("SELECT display_name, email FROM app_access_contacts()")
                  .map(
                      (r, m) -> {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("name", r.get("display_name", String.class));
                        c.put("email", r.get("email", String.class));
                        c.put("role", "admin");
                        return c;
                      })
                  .all()
                  .collectList()
                  .flatMap(
                      contacts ->
                          Mono.<TenderRow>error(
                              ApiException.forbidden(
                                      "no_access", "You do not have access to this tender")
                                  .with("access_contacts", contacts)));
            });
  }

  private Mono<Map<UUID, String>> names(DatabaseClient db, Set<UUID> ids) {
    if (ids.isEmpty()) {
      return Mono.just(Map.of());
    }
    return db.sql("SELECT id, display_name FROM users WHERE id IN (:ids)")
        .bind("ids", ids)
        .map((r, m) -> Map.entry(r.get("id", UUID.class), r.get("display_name", String.class)))
        .all()
        .collectMap(Map.Entry::getKey, Map.Entry::getValue);
  }

  private static UserRef ref(UUID id, Map<UUID, String> names) {
    return id == null ? null : new UserRef().id(id).displayName(names.getOrDefault(id, ""));
  }

  // ---- list -------------------------------------------------------------------------------------

  public Mono<TenderSummaryPage> list(
      DatabaseClient db,
      CurrentUser actor,
      Integer limitParam,
      String cursor,
      String q,
      String sort,
      TenderStatus status,
      Boolean archived,
      Boolean mine) {
    int limit = Cursor.limit(limitParam);
    Keyset.Order order = Keyset.parse(sort, SORTS, "-last_activity_at");
    Sql filter = new Sql();
    if (q != null && !q.isBlank()) {
      filter.and("(x.name ILIKE :q OR x.reference_no ILIKE :q)", "q", Sql.contains(q));
    }
    if (status != null) {
      filter.and("x.status = :status", "status", status.getValue());
    }
    if (!Boolean.TRUE.equals(archived)) {
      filter.and("x.archived_at IS NULL");
    }
    if (Boolean.TRUE.equals(mine)) {
      filter.and(
          "EXISTS (SELECT 1 FROM tender_members mm WHERE mm.tender_id = x.id"
              + " AND mm.user_id = :me AND mm.status = 'active')",
          "me",
          actor.id());
    }
    Sql page = new Sql();
    page.binds().putAll(filter.binds());
    Cursor c = Cursor.decodeOrNull(cursor);
    String keyset = "";
    if (c != null) {
      keyset = (filter.where().isEmpty() ? " WHERE " : " AND ") + order.after("x.id");
      page.bind("sort_value", c.sortValue()).bind("sort_id", c.id());
    }
    Mono<List<TenderRow>> rows =
        page.apply(
                db.sql(
                    SUMMARY_SELECT
                        + filter.where()
                        + keyset
                        + " ORDER BY "
                        + order.orderBy("x.id")
                        + " LIMIT "
                        + (limit + 1)))
            .map((r, m) -> toRow(r))
            .all()
            .collectList();
    Mono<Long> total =
        filter
            .apply(
                db.sql(
                    "SELECT count(*) AS n FROM ("
                        + SUMMARY_SELECT.replace(
                            "SELECT x.* FROM",
                            "SELECT x.id, x.name, x.reference_no, x.status, x.archived_at FROM")
                        + filter.where()
                        + ") z"))
            .map((r, m) -> r.get("n", Long.class))
            .one();
    return Mono.zip(rows, total)
        .flatMap(
            t -> {
              List<TenderRow> items = t.getT1();
              String next = null;
              if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                TenderRow last = items.get(limit - 1);
                String sv =
                    switch (order.name()) {
                      case "name" -> last.name().toLowerCase();
                      case "created_at" -> last.createdAt().toString();
                      default -> last.lastActivityAt().toString();
                    };
                next = new Cursor(sv, last.id()).encode();
              }
              List<TenderRow> finalItems = items;
              String nextCursor = next;
              Set<UUID> chairs = new LinkedHashSet<>();
              finalItems.forEach(i -> chairs.add(i.chairId()));
              chairs.remove(null);
              return names(db, chairs)
                  .map(
                      names ->
                          new TenderSummaryPage()
                              .items(finalItems.stream().map(i -> summary(i, names)).toList())
                              .nextCursor(nextCursor)
                              .total(t.getT2().intValue()));
            });
  }

  private static TenderSummary summary(TenderRow r, Map<UUID, String> names) {
    return new TenderSummary()
        .id(r.id())
        .name(r.name())
        .referenceNo(r.referenceNo())
        .status(r.status())
        .archived(r.archivedAt() != null)
        .committeeSize(r.committeeSize())
        .vendorCount(r.vendorCount())
        .chair(ref(r.chairId(), names))
        .lastActivityAt(r.lastActivityAt())
        .dueDate(r.dueDate());
  }

  // ---- one tender
  // ---------------------------------------------------------------------------------

  public Mono<Tender> get(DatabaseClient db, CurrentUser actor, UUID id) {
    return load(db, id).flatMap(row -> full(db, actor, row));
  }

  private Mono<Tender> full(DatabaseClient db, CurrentUser actor, TenderRow row) {
    boolean admin = actor.role() == Role.ADMIN;
    Mono<List<Member>> members = members(db, row.id(), admin);
    Mono<List<StageDate>> stages =
        db.sql(
                "SELECT stage, due_date, reminder_lead_days FROM tender_stage_dates"
                    + " WHERE tender_id = :t AND removed_at IS NULL ORDER BY CASE stage WHEN 'requirements' THEN 1"
                    + " WHEN 'evaluation' THEN 2 ELSE 3 END")
            .bind("t", row.id())
            .map(
                (r, m) ->
                    new StageDate()
                        .stage(Stage.fromValue(r.get("stage", String.class)))
                        .dueDate(r.get("due_date", LocalDate.class))
                        .reminderLeadDays(r.get("reminder_lead_days", Integer.class)))
            .all()
            .collectList();
    Mono<Readiness> readiness =
        admin ? readiness(db, row.id()) : Mono.just(new Readiness().ready(true));
    return Mono.zip(members, stages, readiness)
        .map(
            t -> {
              List<Member> ms = t.getT1();
              UserRef chair =
                  ms.stream()
                      .filter(Member::getIsChair)
                      .map(Member::getUser)
                      .findFirst()
                      .orElse(null);
              boolean archived = row.archivedAt() != null;
              boolean draft = row.status() == TenderStatus.DRAFT;
              Actions actions = Actions.builder();
              if (admin) {
                actions
                    .when("edit", !archived, "Archived tenders are read-only")
                    .when(
                        "open",
                        draft && !archived && Boolean.TRUE.equals(t.getT3().getReady()),
                        !draft
                            ? "The tender is already open"
                            : "Upload the RFP and choose a committee first")
                    .when(
                        "archive",
                        !archived && row.status() == TenderStatus.FINALIZED,
                        archived ? "Already archived" : "Only a finalized tender can be archived")
                    .allow("duplicate")
                    .when("manage_committee", !archived, "Archived tenders are read-only");
              }
              return new Tender()
                  .id(row.id())
                  .name(row.name())
                  .referenceNo(row.referenceNo())
                  .description(row.description())
                  .status(row.status())
                  .archived(archived)
                  .committeeSize(row.committeeSize())
                  .vendorCount(row.vendorCount())
                  .chair(chair)
                  .lastActivityAt(row.lastActivityAt())
                  .dueDate(row.dueDate())
                  .openedAt(row.openedAt())
                  .stageDates(t.getT2())
                  .members(ms)
                  .nextStep(nextStep(row, admin))
                  .actions(actions.build())
                  .rowVersion(row.rowVersion());
            });
  }

  private static NextStep nextStep(TenderRow row, boolean admin) {
    return switch (row.status()) {
      case DRAFT ->
          admin
              ? new NextStep()
                  .text("Upload the RFP and open the tender to the committee")
                  .target("documents")
              : new NextStep().text("The tender is being prepared");
      case REQUIREMENTS ->
          new NextStep().text("Extract and review the requirements").target("requirements");
      case EVALUATION -> new NextStep().text("Evaluate the proposals").target("evaluation");
      case CONSOLIDATION -> new NextStep().text("Settle the disagreements").target("report");
      case FINALIZED -> new NextStep().text("The report is final").target("report");
    };
  }

  private Mono<List<Member>> members(DatabaseClient db, UUID tenderId, boolean withEmail) {
    return db.sql(
            "SELECT m.user_id, m.is_chair, m.status, m.added_at, u.display_name, u.email"
                + " FROM tender_members m JOIN users u ON u.id = m.user_id"
                + " WHERE m.tender_id = :t AND m.status = 'active'"
                + " ORDER BY m.is_chair DESC, lower(u.display_name), m.id")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new Member()
                    .user(
                        new UserRef()
                            .id(r.get("user_id", UUID.class))
                            .displayName(r.get("display_name", String.class)))
                    .email(withEmail ? r.get("email", String.class) : null)
                    .isChair(Boolean.TRUE.equals(r.get("is_chair", Boolean.class)))
                    .status(r.get("status", String.class))
                    .addedAt(r.get("added_at", OffsetDateTime.class)))
        .all()
        .collectList();
  }

  public Mono<MemberPage> listMembers(DatabaseClient db, CurrentUser actor, UUID tenderId) {
    return load(db, tenderId)
        .then(members(db, tenderId, actor.role() == Role.ADMIN))
        .map(items -> new MemberPage().items(items).total(items.size()));
  }

  // ---- create / duplicate
  // ----------------------------------------------------------------------------

  public Mono<Tender> create(DatabaseClient db, CurrentUser actor, TenderCreate body) {
    String name = clean(body.getName());
    List<ApiException.FieldError> errors = new ArrayList<>();
    if (name == null) {
      errors.add(new ApiException.FieldError("name", "required", "Enter a name"));
    }
    Set<UUID> memberIds =
        new LinkedHashSet<>(body.getMemberIds() == null ? List.of() : body.getMemberIds());
    if (memberIds.isEmpty()) {
      errors.add(
          new ApiException.FieldError(
              "member_ids", "required", "Choose at least one committee member"));
    }
    if (body.getChairId() == null || !memberIds.contains(body.getChairId())) {
      errors.add(
          new ApiException.FieldError(
              "chair_id", "not_a_member", "The Chair must be one of the members"));
    }
    if (!errors.isEmpty()) {
      return Mono.error(
          new ApiException(
              HttpStatus.UNPROCESSABLE_ENTITY,
              "validation_failed",
              "The request has invalid values",
              errors));
    }
    return requireFreeName(db, name, null)
        .then(requireCommittee(db, memberIds))
        .then(
            insertTender(
                db,
                actor,
                name,
                clean(body.getDescription()),
                clean(body.getReferenceNo()),
                memberIds,
                body.getChairId(),
                body.getEvaluationDueDate(),
                "tender_created",
                null));
  }

  private Mono<Tender> insertTender(
      DatabaseClient db,
      CurrentUser actor,
      String name,
      String description,
      String referenceNo,
      Set<UUID> memberIds,
      UUID chairId,
      LocalDate dueDate,
      String action,
      UUID sourceTender) {
    DatabaseClient.GenericExecuteSpec insert =
        db.sql(
                "INSERT INTO tenders (name, description, reference_no, created_by)"
                    + " VALUES (:n, :d, :r, :u) RETURNING id")
            .bind("n", name)
            .bind("u", actor.id());
    insert =
        description == null ? insert.bindNull("d", String.class) : insert.bind("d", description);
    insert =
        referenceNo == null ? insert.bindNull("r", String.class) : insert.bind("r", referenceNo);
    return insert
        .map((r, m) -> r.get("id", UUID.class))
        .one()
        .flatMap(
            tenderId ->
                Flux.fromIterable(memberIds)
                    .concatMap(
                        uid ->
                            db.sql(
                                    "INSERT INTO tender_members (tender_id, user_id, is_chair)"
                                        + " VALUES (:t, :u, :c)")
                                .bind("t", tenderId)
                                .bind("u", uid)
                                .bind("c", uid.equals(chairId))
                                .fetch()
                                .rowsUpdated())
                    .then(
                        dueDate == null
                            ? Mono.empty()
                            : db.sql(
                                    "INSERT INTO tender_stage_dates (tender_id, stage, due_date)"
                                        + " VALUES (:t, 'evaluation', :d)")
                                .bind("t", tenderId)
                                .bind("d", dueDate)
                                .fetch()
                                .rowsUpdated()
                                .then())
                    .then(
                        audit.record(
                            db,
                            actor,
                            AuditEntry.of(action, "tender", tenderId)
                                .tender(tenderId)
                                .created(
                                    createdValues(
                                        name,
                                        description,
                                        referenceNo,
                                        memberIds,
                                        chairId,
                                        dueDate,
                                        sourceTender))
                                .build()))
                    .then(get(db, actor, tenderId)));
  }

  private static Map<String, Object> createdValues(
      String name,
      String description,
      String referenceNo,
      Set<UUID> memberIds,
      UUID chairId,
      LocalDate dueDate,
      UUID source) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("description", description);
    m.put("reference_no", referenceNo);
    m.put("status", "draft");
    m.put("members", memberIds.stream().map(UUID::toString).toList());
    m.put("chair", chairId == null ? null : chairId.toString());
    m.put("evaluation_due_date", dueDate == null ? null : dueDate.toString());
    if (source != null) {
      m.put("duplicated_from", source.toString());
    }
    return m;
  }

  public Mono<Tender> duplicate(
      DatabaseClient db, CurrentUser actor, UUID sourceId, String newName) {
    String name = clean(newName);
    if (name == null) {
      return Mono.error(ApiException.invalid("name", "required", "Enter a name"));
    }
    return load(db, sourceId)
        .flatMap(
            source ->
                requireFreeName(db, name, null)
                    .then(
                        db.sql(
                                "SELECT user_id, is_chair FROM tender_members"
                                    + " WHERE tender_id = :t AND status = 'active'")
                            .bind("t", sourceId)
                            .map(
                                (r, m) ->
                                    Map.entry(
                                        r.get("user_id", UUID.class),
                                        Boolean.TRUE.equals(r.get("is_chair", Boolean.class))))
                            .all()
                            .collectList())
                    .flatMap(
                        seats -> {
                          Set<UUID> ids = new LinkedHashSet<>();
                          UUID chair = null;
                          for (var s : seats) {
                            ids.add(s.getKey());
                            if (s.getValue()) {
                              chair = s.getKey();
                            }
                          }
                          if (ids.isEmpty()) {
                            return Mono.<Tender>error(
                                ApiException.conflict(
                                    "no_committee", "The tender has no committee to copy"));
                          }
                          return insertTender(
                              db,
                              actor,
                              name,
                              source.description(),
                              source.referenceNo(),
                              ids,
                              chair,
                              null,
                              "tender_duplicated",
                              sourceId);
                        }));
  }

  // ---- update / archive
  // ----------------------------------------------------------------------------

  public Mono<Tender> update(
      DatabaseClient db, CurrentUser actor, UUID id, int ifMatch, TenderUpdate body) {
    return load(db, id)
        .flatMap(
            row -> {
              if (row.archivedAt() != null) {
                return Mono.error(
                    ApiException.conflict("tender_archived", "Archived tenders are read-only"));
              }
              if (row.rowVersion() != ifMatch) {
                return Mono.error(ETags.stale());
              }
              String name = body.getName() == null ? row.name() : clean(body.getName());
              if (name == null) {
                return Mono.error(ApiException.invalid("name", "required", "Enter a name"));
              }
              String description =
                  body.getDescription() == null ? row.description() : clean(body.getDescription());
              String reference =
                  body.getReferenceNo() == null ? row.referenceNo() : clean(body.getReferenceNo());
              Map<String, Object> before = new LinkedHashMap<>();
              before.put("name", row.name());
              before.put("description", row.description());
              before.put("reference_no", row.referenceNo());
              Map<String, Object> after = new LinkedHashMap<>();
              after.put("name", name);
              after.put("description", description);
              after.put("reference_no", reference);
              if (before.equals(after)) {
                return full(db, actor, row);
              }
              Mono<Void> free =
                  name.equalsIgnoreCase(row.name()) ? Mono.empty() : requireFreeName(db, name, id);
              DatabaseClient.GenericExecuteSpec update =
                  db.sql(
                          "UPDATE tenders SET name = :n, description = :d, reference_no = :r"
                              + " WHERE id = :id AND row_version = :v")
                      .bind("n", name)
                      .bind("id", id)
                      .bind("v", ifMatch);
              update =
                  description == null
                      ? update.bindNull("d", String.class)
                      : update.bind("d", description);
              update =
                  reference == null
                      ? update.bindNull("r", String.class)
                      : update.bind("r", reference);
              return free.then(update.fetch().rowsUpdated())
                  .flatMap(
                      n ->
                          n == 0
                              ? Mono.<Void>error(ETags.stale())
                              : audit.record(
                                  db,
                                  actor,
                                  AuditEntry.of("tender_updated", "tender", id)
                                      .tender(id)
                                      .changed(before, after)
                                      .build()))
                  .then(get(db, actor, id));
            });
  }

  public Mono<Tender> archive(DatabaseClient db, CurrentUser actor, UUID id) {
    return load(db, id)
        .flatMap(
            row -> {
              if (row.archivedAt() != null) {
                return full(db, actor, row);
              }
              if (row.status() != TenderStatus.FINALIZED) {
                return Mono.error(
                    ApiException.conflict(
                        "not_finished", "Only a finalized tender can be archived"));
              }
              return db.sql("UPDATE tenders SET archived_at = now() WHERE id = :id")
                  .bind("id", id)
                  .fetch()
                  .rowsUpdated()
                  .then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("tender_archived", "tender", id)
                              .tender(id)
                              .changed(Map.of("archived", false), Map.of("archived", true))
                              .build()))
                  .then(get(db, actor, id));
            });
  }

  // ---- opening
  // ------------------------------------------------------------------------------------

  public Mono<Readiness> readiness(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT"
                + " EXISTS (SELECT 1 FROM documents d JOIN document_versions v ON v.document_id = d.id"
                + "   WHERE d.tender_id = :t AND d.doc_type = 'rfp' AND d.deleted_at IS NULL"
                + "   AND v.deleted_at IS NULL AND v.status <> 'failed') AS rfp,"
                + " (SELECT count(*) FROM tender_members WHERE tender_id = :t AND status = 'active') AS members,"
                + " EXISTS (SELECT 1 FROM tender_members WHERE tender_id = :t AND status = 'active'"
                + "   AND is_chair) AS chair")
        .bind("t", tenderId)
        .map(
            (r, m) -> {
              boolean rfp = Boolean.TRUE.equals(r.get("rfp", Boolean.class));
              boolean members = r.get("members", Long.class) > 0;
              boolean chair = Boolean.TRUE.equals(r.get("chair", Boolean.class));
              List<ReadinessItemsInner> items =
                  List.of(
                      new ReadinessItemsInner().key("rfp_uploaded").label("RFP uploaded").done(rfp),
                      new ReadinessItemsInner()
                          .key("committee_chosen")
                          .label("Committee chosen")
                          .done(members),
                      new ReadinessItemsInner()
                          .key("chair_designated")
                          .label("Chair designated")
                          .done(chair));
              return new Readiness().ready(rfp && members && chair).items(items);
            })
        .one();
  }

  public Mono<Tender> open(DatabaseClient db, CurrentUser actor, UUID id) {
    return load(db, id)
        .flatMap(
            row -> {
              if (row.status() != TenderStatus.DRAFT) {
                return Mono.error(
                    ApiException.conflict(
                        "already_opened", "The tender is already open to the committee"));
              }
              return readiness(db, id)
                  .flatMap(
                      rd -> {
                        if (!Boolean.TRUE.equals(rd.getReady())) {
                          List<ApiException.FieldError> missing =
                              rd.getItems().stream()
                                  .filter(i -> !i.getDone())
                                  .map(
                                      i ->
                                          new ApiException.FieldError(
                                              i.getKey(), "missing", i.getLabel() + " is missing"))
                                  .toList();
                          return Mono.<Tender>error(
                              new ApiException(
                                  HttpStatus.UNPROCESSABLE_ENTITY,
                                  "not_ready",
                                  "The tender is not ready to open",
                                  missing));
                        }
                        return db.sql(
                                "UPDATE tenders SET status = 'requirements', opened_at = now()"
                                    + " WHERE id = :id")
                            .bind("id", id)
                            .fetch()
                            .rowsUpdated()
                            .then(
                                audit.record(
                                    db,
                                    actor,
                                    AuditEntry.of("tender_opened", "tender", id)
                                        .tender(id)
                                        .changed(
                                            Map.of("status", "draft"),
                                            Map.of("status", "requirements"))
                                        .build()))
                            .then(get(db, actor, id));
                      });
            });
  }

  // ---- committee
  // ------------------------------------------------------------------------------------

  public Mono<Member> addMember(
      DatabaseClient db, CurrentUser actor, UUID tenderId, AddMember body) {
    return load(db, tenderId)
        .flatMap(
            row -> {
              if (row.archivedAt() != null) {
                return Mono.error(
                    ApiException.conflict("tender_archived", "Archived tenders are read-only"));
              }
              if (body.getUserId() == null) {
                return Mono.error(ApiException.invalid("user_id", "required", "Choose a person"));
              }
              return requireCommittee(db, Set.of(body.getUserId()))
                  .then(
                      db.sql(
                              "SELECT 1 AS x FROM tender_members WHERE tender_id = :t AND user_id = :u"
                                  + " AND status = 'active'")
                          .bind("t", tenderId)
                          .bind("u", body.getUserId())
                          .map((r, m) -> 1)
                          .one())
                  .flatMap(
                      x ->
                          Mono.<Member>error(
                              ApiException.conflict(
                                  "already_member", "This person is already on the committee")))
                  .switchIfEmpty(
                      Mono.defer(
                          () ->
                              db.sql(
                                      "INSERT INTO tender_members (tender_id, user_id) VALUES (:t, :u)")
                                  .bind("t", tenderId)
                                  .bind("u", body.getUserId())
                                  .fetch()
                                  .rowsUpdated()
                                  .then(
                                      audit.record(
                                          db,
                                          actor,
                                          AuditEntry.of("committee_changed", "tender", tenderId)
                                              .tender(tenderId)
                                              .changed(
                                                  Map.of("member_added", ""),
                                                  Map.of(
                                                      "member_added", body.getUserId().toString()))
                                              .build()))
                                  .then(memberOf(db, tenderId, body.getUserId(), true))));
            });
  }

  private Mono<Member> memberOf(DatabaseClient db, UUID tenderId, UUID userId, boolean withEmail) {
    return members(db, tenderId, withEmail)
        .flatMap(
            list ->
                Mono.justOrEmpty(
                    list.stream().filter(m -> m.getUser().getId().equals(userId)).findFirst()));
  }

  public Mono<Member> setChair(DatabaseClient db, CurrentUser actor, UUID tenderId, SetChair body) {
    return load(db, tenderId)
        .flatMap(
            row -> {
              if (row.archivedAt() != null) {
                return Mono.error(
                    ApiException.conflict("tender_archived", "Archived tenders are read-only"));
              }
              UUID target = body.getUserId();
              if (target == null) {
                return Mono.error(ApiException.invalid("user_id", "required", "Choose a person"));
              }
              return db.sql(
                      "SELECT 1 AS x FROM tender_members WHERE tender_id = :t AND user_id = :u"
                          + " AND status = 'active'")
                  .bind("t", tenderId)
                  .bind("u", target)
                  .map((r, m) -> 1)
                  .one()
                  .switchIfEmpty(
                      Mono.error(
                          ApiException.invalid(
                              "user_id",
                              "not_a_member",
                              "The Chair must be a member of the committee")))
                  .then(
                      db.sql(
                              "UPDATE tender_members SET is_chair = false WHERE tender_id = :t"
                                  + " AND is_chair AND status = 'active' AND user_id <> :u")
                          .bind("t", tenderId)
                          .bind("u", target)
                          .fetch()
                          .rowsUpdated())
                  .then(
                      db.sql(
                              "UPDATE tender_members SET is_chair = true WHERE tender_id = :t"
                                  + " AND user_id = :u AND status = 'active' AND NOT is_chair")
                          .bind("t", tenderId)
                          .bind("u", target)
                          .fetch()
                          .rowsUpdated())
                  .then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("committee_changed", "tender", tenderId)
                              .tender(tenderId)
                              .changed(
                                  Map.of(
                                      "chair",
                                      row.chairId() == null ? "" : row.chairId().toString()),
                                  Map.of("chair", target.toString()))
                              .build()))
                  .then(memberOf(db, tenderId, target, true));
            });
  }

  public Mono<Void> removeMember(
      DatabaseClient db, CurrentUser actor, UUID tenderId, UUID userId, RemoveMember body) {
    return load(db, tenderId)
        .flatMap(
            row -> {
              if (row.archivedAt() != null) {
                return Mono.error(
                    ApiException.conflict("tender_archived", "Archived tenders are read-only"));
              }
              if (body.getHandling() == null) {
                return Mono.error(
                    ApiException.invalid(
                        "handling", "required", "Choose what happens to their work"));
              }
              if (body.getHandling() == RemovalHandling.REASSIGNED
                  && body.getReassignToUserId() == null) {
                return Mono.error(
                    ApiException.invalid(
                        "reassign_to_user_id", "required", "Choose who takes over the work"));
              }
              return db.sql(
                      "SELECT id, is_chair FROM tender_members WHERE tender_id = :t AND user_id = :u"
                          + " AND status = 'active'")
                  .bind("t", tenderId)
                  .bind("u", userId)
                  .map(
                      (r, m) ->
                          Map.entry(
                              r.get("id", UUID.class),
                              Boolean.TRUE.equals(r.get("is_chair", Boolean.class))))
                  .one()
                  .switchIfEmpty(Mono.error(ApiException.notFound("The committee member")))
                  .flatMap(
                      seat -> {
                        if (seat.getValue()) {
                          return Mono.<Void>error(
                              ApiException.conflict(
                                  "chair_must_be_replaced",
                                  "Designate another Chair before removing this person"));
                        }
                        if (row.status() != TenderStatus.DRAFT && row.committeeSize() <= 1) {
                          return Mono.<Void>error(
                              ApiException.conflict(
                                  "committee_empty", "An open tender needs at least one member"));
                        }
                        Mono<Void> target =
                            body.getHandling() == RemovalHandling.REASSIGNED
                                ? db.sql(
                                        "SELECT 1 AS x FROM tender_members WHERE tender_id = :t"
                                            + " AND user_id = :u AND status = 'active' AND user_id <> :me")
                                    .bind("t", tenderId)
                                    .bind("u", body.getReassignToUserId())
                                    .bind("me", userId)
                                    .map((r, m) -> 1)
                                    .one()
                                    .switchIfEmpty(
                                        Mono.error(
                                            ApiException.invalid(
                                                "reassign_to_user_id",
                                                "not_a_member",
                                                "Choose another member of this committee")))
                                    .then()
                                : Mono.empty();
                        DatabaseClient.GenericExecuteSpec update =
                            db.sql(
                                    "UPDATE tender_members SET status = 'removed', removed_at = now(),"
                                        + " removal_handling = :h, reassigned_to_user_id = :r WHERE id = :id")
                                .bind("h", body.getHandling().getValue())
                                .bind("id", seat.getKey());
                        update =
                            body.getHandling() == RemovalHandling.REASSIGNED
                                ? update.bind("r", body.getReassignToUserId())
                                : update.bindNull("r", UUID.class);
                        return target
                            .then(update.fetch().rowsUpdated())
                            .then(
                                audit.record(
                                    db,
                                    actor,
                                    AuditEntry.of("committee_changed", "tender", tenderId)
                                        .tender(tenderId)
                                        .changed(
                                            Map.of("member", userId.toString(), "status", "active"),
                                            Map.of(
                                                "member",
                                                userId.toString(),
                                                "status",
                                                "removed",
                                                "removal_handling",
                                                body.getHandling().getValue()))
                                        .note(body.getNote())
                                        .build()));
                      });
            });
  }

  // ---- stage dates
  // -------------------------------------------------------------------------------------

  public Mono<StageDates> setStageDates(
      DatabaseClient db, CurrentUser actor, UUID tenderId, StageDates body) {
    return load(db, tenderId)
        .flatMap(
            row -> {
              if (row.archivedAt() != null) {
                return Mono.error(
                    ApiException.conflict("tender_archived", "Archived tenders are read-only"));
              }
              List<StageDate> items = body.getItems() == null ? List.of() : body.getItems();
              Set<Stage> seen = new LinkedHashSet<>();
              for (StageDate d : items) {
                if (d.getStage() == null || d.getDueDate() == null) {
                  return Mono.error(
                      ApiException.invalid(
                          "items", "required", "Each stage needs a name and a date"));
                }
                if (!seen.add(d.getStage())) {
                  return Mono.error(
                      ApiException.invalid("items", "duplicate_stage", "A stage appears twice"));
                }
                if (d.getReminderLeadDays() != null && d.getReminderLeadDays() < 0) {
                  return Mono.error(
                      ApiException.invalid(
                          "reminder_lead_days", "min", "The reminder cannot be negative"));
                }
              }
              return stageMap(db, tenderId)
                  .flatMap(
                      old -> {
                        Map<String, Object> after = new LinkedHashMap<>();
                        items.forEach(
                            d -> after.put(d.getStage().getValue(), d.getDueDate().toString()));
                        Flux<Long> upserts =
                            Flux.fromIterable(items)
                                .concatMap(
                                    d -> {
                                      DatabaseClient.GenericExecuteSpec s =
                                          db.sql(
                                                  "INSERT INTO tender_stage_dates"
                                                      + " (tender_id, stage, due_date, reminder_lead_days)"
                                                      + " VALUES (:t, :s, :d, :l)"
                                                      + " ON CONFLICT (tender_id, stage) WHERE removed_at IS NULL DO UPDATE SET"
                                                      + " due_date = EXCLUDED.due_date,"
                                                      + " reminder_lead_days = EXCLUDED.reminder_lead_days")
                                              .bind("t", tenderId)
                                              .bind("s", d.getStage().getValue())
                                              .bind("d", d.getDueDate());
                                      s =
                                          d.getReminderLeadDays() == null
                                              ? s.bindNull("l", Integer.class)
                                              : s.bind("l", d.getReminderLeadDays());
                                      return s.fetch().rowsUpdated();
                                    });
                        // A stage left out is marked removed, never deleted (rule 11).
                        Mono<Long> prune =
                            db.sql(
                                    "UPDATE tender_stage_dates SET removed_at = now() WHERE tender_id = :t"
                                        + " AND removed_at IS NULL"
                                        + (seen.isEmpty() ? "" : " AND stage NOT IN (:keep)"))
                                .bind("t", tenderId)
                                .bind(
                                    "keep",
                                    seen.isEmpty()
                                        ? List.of("")
                                        : seen.stream().map(Stage::getValue).toList())
                                .fetch()
                                .rowsUpdated();
                        Mono<Void> record =
                            old.equals(
                                    after.entrySet().stream()
                                        .collect(
                                            java.util.stream.Collectors.toMap(
                                                Map.Entry::getKey,
                                                e -> String.valueOf(e.getValue()))))
                                ? Mono.empty()
                                : audit.record(
                                    db,
                                    actor,
                                    AuditEntry.of("tender_updated", "tender", tenderId)
                                        .tender(tenderId)
                                        .changed(
                                            Map.of("stage_dates", old),
                                            Map.of("stage_dates", after))
                                        .build());
                        return upserts.then(prune).then(record).then(stageDates(db, tenderId));
                      });
            });
  }

  private Mono<Map<String, String>> stageMap(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT stage, due_date FROM tender_stage_dates WHERE tender_id = :t AND removed_at IS NULL")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                Map.entry(
                    r.get("stage", String.class), r.get("due_date", LocalDate.class).toString()))
        .all()
        .collectMap(Map.Entry::getKey, Map.Entry::getValue);
  }

  private Mono<StageDates> stageDates(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT stage, due_date, reminder_lead_days FROM tender_stage_dates WHERE tender_id = :t"
                + " AND removed_at IS NULL ORDER BY CASE stage WHEN 'requirements' THEN 1 WHEN 'evaluation' THEN 2 ELSE 3 END")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new StageDate()
                    .stage(Stage.fromValue(r.get("stage", String.class)))
                    .dueDate(r.get("due_date", LocalDate.class))
                    .reminderLeadDays(r.get("reminder_lead_days", Integer.class)))
        .all()
        .collectList()
        .map(items -> new StageDates().items(items));
  }

  // ---- home and progress
  // --------------------------------------------------------------------------------

  /** Documents, requirements and vendors arrive with their phases; the shell is complete. */
  public Mono<TenderHome> home(DatabaseClient db, CurrentUser actor, UUID id) {
    return get(db, actor, id)
        .map(
            t ->
                new TenderHome()
                    .tender(t)
                    .documents(List.of())
                    .requirementsSummary(
                        new RequirementsSummary()
                            .total(0)
                            .mandatory(0)
                            .optional(0)
                            .reviewed(0)
                            .flagged(0)
                            .deleted(0))
                    .vendors(List.of()));
  }

  public Mono<ProgressBoard> progress(DatabaseClient db, CurrentUser actor, UUID id) {
    return load(db, id)
        .flatMap(
            row ->
                members(db, id, false)
                    .map(
                        ms ->
                            new ProgressBoard()
                                .tenderId(id)
                                .stage(row.status())
                                .vendors(List.of())
                                .members(
                                    ms.stream()
                                        .map(
                                            m ->
                                                new ProgressBoardMembersInner()
                                                    .user(m.getUser())
                                                    .requirementsReviewed(0)
                                                    .requirementsTotal(0)
                                                    .evaluationsSubmitted(0)
                                                    .evaluationsTotal(0))
                                        .toList())
                                .runningJobs(List.of())
                                .overdue(List.of())));
  }

  // ---- shared checks
  // ----------------------------------------------------------------------------------

  private Mono<Void> requireFreeName(DatabaseClient db, String name, UUID except) {
    return db.sql(
            "SELECT 1 AS x FROM tenders WHERE lower(btrim(name)) = lower(btrim(:n))"
                + " AND deleted_at IS NULL AND id <> :id")
        .bind("n", name)
        .bind("id", except == null ? UUID.randomUUID() : except)
        .map((r, m) -> 1)
        .one()
        .hasElement()
        .flatMap(
            taken -> {
              if (taken) {
                return Mono.<Void>error(
                    ApiException.conflict("name_taken", "A tender with this name already exists"));
              }
              return Mono.empty();
            });
  }

  /** Committee members are active (or invited) users with the member role. */
  private Mono<Void> requireCommittee(DatabaseClient db, Set<UUID> ids) {
    return db.sql("SELECT id, role, status FROM users WHERE id IN (:ids)")
        .bind("ids", ids)
        .map(
            (r, m) ->
                new String[] {
                  r.get("id", UUID.class).toString(),
                  r.get("role", String.class),
                  r.get("status", String.class)
                })
        .all()
        .collectList()
        .flatMap(
            found -> {
              Set<String> known = new LinkedHashSet<>();
              for (String[] f : found) {
                known.add(f[0]);
                if (!"member".equals(f[1])) {
                  return Mono.<Void>error(
                      ApiException.invalid(
                          "member_ids",
                          "wrong_role",
                          "Only users with the committee member role can sit on a committee"));
                }
                if ("deactivated".equals(f[2])) {
                  return Mono.<Void>error(
                      ApiException.invalid(
                          "member_ids", "deactivated", "A deactivated user cannot be added"));
                }
              }
              for (UUID id : ids) {
                if (!known.contains(id.toString())) {
                  return Mono.<Void>error(
                      ApiException.invalid(
                          "member_ids", "unknown_user", "A chosen user does not exist"));
                }
              }
              return Mono.<Void>empty();
            });
  }

  private static String clean(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
