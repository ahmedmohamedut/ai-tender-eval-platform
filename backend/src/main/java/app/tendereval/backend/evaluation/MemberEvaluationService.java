package app.tendereval.backend.evaluation;

import app.tendereval.backend.api.model.AiGrade;
import app.tendereval.backend.api.model.AiOutcome;
import app.tendereval.backend.api.model.AuditAction;
import app.tendereval.backend.api.model.AuditSource;
import app.tendereval.backend.api.model.BulkResult;
import app.tendereval.backend.api.model.BulkResultSkippedInner;
import app.tendereval.backend.api.model.BulkRows;
import app.tendereval.backend.api.model.CategoryScore;
import app.tendereval.backend.api.model.Citation;
import app.tendereval.backend.api.model.Clarification;
import app.tendereval.backend.api.model.CrossVendorEntry;
import app.tendereval.backend.api.model.CrossVendorView;
import app.tendereval.backend.api.model.EvaluationProgress;
import app.tendereval.backend.api.model.EvaluationProgressItemsInner;
import app.tendereval.backend.api.model.EvaluationRow;
import app.tendereval.backend.api.model.EvaluationRowPage;
import app.tendereval.backend.api.model.EvaluationSummary;
import app.tendereval.backend.api.model.EvaluationSummaryCounts;
import app.tendereval.backend.api.model.ExcludeMember;
import app.tendereval.backend.api.model.FeedbackInput;
import app.tendereval.backend.api.model.Grade;
import app.tendereval.backend.api.model.History;
import app.tendereval.backend.api.model.HistoryEvent;
import app.tendereval.backend.api.model.Importance;
import app.tendereval.backend.api.model.Job;
import app.tendereval.backend.api.model.JobStatus;
import app.tendereval.backend.api.model.MemberDecision;
import app.tendereval.backend.api.model.MemberEvaluation;
import app.tendereval.backend.api.model.MemberEvaluationStatus;
import app.tendereval.backend.api.model.NonComplianceReason;
import app.tendereval.backend.api.model.OutdatedReason;
import app.tendereval.backend.api.model.PeerRow;
import app.tendereval.backend.api.model.PeerRowPage;
import app.tendereval.backend.api.model.ReopenEvaluation;
import app.tendereval.backend.api.model.RequirementLite;
import app.tendereval.backend.api.model.RowFlag;
import app.tendereval.backend.api.model.RowReviewState;
import app.tendereval.backend.api.model.RowUpdate;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.common.Actions;
import app.tendereval.backend.common.Cursor;
import app.tendereval.backend.common.ETags;
import app.tendereval.backend.common.Settings;
import app.tendereval.backend.jobs.JobsService;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * A member's own evaluation of a vendor (UX-EVA-01..18). Rows are the baseline's requirements with
 * the current AI grade and the member's decision. The database decides who may read what
 * (independence, DM Independence and access); this service shapes the answer, checks the rules a
 * policy cannot phrase (a note for an override, the submit pre-checks) and writes the audit trail.
 */
@Service
public class MemberEvaluationService {

  static final Set<String> FEEDBACK_TAGS =
      Set.of("wrong_page", "missed_evidence", "wrong_grade", "unclear_rationale", "other");

  private final AuditService audit;
  private final JobsService jobs;
  private final app.tendereval.backend.notifications.NotificationService notifications;
  private final JsonMapper json = JsonMapper.builder().build();

  public MemberEvaluationService(
      AuditService audit,
      JobsService jobs,
      app.tendereval.backend.notifications.NotificationService notifications) {
    this.audit = audit;
    this.jobs = jobs;
    this.notifications = notifications;
  }

  /** A reopened evaluation is announced to its owner. */
  private Mono<Void> tellOwner(
      DatabaseClient db, UUID tenderId, UUID tenderVendorId, UUID memberEvaluationId) {
    return db.sql("SELECT user_id FROM member_evaluations WHERE id = :id")
        .bind("id", memberEvaluationId)
        .map((r, m) -> r.get("user_id", UUID.class))
        .one()
        .flatMap(
            owner ->
                notifications.notify(
                    db,
                    tenderId,
                    "evaluation_reopened",
                    "tender_vendor",
                    tenderVendorId,
                    Map.of(),
                    List.of(owner)));
  }

  /** The Chair hears when the last member of a vendor has submitted. */
  private Mono<Void> tellChairIfComplete(DatabaseClient db, UUID tenderId, UUID tenderVendorId) {
    return db.sql("SELECT app_all_submitted_or_excluded(:v) AS done")
        .bind("v", tenderVendorId)
        .map((r, m) -> Boolean.TRUE.equals(r.get("done", Boolean.class)))
        .one()
        .flatMap(
            done ->
                !done
                    ? Mono.<Void>empty()
                    : notifications
                        .chairs(db, tenderId)
                        .flatMap(
                            chairs ->
                                notifications.notify(
                                    db,
                                    tenderId,
                                    "all_members_submitted",
                                    "tender_vendor",
                                    tenderVendorId,
                                    Map.of(),
                                    chairs)));
  }

  // ---- context
  // ------------------------------------------------------------------------------------

  /** What the caller needs to know about a vendor before anything else. */
  record Ctx(
      UUID tenderId,
      UUID tenderVendorId,
      String vendorName,
      UUID versionId,
      int versionNo,
      UUID baselineId,
      int baselineNo,
      UUID evaluationId,
      String status,
      boolean excluded,
      boolean firstSubmitted,
      boolean member,
      boolean chair,
      boolean cleared,
      OffsetDateTime firstAt,
      OffsetDateTime submittedAt) {}

  private Mono<Ctx> ctx(DatabaseClient db, UUID tenderVendorId) {
    return db.sql(
            "SELECT tv.tender_id, tv.id AS tv, v.name AS vendor, p.id AS pv, p.version_no,"
                + " b.id AS baseline, b.version_no AS baseline_no, e.id AS me, e.status,"
                + " (e.excluded_at IS NOT NULL) AS excluded, (e.first_submitted_at IS NOT NULL) AS first,"
                + " app_is_member_of(tv.tender_id) AS member, app_is_chair_of(tv.tender_id) AS chair,"
                + " app_cleared_for_vendor(tv.id) AS cleared, e.first_submitted_at, e.submitted_at"
                + " FROM tender_vendors tv JOIN vendors v ON v.id = tv.vendor_id"
                + " LEFT JOIN LATERAL (SELECT id, version_no FROM proposal_versions x"
                + "   WHERE x.tender_vendor_id = tv.id AND x.status <> 'superseded'"
                + "   ORDER BY x.version_no DESC LIMIT 1) p ON true"
                + " LEFT JOIN baselines b ON b.tender_id = tv.tender_id AND b.status = 'confirmed'"
                + " LEFT JOIN member_evaluations e ON e.tender_vendor_id = tv.id"
                + "   AND e.user_id = app_user_id()"
                + " WHERE tv.id = :v AND tv.removed_at IS NULL")
        .bind("v", tenderVendorId)
        .map(
            (r, m) ->
                new Ctx(
                    r.get("tender_id", UUID.class),
                    r.get("tv", UUID.class),
                    r.get("vendor", String.class),
                    r.get("pv", UUID.class),
                    nz(r.get("version_no", Integer.class)),
                    r.get("baseline", UUID.class),
                    nz(r.get("baseline_no", Integer.class)),
                    r.get("me", UUID.class),
                    r.get("status", String.class),
                    Boolean.TRUE.equals(r.get("excluded", Boolean.class)),
                    Boolean.TRUE.equals(r.get("first", Boolean.class)),
                    Boolean.TRUE.equals(r.get("member", Boolean.class)),
                    Boolean.TRUE.equals(r.get("chair", Boolean.class)),
                    Boolean.TRUE.equals(r.get("cleared", Boolean.class)),
                    r.get("first_submitted_at", OffsetDateTime.class),
                    r.get("submitted_at", OffsetDateTime.class)))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("vendor of this tender")))
        .flatMap(
            c ->
                !c.member()
                    ? Mono.<Ctx>error(ApiException.notFound("vendor of this tender"))
                    : Mono.just(c));
  }

  private static int nz(Integer v) {
    return v == null ? 0 : v;
  }

  private Mono<Ctx> cleared(DatabaseClient db, UUID tenderVendorId) {
    return ctx(db, tenderVendorId)
        .flatMap(
            c ->
                !c.cleared()
                    ? Mono.<Ctx>error(
                        ApiException.forbidden(
                            "declaration_required",
                            "Declare that you have no conflict for this vendor first"))
                    : Mono.just(c));
  }

  // ---- rows
  // ---------------------------------------------------------------------------------------------

  private static final String ROW_SELECT =
      "SELECT br.requirement_id, br.display_no, br.text, br.category_name, br.importance,"
          + " br.acceptance_criteria, q.category_id,"
          + " g.id AS gid, g.outcome, g.grade AS ai_grade, g.non_compliance_reason, g.rationale_summary,"
          + " g.rationale, g.missing_elements::text AS missing, g.confidence, g.created_at AS ai_at,"
          + " g.is_current, a.model_name, a.model_version,"
          + " r.review_state, r.member_grade, r.note, r.needs_clarification, r.clarification_note,"
          + " r.reviewed_at, r.outdated_at, r.outdated_reason, r.row_version, r.id AS row_id"
          + " FROM baseline_requirements br"
          + " JOIN requirements q ON q.id = br.requirement_id"
          + " LEFT JOIN ai_grades g ON g.proposal_version_id = :pv AND g.requirement_id ="
          + "   br.requirement_id AND g.is_current"
          + " LEFT JOIN ai_runs a ON a.id = g.ai_run_id"
          + " LEFT JOIN member_evaluation_rows r ON r.member_evaluation_id = :me AND r.requirement_id ="
          + "   br.requirement_id"
          + " WHERE br.baseline_id = :b";

  private EvaluationRow map(io.r2dbc.spi.Readable r, double lowConfidence) {
    UUID gid = r.get("gid", UUID.class);
    AiGrade ai = null;
    String outcome = r.get("outcome", String.class);
    if (gid != null) {
      ai =
          new AiGrade()
              .id(gid)
              .outcome(AiOutcome.fromValue(outcome))
              .rationaleSummary(r.get("rationale_summary", String.class))
              .rationale(r.get("rationale", String.class))
              .missingElements(list(r.get("missing", String.class)))
              .confidence(
                  r.get("confidence", BigDecimal.class) == null
                      ? null
                      : r.get("confidence", BigDecimal.class))
              .citations(new ArrayList<>())
              .modelVersion(
                  String.valueOf(r.get("model_name", String.class))
                      + (r.get("model_version", String.class) == null
                          ? ""
                          : " " + r.get("model_version", String.class)))
              .runAt(r.get("ai_at", OffsetDateTime.class))
              .isCurrent(Boolean.TRUE.equals(r.get("is_current", Boolean.class)));
      if (r.get("ai_grade", String.class) != null) {
        ai.setGrade(Grade.fromValue(r.get("ai_grade", String.class)));
      }
      if (r.get("non_compliance_reason", String.class) != null) {
        ai.setNonComplianceReason(
            NonComplianceReason.fromValue(r.get("non_compliance_reason", String.class)));
      }
    }
    String state = r.get("review_state", String.class);
    boolean flagged = Boolean.TRUE.equals(r.get("needs_clarification", Boolean.class));
    MemberDecision d =
        new MemberDecision()
            .reviewState(RowReviewState.fromValue(state == null ? "not_reviewed" : state))
            .needsClarification(flagged)
            .note(r.get("note", String.class))
            .clarificationNote(r.get("clarification_note", String.class))
            .reviewedAt(r.get("reviewed_at", OffsetDateTime.class))
            .outdatedAt(r.get("outdated_at", OffsetDateTime.class));
    if (r.get("member_grade", String.class) != null) {
      d.setGrade(Grade.fromValue(r.get("member_grade", String.class)));
    }
    if (r.get("outdated_reason", String.class) != null) {
      d.setOutdatedReason(OutdatedReason.fromValue(r.get("outdated_reason", String.class)));
    }
    List<RowFlag> flags = new ArrayList<>();
    if (flagged) {
      flags.add(RowFlag.NEEDS_CLARIFICATION);
    }
    if (r.get("outdated_at", OffsetDateTime.class) != null) {
      flags.add(RowFlag.OUTDATED);
    }
    if (gid == null || "failed".equals(outcome)) {
      flags.add(RowFlag.NOT_EVALUATED);
    }
    if ("overridden".equals(state)) {
      flags.add(RowFlag.OVERRIDDEN);
    }
    BigDecimal conf = r.get("confidence", BigDecimal.class);
    if (conf != null && conf.doubleValue() < lowConfidence) {
      flags.add(RowFlag.LOW_CONFIDENCE);
    }
    Integer version = r.get("row_version", Integer.class);
    return new EvaluationRow()
        .requirement(
            new RequirementLite()
                .id(r.get("requirement_id", UUID.class))
                .displayNo(r.get("display_no", String.class))
                .text(r.get("text", String.class))
                .category(
                    r.get("category_name", String.class) == null
                        ? ""
                        : r.get("category_name", String.class))
                .importance(Importance.fromValue(r.get("importance", String.class)))
                .acceptanceCriteria(r.get("acceptance_criteria", String.class)))
        .ai(ai)
        .member(d)
        .flags(flags)
        .rowVersion(version == null ? 1 : version);
  }

  private List<String> list(String jsonText) {
    if (jsonText == null) {
      return new ArrayList<>();
    }
    @SuppressWarnings("unchecked")
    List<String> out = json.readValue(jsonText, List.class);
    return out;
  }

  private Mono<Double> lowThreshold(DatabaseClient db) {
    return db.sql("SELECT value FROM settings WHERE key = 'ai.low_confidence_threshold'")
        .map((r, m) -> Double.parseDouble(r.get("value", String.class)))
        .one()
        .defaultIfEmpty(0.6);
  }

  /** Adds the citations of the page's current AI grades. */
  private Mono<List<EvaluationRow>> withCitations(DatabaseClient db, List<EvaluationRow> rows) {
    List<UUID> ids =
        rows.stream().filter(r -> r.getAi() != null).map(r -> r.getAi().getId()).toList();
    if (ids.isEmpty()) {
      return Mono.just(rows);
    }
    return db.sql(
            "SELECT ai_grade_id, citation_no, document_version_id, passage_id, page_no, excerpt"
                + " FROM grade_citations WHERE ai_grade_id IN (:ids) ORDER BY ai_grade_id, citation_no")
        .bind("ids", ids)
        .map(
            (r, m) ->
                Map.entry(
                    r.get("ai_grade_id", UUID.class),
                    new Citation()
                        .citationNo(r.get("citation_no", Integer.class))
                        .documentVersionId(r.get("document_version_id", UUID.class))
                        .passageId(r.get("passage_id", UUID.class))
                        .pageNo(r.get("page_no", Integer.class))
                        .excerpt(r.get("excerpt", String.class))))
        .all()
        .collectList()
        .map(
            entries -> {
              for (EvaluationRow row : rows) {
                if (row.getAi() == null) {
                  continue;
                }
                for (var e : entries) {
                  if (e.getKey().equals(row.getAi().getId())) {
                    row.getAi().getCitations().add(e.getValue());
                  }
                }
              }
              return rows;
            });
  }

  public Mono<EvaluationRowPage> rows(
      DatabaseClient db,
      UUID tenderVendorId,
      Integer limit,
      String cursor,
      String q,
      Grade grade,
      Importance importance,
      UUID categoryId,
      RowReviewState reviewState,
      Boolean overridden,
      Boolean flagged,
      Boolean lowConfidence,
      Boolean outdated,
      Boolean notEvaluated) {
    return cleared(db, tenderVendorId)
        .flatMap(
            c -> {
              if (c.baselineId() == null || c.versionId() == null) {
                return Mono.just(new EvaluationRowPage().items(new ArrayList<>()).total(0));
              }
              int lim = Cursor.limit(limit);
              StringBuilder sql = new StringBuilder(ROW_SELECT);
              Map<String, Object> binds = new LinkedHashMap<>();
              binds.put("pv", c.versionId());
              binds.put("me", c.evaluationId() == null ? new UUID(0, 0) : c.evaluationId());
              binds.put("b", c.baselineId());
              if (q != null && !q.isBlank()) {
                sql.append(" AND (br.text ILIKE :q OR br.display_no ILIKE :q)");
                binds.put("q", "%" + q.trim() + "%");
              }
              if (grade != null) {
                sql.append(" AND coalesce(r.member_grade, g.grade) = :grade");
                binds.put("grade", grade.getValue());
              }
              if (importance != null) {
                sql.append(" AND br.importance = :imp");
                binds.put("imp", importance.getValue());
              }
              if (categoryId != null) {
                sql.append(" AND q.category_id = :cat");
                binds.put("cat", categoryId);
              }
              if (reviewState != null) {
                sql.append(" AND coalesce(r.review_state, 'not_reviewed') = :rs");
                binds.put("rs", reviewState.getValue());
              }
              if (Boolean.TRUE.equals(overridden)) {
                sql.append(" AND r.review_state = 'overridden'");
              }
              if (Boolean.TRUE.equals(flagged)) {
                sql.append(" AND r.needs_clarification");
              }
              if (Boolean.TRUE.equals(outdated)) {
                sql.append(" AND r.outdated_at IS NOT NULL");
              }
              if (Boolean.TRUE.equals(notEvaluated)) {
                sql.append(" AND (g.id IS NULL OR g.outcome = 'failed')");
              }
              Cursor cur = Cursor.decodeOrNull(cursor);
              if (cur != null) {
                sql.append(" AND br.display_no > :after");
                binds.put("after", cur.sortValue());
              }
              sql.append(" ORDER BY br.display_no LIMIT ").append(lim + 1);
              return lowThreshold(db)
                  .flatMap(
                      threshold -> {
                        DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString());
                        for (var e : binds.entrySet()) {
                          spec = spec.bind(e.getKey(), e.getValue());
                        }
                        return spec.map((r, m) -> map(r, threshold)).all().collectList();
                      })
                  .flatMap(
                      all -> {
                        boolean more = all.size() > lim;
                        List<EvaluationRow> page = more ? all.subList(0, lim) : all;
                        String next =
                            more
                                ? new Cursor(
                                        page.get(page.size() - 1).getRequirement().getDisplayNo(),
                                        page.get(page.size() - 1).getRequirement().getId())
                                    .encode()
                                : null;
                        return withCitations(db, new ArrayList<>(page))
                            .map(
                                rows ->
                                    new EvaluationRowPage()
                                        .items(rows)
                                        .nextCursor(next)
                                        .total(null));
                      });
            });
  }

  public Mono<EvaluationRow> row(DatabaseClient db, UUID tenderVendorId, UUID requirementId) {
    return cleared(db, tenderVendorId)
        .flatMap(
            c ->
                lowThreshold(db)
                    .flatMap(
                        threshold ->
                            db.sql(ROW_SELECT + " AND br.requirement_id = :req")
                                .bind("pv", c.versionId() == null ? new UUID(0, 0) : c.versionId())
                                .bind(
                                    "me",
                                    c.evaluationId() == null ? new UUID(0, 0) : c.evaluationId())
                                .bind("b", c.baselineId() == null ? new UUID(0, 0) : c.baselineId())
                                .bind("req", requirementId)
                                .map((r, m) -> map(r, threshold))
                                .one())
                    .switchIfEmpty(
                        Mono.error(ApiException.notFound("requirement of this baseline")))
                    .flatMap(
                        row ->
                            withCitations(db, new ArrayList<>(List.of(row))).map(l -> l.get(0))));
  }

  // ---- decisions
  // -------------------------------------------------------------------------------------------

  private Mono<Ctx> writable(DatabaseClient db, UUID tenderVendorId) {
    return cleared(db, tenderVendorId)
        .flatMap(
            c -> {
              if (c.evaluationId() == null) {
                return Mono.<Ctx>error(
                    ApiException.conflict(
                        "evaluation_not_ready",
                        "The evaluation is still running. You can decide when it has finished"));
              }
              if (c.excluded()) {
                return Mono.<Ctx>error(
                    ApiException.forbidden("excluded", "You are excluded from this vendor"));
              }
              if ("submitted".equals(c.status())) {
                return Mono.<Ctx>error(
                    ApiException.conflict(
                        "evaluation_submitted", "This evaluation is submitted and read only"));
              }
              return Mono.just(c);
            });
  }

  public Mono<EvaluationRow> update(
      DatabaseClient db,
      CurrentUser actor,
      UUID tenderVendorId,
      UUID requirementId,
      int ifMatch,
      RowUpdate in) {
    return writable(db, tenderVendorId)
        .flatMap(
            c ->
                Settings.load(db)
                    .flatMap(
                        settings ->
                            db.sql(
                                    "SELECT r.id, r.row_version, r.review_state, r.member_grade,"
                                        + " r.needs_clarification, g.grade AS ai_grade, g.outcome"
                                        + " FROM member_evaluation_rows r LEFT JOIN ai_grades g ON g.id ="
                                        + " r.ai_grade_id WHERE r.member_evaluation_id = :me"
                                        + " AND r.requirement_id = :req")
                                .bind("me", c.evaluationId())
                                .bind("req", requirementId)
                                .map(
                                    (r, m) ->
                                        new Object[] {
                                          r.get("id", UUID.class),
                                          r.get("row_version", Integer.class),
                                          r.get("review_state", String.class),
                                          r.get("member_grade", String.class),
                                          r.get("ai_grade", String.class),
                                          r.get("outcome", String.class)
                                        })
                                .one()
                                .switchIfEmpty(
                                    Mono.error(ApiException.notFound("row of this evaluation")))
                                .flatMap(
                                    row ->
                                        apply(
                                            db,
                                            actor,
                                            c,
                                            requirementId,
                                            ifMatch,
                                            in,
                                            row,
                                            settings.minNoteLength()))))
        .then(row(db, tenderVendorId, requirementId));
  }

  private Mono<Void> apply(
      DatabaseClient db,
      CurrentUser actor,
      Ctx c,
      UUID requirementId,
      int ifMatch,
      RowUpdate in,
      Object[] row,
      int minNote) {
    UUID rowId = (UUID) row[0];
    int version = (Integer) row[1];
    String before = (String) row[2];
    String aiGrade = (String) row[4];
    if (version != ifMatch) {
      return Mono.error(ETags.stale());
    }
    String action = in.getAction() == null ? "" : in.getAction().getValue();
    String sql;
    Map<String, Object> binds = new LinkedHashMap<>();
    String auditAction;
    String note = in.getNote() == null ? null : in.getNote().strip();
    Map<String, Object> after = new LinkedHashMap<>();
    switch (action) {
      case "agree" -> {
        if (aiGrade == null) {
          return Mono.error(
              ApiException.conflict("no_ai_grade", "There is no AI grade to agree with"));
        }
        sql =
            "UPDATE member_evaluation_rows SET review_state = 'agreed', member_grade = :g,"
                + " note = NULL WHERE id = :id AND row_version = :v";
        binds.put("g", aiGrade);
        auditAction = "grade_agreed";
        after.put("review_state", "agreed");
      }
      case "override" -> {
        if (in.getGrade() == null) {
          return Mono.error(ApiException.invalid("grade", "required", "Choose a grade"));
        }
        if (aiGrade != null && aiGrade.equals(in.getGrade().getValue())) {
          return Mono.error(
              ApiException.invalid(
                  "grade",
                  "same_as_ai",
                  "Choose a grade that differs from the AI grade, or agree"));
        }
        if (note == null || note.length() < minNote) {
          return Mono.error(
              ApiException.invalid(
                  "note", "too_short", "Give a reason of at least " + minNote + " characters"));
        }
        sql =
            "UPDATE member_evaluation_rows SET review_state = 'overridden', member_grade = :g,"
                + " note = :n WHERE id = :id AND row_version = :v";
        binds.put("g", in.getGrade().getValue());
        binds.put("n", note);
        auditAction = "grade_overridden";
        after.put("review_state", "overridden");
      }
      case "flag_clarification" -> {
        sql =
            "UPDATE member_evaluation_rows SET needs_clarification = true, clarification_note = :n"
                + " WHERE id = :id AND row_version = :v";
        binds.put("n", note == null || note.isEmpty() ? "" : note);
        auditAction = "row_flagged";
        after.put("needs_clarification", true);
      }
      case "clear_clarification" -> {
        sql =
            "UPDATE member_evaluation_rows SET needs_clarification = false, clarification_note = NULL"
                + " WHERE id = :id AND row_version = :v";
        auditAction = "row_flagged";
        after.put("needs_clarification", false);
      }
      default -> {
        return Mono.error(ApiException.invalid("action", "unknown", "Unknown action"));
      }
    }
    DatabaseClient.GenericExecuteSpec spec = db.sql(sql).bind("id", rowId).bind("v", version);
    for (var e : binds.entrySet()) {
      spec = spec.bind(e.getKey(), e.getValue());
    }
    // No grade or note goes to audit_events beyond what the note-required check forces: the
    // administrator reads the trail only after release (V22).
    Map<String, Object> was = new LinkedHashMap<>();
    was.put("review_state", before);
    AuditEntry.Builder entry =
        AuditEntry.of(auditAction, "member_evaluation_row", rowId)
            .tender(c.tenderId())
            .vendor(c.tenderVendorId())
            .changed(was, after);
    if ("grade_overridden".equals(auditAction)) {
      entry.note(note);
    }
    return spec.fetch()
        .rowsUpdated()
        .flatMap(
            n -> n == 0 ? Mono.<Void>error(ETags.stale()) : audit.record(db, actor, entry.build()));
  }

  public Mono<BulkResult> bulkAgree(
      DatabaseClient db, CurrentUser actor, UUID tenderVendorId, BulkRows in) {
    return writable(db, tenderVendorId)
        .flatMap(
            c ->
                db.sql(
                        "SELECT r.id, r.requirement_id, r.review_state, g.grade AS ai_grade,"
                            + " r.outdated_at FROM member_evaluation_rows r LEFT JOIN ai_grades g"
                            + " ON g.id = r.ai_grade_id WHERE r.member_evaluation_id = :me"
                            + " AND r.requirement_id IN (:ids)")
                    .bind("me", c.evaluationId())
                    .bind("ids", in.getRequirementIds())
                    .map(
                        (r, m) ->
                            new Object[] {
                              r.get("id", UUID.class),
                              r.get("requirement_id", UUID.class),
                              r.get("review_state", String.class),
                              r.get("ai_grade", String.class)
                            })
                    .all()
                    .collectList()
                    .flatMap(
                        rows -> {
                          Map<UUID, Object[]> byReq = new LinkedHashMap<>();
                          rows.forEach(o -> byReq.put((UUID) o[1], o));
                          List<BulkResultSkippedInner> skipped = new ArrayList<>();
                          UUID batch = UUID.randomUUID();
                          Mono<Integer> chain = Mono.just(0);
                          for (UUID req : in.getRequirementIds()) {
                            Object[] o = byReq.get(req);
                            if (o == null) {
                              skipped.add(new BulkResultSkippedInner().id(req).code("not_found"));
                            } else if (o[3] == null) {
                              skipped.add(new BulkResultSkippedInner().id(req).code("no_ai_grade"));
                            } else if ("agreed".equals(o[2])) {
                              skipped.add(
                                  new BulkResultSkippedInner().id(req).code("already_agreed"));
                            } else {
                              chain =
                                  chain.flatMap(
                                      count ->
                                          db.sql(
                                                  "UPDATE member_evaluation_rows SET review_state ="
                                                      + " 'agreed', member_grade = :g, note = NULL"
                                                      + " WHERE id = :id")
                                              .bind("g", o[3])
                                              .bind("id", o[0])
                                              .fetch()
                                              .rowsUpdated()
                                              .then(
                                                  audit.record(
                                                      db,
                                                      actor,
                                                      AuditEntry.of(
                                                              "grade_agreed",
                                                              "member_evaluation_row",
                                                              (UUID) o[0])
                                                          .tender(c.tenderId())
                                                          .vendor(c.tenderVendorId())
                                                          .batch(batch)
                                                          .changed(
                                                              Map.of("review_state", o[2]),
                                                              Map.of("review_state", "agreed"))
                                                          .build()))
                                              .thenReturn(count + 1));
                            }
                          }
                          return chain.map(n -> new BulkResult().updated(n).skipped(skipped));
                        }));
  }

  public Mono<Void> feedback(
      DatabaseClient db,
      CurrentUser actor,
      UUID tenderVendorId,
      UUID requirementId,
      FeedbackInput in) {
    List<String> tags = in.getReasonTags() == null ? List.of() : in.getReasonTags();
    if (!FEEDBACK_TAGS.containsAll(tags)) {
      return Mono.error(ApiException.invalid("reason_tags", "unknown", "Unknown reason"));
    }
    return cleared(db, tenderVendorId)
        .flatMap(
            c ->
                db.sql(
                        "SELECT g.id FROM ai_grades g WHERE g.proposal_version_id = :pv"
                            + " AND g.requirement_id = :req AND g.is_current")
                    .bind("pv", c.versionId() == null ? new UUID(0, 0) : c.versionId())
                    .bind("req", requirementId)
                    .map((r, m) -> r.get("id", UUID.class))
                    .one()
                    .switchIfEmpty(
                        Mono.error(ApiException.notFound("AI grade of this requirement")))
                    .flatMap(
                        gid -> {
                          DatabaseClient.GenericExecuteSpec spec =
                              db.sql(
                                      "INSERT INTO ai_grade_feedback (ai_grade_id, user_id, is_useful,"
                                          + " reason_tags, comment) VALUES (:g, :u, :f,"
                                          + " CAST(:t AS text[]), :c) ON CONFLICT (ai_grade_id, user_id)"
                                          + " DO UPDATE SET is_useful = EXCLUDED.is_useful,"
                                          + " reason_tags = EXCLUDED.reason_tags,"
                                          + " comment = EXCLUDED.comment")
                                  .bind("g", gid)
                                  .bind("u", actor.id())
                                  .bind("f", Boolean.TRUE.equals(in.getIsUseful()))
                                  .bind("t", "{" + String.join(",", tags) + "}");
                          String comment =
                              in.getComment() == null || in.getComment().isBlank()
                                  ? null
                                  : in.getComment().strip();
                          spec =
                              comment == null
                                  ? spec.bindNull("c", String.class)
                                  : spec.bind("c", comment);
                          return spec.fetch()
                              .rowsUpdated()
                              .then(
                                  audit.record(
                                      db,
                                      actor,
                                      AuditEntry.of("ai_feedback_sent", "ai_grade", gid)
                                          .tender(c.tenderId())
                                          .vendor(c.tenderVendorId())
                                          .created(
                                              Map.of(
                                                  "is_useful",
                                                  Boolean.TRUE.equals(in.getIsUseful())))
                                          .build()));
                        }));
  }

  // ---- history, peers, clarifications
  // ----------------------------------------------------------------------------

  public Mono<History> history(DatabaseClient db, UUID tenderVendorId, UUID requirementId) {
    return cleared(db, tenderVendorId)
        .flatMap(
            c ->
                Mono.zip(
                        db.sql(
                                "SELECT id, occurred_at, source, action, actor_user_id, display_name,"
                                    + " before_json, after_json, note"
                                    + " FROM app_evaluation_row_history(:v, :r)")
                            .bind("v", tenderVendorId)
                            .bind("r", requirementId)
                            .map(
                                (r, m) -> {
                                  UUID actor = r.get("actor_user_id", UUID.class);
                                  String name = r.get("display_name", String.class);
                                  HistoryEvent ev =
                                      new HistoryEvent()
                                          .id(r.get("id", UUID.class))
                                          .occurredAt(r.get("occurred_at", OffsetDateTime.class))
                                          .source(
                                              AuditSource.fromValue(r.get("source", String.class)))
                                          .action(
                                              AuditAction.fromValue(r.get("action", String.class)))
                                          .actor(
                                              actor == null || name == null
                                                  ? null
                                                  : new UserRef().id(actor).displayName(name))
                                          .note(r.get("note", String.class));
                                  ev.setBefore(toMap(r.get("before_json", String.class)));
                                  ev.setAfter(toMap(r.get("after_json", String.class)));
                                  return ev;
                                })
                            .all()
                            .collectList(),
                        // the first AI grade of this requirement for this proposal, kept as the
                        // original
                        db.sql(
                                "SELECT g.grade, g.non_compliance_reason, g.rationale_summary, g.created_at"
                                    + " FROM ai_grades g WHERE g.proposal_version_id = :pv AND"
                                    + " g.requirement_id = :r ORDER BY g.created_at LIMIT 1")
                            .bind("pv", c.versionId() == null ? new UUID(0, 0) : c.versionId())
                            .bind("r", requirementId)
                            .map(
                                (r, m) -> {
                                  Map<String, Object> o = new LinkedHashMap<>();
                                  o.put("grade", r.get("grade", String.class));
                                  o.put(
                                      "non_compliance_reason",
                                      r.get("non_compliance_reason", String.class));
                                  o.put(
                                      "rationale_summary",
                                      r.get("rationale_summary", String.class));
                                  return o;
                                })
                            .one()
                            .map(java.util.Optional::of)
                            .defaultIfEmpty(java.util.Optional.empty()))
                    .map(
                        z -> {
                          History h = new History().events(z.getT1());
                          h.setAiOriginal(z.getT2().orElse(null));
                          return h;
                        }));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> toMap(String jsonText) {
    return jsonText == null ? null : (Map<String, Object>) json.readValue(jsonText, Map.class);
  }

  public Mono<PeerRowPage> peers(
      DatabaseClient db, UUID tenderVendorId, Integer limit, String cursor, UUID requirementId) {
    return ctx(db, tenderVendorId)
        .flatMap(
            c -> {
              if (!c.firstSubmitted()) {
                return Mono.<PeerRowPage>error(
                    ApiException.forbidden(
                        "peers_locked",
                        "Other members' grades open once you have submitted this vendor"));
              }
              int lim = Cursor.limit(limit);
              String sql =
                  "SELECT r.requirement_id, r.review_state, r.member_grade, r.note,"
                      + " r.needs_clarification, e.user_id, u.display_name, q.display_no"
                      + " FROM member_evaluation_rows r"
                      + " JOIN member_evaluations e ON e.id = r.member_evaluation_id"
                      + " JOIN users u ON u.id = e.user_id"
                      + " JOIN requirements q ON q.id = r.requirement_id"
                      + " WHERE e.tender_vendor_id = :v AND e.user_id <> app_user_id()"
                      + " AND e.status = 'submitted'"
                      + (requirementId != null ? " AND r.requirement_id = :req" : "")
                      + " ORDER BY q.display_no, u.display_name LIMIT "
                      + lim;
              DatabaseClient.GenericExecuteSpec spec = db.sql(sql).bind("v", tenderVendorId);
              if (requirementId != null) {
                spec = spec.bind("req", requirementId);
              }
              return spec.map(
                      (r, m) -> {
                        PeerRow p =
                            new PeerRow()
                                .member(
                                    new UserRef()
                                        .id(r.get("user_id", UUID.class))
                                        .displayName(r.get("display_name", String.class)))
                                .requirementId(r.get("requirement_id", UUID.class))
                                .reviewState(
                                    RowReviewState.fromValue(r.get("review_state", String.class)))
                                .note(r.get("note", String.class))
                                .needsClarification(
                                    Boolean.TRUE.equals(
                                        r.get("needs_clarification", Boolean.class)));
                        if (r.get("member_grade", String.class) != null) {
                          p.setGrade(Grade.fromValue(r.get("member_grade", String.class)));
                        }
                        return p;
                      })
                  .all()
                  .collectList()
                  .map(items -> new PeerRowPage().items(items).total(items.size()));
            });
  }

  public Mono<List<Clarification>> clarifications(DatabaseClient db, UUID tenderVendorId) {
    return cleared(db, tenderVendorId)
        .flatMap(
            c ->
                c.evaluationId() == null
                    ? Mono.just(List.<Clarification>of())
                    : db.sql(
                            "SELECT br.requirement_id, br.display_no, br.text, br.category_name,"
                                + " br.importance, br.acceptance_criteria, r.clarification_note,"
                                + " r.member_grade FROM member_evaluation_rows r"
                                + " JOIN baseline_requirements br ON br.requirement_id = r.requirement_id"
                                + " AND br.baseline_id = :b WHERE r.member_evaluation_id = :me"
                                + " AND r.needs_clarification ORDER BY br.display_no")
                        .bind("b", c.baselineId())
                        .bind("me", c.evaluationId())
                        .map(
                            (r, m) ->
                                new Clarification()
                                    .requirement(
                                        new RequirementLite()
                                            .id(r.get("requirement_id", UUID.class))
                                            .displayNo(r.get("display_no", String.class))
                                            .text(r.get("text", String.class))
                                            .category(
                                                r.get("category_name", String.class) == null
                                                    ? ""
                                                    : r.get("category_name", String.class))
                                            .importance(
                                                Importance.fromValue(
                                                    r.get("importance", String.class)))
                                            .acceptanceCriteria(
                                                r.get("acceptance_criteria", String.class)))
                                    .note(r.get("clarification_note", String.class))
                                    .decision(r.get("member_grade", String.class)))
                        .all()
                        .collectList());
  }

  // ---- my evaluation, summary, submit
  // ------------------------------------------------------------------------------

  /** Counts, score and the pre-check failures of the caller's own rows. */
  private record Totals(
      int total,
      int reviewed,
      int ful,
      int part,
      int non,
      int notReviewed,
      int notEvaluated,
      int mandatoryFailures,
      int undecidedFlagged) {}

  private Mono<Totals> totals(DatabaseClient db, Ctx c) {
    if (c.baselineId() == null) {
      return Mono.just(new Totals(0, 0, 0, 0, 0, 0, 0, 0, 0));
    }
    return db.sql(
            "SELECT count(*) AS total,"
                + " count(*) FILTER (WHERE r.review_state IN ('agreed', 'overridden')) AS reviewed,"
                + " count(*) FILTER (WHERE r.member_grade = 'fully_compliant') AS ful,"
                + " count(*) FILTER (WHERE r.member_grade = 'partially_compliant') AS part,"
                + " count(*) FILTER (WHERE r.member_grade = 'non_compliant') AS non,"
                + " count(*) FILTER (WHERE coalesce(r.review_state, 'not_reviewed') = 'not_reviewed'"
                + "   AND g.outcome = 'graded') AS not_reviewed,"
                + " count(*) FILTER (WHERE coalesce(r.review_state, 'not_reviewed') = 'not_reviewed'"
                + "   AND (g.id IS NULL OR g.outcome = 'failed')) AS not_evaluated,"
                + " count(*) FILTER (WHERE br.importance = 'mandatory'"
                + "   AND coalesce(r.member_grade, g.grade) = 'non_compliant') AS mand,"
                + " count(*) FILTER (WHERE r.needs_clarification"
                + "   AND coalesce(r.review_state, 'not_reviewed') = 'not_reviewed') AS flagged"
                + " FROM baseline_requirements br"
                + " LEFT JOIN ai_grades g ON g.proposal_version_id = :pv AND g.requirement_id ="
                + "   br.requirement_id AND g.is_current"
                + " LEFT JOIN member_evaluation_rows r ON r.member_evaluation_id = :me"
                + "   AND r.requirement_id = br.requirement_id"
                + " WHERE br.baseline_id = :b")
        .bind("pv", c.versionId() == null ? new UUID(0, 0) : c.versionId())
        .bind("me", c.evaluationId() == null ? new UUID(0, 0) : c.evaluationId())
        .bind("b", c.baselineId())
        .map(
            (r, m) ->
                new Totals(
                    r.get("total", Long.class).intValue(),
                    r.get("reviewed", Long.class).intValue(),
                    r.get("ful", Long.class).intValue(),
                    r.get("part", Long.class).intValue(),
                    r.get("non", Long.class).intValue(),
                    r.get("not_reviewed", Long.class).intValue(),
                    r.get("not_evaluated", Long.class).intValue(),
                    r.get("mand", Long.class).intValue(),
                    r.get("flagged", Long.class).intValue()))
        .one();
  }

  private Mono<EvaluationSummary> summary(DatabaseClient db, Ctx c, Totals t) {
    Mono<BigDecimal[]> points =
        c.baselineId() == null
            ? Mono.just(new BigDecimal[] {BigDecimal.ONE, new BigDecimal("0.5"), BigDecimal.ZERO})
            : db.sql(
                    "SELECT points_fully, points_partially, points_non FROM scoring_models"
                        + " WHERE baseline_id = :b")
                .bind("b", c.baselineId())
                .map(
                    (r, m) ->
                        new BigDecimal[] {
                          r.get("points_fully", BigDecimal.class),
                          r.get("points_partially", BigDecimal.class),
                          r.get("points_non", BigDecimal.class)
                        })
                .one()
                .defaultIfEmpty(
                    new BigDecimal[] {BigDecimal.ONE, new BigDecimal("0.5"), BigDecimal.ZERO});
    Mono<List<CategoryScore>> byCategory =
        c.baselineId() == null
            ? Mono.just(List.<CategoryScore>of())
            : db.sql(
                    "SELECT coalesce(br.category_name, 'Uncategorised') AS cat, count(*) AS total,"
                        + " count(*) FILTER (WHERE r.review_state IN ('agreed', 'overridden')) AS graded,"
                        + " count(*) FILTER (WHERE r.member_grade = 'fully_compliant') AS ful,"
                        + " count(*) FILTER (WHERE r.member_grade = 'partially_compliant') AS part"
                        + " FROM baseline_requirements br LEFT JOIN member_evaluation_rows r"
                        + " ON r.member_evaluation_id = :me AND r.requirement_id = br.requirement_id"
                        + " WHERE br.baseline_id = :b GROUP BY 1 ORDER BY 1")
                .bind("me", c.evaluationId() == null ? new UUID(0, 0) : c.evaluationId())
                .bind("b", c.baselineId())
                .map(
                    (r, m) ->
                        new CategoryScore()
                            .category(r.get("cat", String.class))
                            .total(r.get("total", Long.class).intValue())
                            .graded(r.get("graded", Long.class).intValue())
                            .score(
                                r.get("total", Long.class) == 0
                                    ? null
                                    : BigDecimal.valueOf(
                                            r.get("ful", Long.class)
                                                + 0.5 * r.get("part", Long.class))
                                        .divide(
                                            BigDecimal.valueOf(r.get("total", Long.class)),
                                            3,
                                            RoundingMode.HALF_UP)))
                .all()
                .collectList();
    return Mono.zip(points, byCategory)
        .map(
            z -> {
              BigDecimal[] p = z.getT1();
              BigDecimal score =
                  p[0].multiply(BigDecimal.valueOf(t.ful()))
                      .add(p[1].multiply(BigDecimal.valueOf(t.part())))
                      .add(p[2].multiply(BigDecimal.valueOf(t.non())));
              BigDecimal max = p[0].multiply(BigDecimal.valueOf(Math.max(1, t.total())));
              Map<String, Object> explanation = new LinkedHashMap<>();
              explanation.put(
                  "basis", "your own decisions only; rows you have not reviewed score nothing yet");
              explanation.put("points_fully", p[0]);
              explanation.put("points_partially", p[1]);
              explanation.put("points_non", p[2]);
              explanation.put("requirements", t.total());
              return new EvaluationSummary()
                  .complianceScore(score.setScale(2, RoundingMode.HALF_UP))
                  .compliancePercent(
                      max.signum() == 0
                          ? null
                          : score
                              .multiply(BigDecimal.valueOf(100))
                              .divide(max, 1, RoundingMode.HALF_UP))
                  .counts(
                      new EvaluationSummaryCounts()
                          .fullyCompliant(t.ful())
                          .partiallyCompliant(t.part())
                          .nonCompliant(t.non())
                          .notReviewed(t.notReviewed())
                          .notEvaluated(t.notEvaluated()))
                  .mandatoryFailures(t.mandatoryFailures())
                  .reviewed(t.reviewed())
                  .total(t.total())
                  .byCategory(z.getT2())
                  .explanation(explanation);
            });
  }

  public Mono<MemberEvaluation> mine(DatabaseClient db, UUID tenderVendorId) {
    return cleared(db, tenderVendorId)
        .flatMap(
            c ->
                totals(db, c)
                    .flatMap(
                        t ->
                            Mono.zip(
                                    summary(db, c, t),
                                    latestJob(db, c)
                                        .map(java.util.Optional::of)
                                        .defaultIfEmpty(java.util.Optional.empty()))
                                .map(
                                    z -> {
                                      String status =
                                          c.status() == null ? "not_started" : c.status();
                                      boolean ready = c.evaluationId() != null;
                                      boolean open =
                                          ready && !c.excluded() && !"submitted".equals(status);
                                      boolean complete =
                                          t.notReviewed() == 0
                                              && t.notEvaluated() == 0
                                              && t.undecidedFlagged() == 0;
                                      return new MemberEvaluation()
                                          // before the job completes the vendor id stands in
                                          .id(ready ? c.evaluationId() : c.tenderVendorId())
                                          .tenderVendorId(c.tenderVendorId())
                                          .vendorName(c.vendorName())
                                          .status(MemberEvaluationStatus.fromValue(status))
                                          .firstSubmittedAt(c.firstAt())
                                          .submittedAt(c.submittedAt())
                                          .excluded(c.excluded())
                                          .summary(z.getT1())
                                          .job(z.getT2().orElse(null))
                                          .proposalVersionNo(c.versionNo())
                                          .baselineVersion(c.baselineNo())
                                          .actions(
                                              Actions.builder()
                                                  .when(
                                                      "decide_rows",
                                                      open,
                                                      !ready
                                                          ? "The evaluation is still running"
                                                          : c.excluded()
                                                              ? "You are excluded from this vendor"
                                                              : "This evaluation is submitted")
                                                  .when(
                                                      "submit",
                                                      open && complete,
                                                      !open
                                                          ? "This evaluation cannot be submitted now"
                                                          : (t.notReviewed()
                                                                  + t.notEvaluated()
                                                                  + t.undecidedFlagged())
                                                              + " rows still need a decision")
                                                  .build());
                                    })));
  }

  private Mono<Job> latestJob(DatabaseClient db, Ctx c) {
    if (c.versionId() == null) {
      return Mono.empty();
    }
    return db.sql(
            "SELECT id FROM jobs WHERE target_id = :pv AND job_type = 'evaluation'"
                + " ORDER BY queued_at DESC LIMIT 1")
        .bind("pv", c.versionId())
        .map((r, m) -> r.get("id", UUID.class))
        .one()
        .flatMap(id -> jobs.get(db, id));
  }

  public Mono<MemberEvaluation> submit(DatabaseClient db, CurrentUser actor, UUID tenderVendorId) {
    return writable(db, tenderVendorId)
        .flatMap(
            c ->
                totals(db, c)
                    .flatMap(
                        t -> {
                          List<Map<String, Object>> failures = new ArrayList<>();
                          if (t.notReviewed() > 0) {
                            failures.add(
                                failure(
                                    "rows_not_reviewed",
                                    t.notReviewed() + " rows are not reviewed",
                                    c));
                          }
                          if (t.notEvaluated() > 0) {
                            failures.add(
                                failure(
                                    "rows_not_evaluated",
                                    t.notEvaluated()
                                        + " rows were not evaluated and need your own grade",
                                    c));
                          }
                          if (t.undecidedFlagged() > 0) {
                            failures.add(
                                failure(
                                    "flagged_rows_undecided",
                                    t.undecidedFlagged() + " flagged rows have no decision",
                                    c));
                          }
                          if (!failures.isEmpty()) {
                            return Mono.<MemberEvaluation>error(
                                new ApiException(
                                        HttpStatus.UNPROCESSABLE_ENTITY,
                                        "precheck_failed",
                                        "The evaluation is not ready to submit")
                                    .with("failures", failures));
                          }
                          return db.sql(
                                  "UPDATE member_evaluations SET status = 'submitted' WHERE id = :id"
                                      + " AND status <> 'submitted'")
                              .bind("id", c.evaluationId())
                              .fetch()
                              .rowsUpdated()
                              .then(
                                  audit.record(
                                      db,
                                      actor,
                                      AuditEntry.of(
                                              "evaluation_submitted",
                                              "member_evaluation",
                                              c.evaluationId())
                                          .tender(c.tenderId())
                                          .vendor(c.tenderVendorId())
                                          .created(Map.of("rows", t.total()))
                                          .build()))
                              // The last submission moves the tender to consolidation (V24).
                              .then(advance(db, c.tenderId()))
                              .then(tellChairIfComplete(db, c.tenderId(), tenderVendorId))
                              .then(mine(db, tenderVendorId));
                        }));
  }

  /** Moves the tender from evaluation to consolidation when the rule now holds. */
  private Mono<Void> advance(DatabaseClient db, UUID tenderId) {
    return db.sql("SELECT app_advance_to_consolidation(:t)")
        .bind("t", tenderId)
        .fetch()
        .rowsUpdated()
        .then();
  }

  private static Map<String, Object> failure(String code, String message, Ctx c) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("code", code);
    f.put("message", message);
    f.put("object_type", "tender_vendor");
    f.put("object_ids", List.of(c.tenderVendorId().toString()));
    f.put("link", "/tenders/" + c.tenderId() + "/vendors/" + c.tenderVendorId() + "/evaluation");
    return f;
  }

  // ---- the Chair: reopen, exclude
  // -----------------------------------------------------------------------------------

  private Mono<UUID[]> evaluationOf(DatabaseClient db, UUID memberEvaluationId) {
    return db.sql(
            "SELECT e.id, tv.tender_id, tv.id AS tv FROM member_evaluations e"
                + " JOIN tender_vendors tv ON tv.id = e.tender_vendor_id WHERE e.id = :id")
        .bind("id", memberEvaluationId)
        .map(
            (r, m) ->
                new UUID[] {
                  r.get("id", UUID.class), r.get("tender_id", UUID.class), r.get("tv", UUID.class)
                })
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("evaluation")));
  }

  public Mono<MemberEvaluation> reopen(
      DatabaseClient db, CurrentUser actor, UUID memberEvaluationId, ReopenEvaluation in) {
    String reason = in.getReason() == null ? "" : in.getReason().strip();
    return Settings.load(db)
        .flatMap(
            s -> {
              if (reason.length() < s.minNoteLength()) {
                return Mono.<MemberEvaluation>error(
                    ApiException.invalid(
                        "reason",
                        "too_short",
                        "Give a reason of at least " + s.minNoteLength() + " characters"));
              }
              return evaluationOf(db, memberEvaluationId)
                  .flatMap(
                      ids ->
                          db.sql("SELECT app_is_chair_of(:t) AS chair")
                              .bind("t", ids[1])
                              .map((r, m) -> Boolean.TRUE.equals(r.get("chair", Boolean.class)))
                              .one()
                              .flatMap(
                                  chair -> {
                                    if (!chair) {
                                      return Mono.<MemberEvaluation>error(
                                          ApiException.forbidden(
                                              "forbidden", "Only the Chair reopens an evaluation"));
                                    }
                                    return db.sql(
                                            "UPDATE member_evaluations SET status = 'reopened',"
                                                + " reopened_by = :u, reopen_reason = :r"
                                                + " WHERE id = :id AND status = 'submitted'")
                                        .bind("u", actor.id())
                                        .bind("r", reason)
                                        .bind("id", memberEvaluationId)
                                        .fetch()
                                        .rowsUpdated()
                                        .flatMap(
                                            n ->
                                                n == 0
                                                    ? Mono.<MemberEvaluation>error(
                                                        ApiException.conflict(
                                                            "not_submitted",
                                                            "Only a submitted evaluation is reopened"))
                                                    : audit
                                                        .record(
                                                            db,
                                                            actor,
                                                            AuditEntry.of(
                                                                    "evaluation_reopened",
                                                                    "member_evaluation",
                                                                    memberEvaluationId)
                                                                .tender(ids[1])
                                                                .vendor(ids[2])
                                                                .note(reason)
                                                                .build())
                                                        .then(
                                                            tellOwner(
                                                                db,
                                                                ids[1],
                                                                ids[2],
                                                                memberEvaluationId))
                                                        .then(shell(db, memberEvaluationId)));
                                  }));
            });
  }

  public Mono<MemberEvaluation> exclude(
      DatabaseClient db, CurrentUser actor, UUID memberEvaluationId, ExcludeMember in) {
    String reason = in.getReason() == null ? "" : in.getReason().strip();
    return Settings.load(db)
        .flatMap(
            s -> {
              if (reason.length() < Math.max(10, s.minNoteLength())) {
                return Mono.<MemberEvaluation>error(
                    ApiException.invalid(
                        "reason", "too_short", "Give a reason of at least 10 characters"));
              }
              return evaluationOf(db, memberEvaluationId)
                  .flatMap(
                      ids ->
                          db.sql("SELECT app_is_chair_of(:t) AS chair")
                              .bind("t", ids[1])
                              .map((r, m) -> Boolean.TRUE.equals(r.get("chair", Boolean.class)))
                              .one()
                              .flatMap(
                                  chair -> {
                                    if (!chair) {
                                      return Mono.<MemberEvaluation>error(
                                          ApiException.forbidden(
                                              "forbidden", "Only the Chair excludes a member"));
                                    }
                                    return db.sql(
                                            "UPDATE member_evaluations SET excluded_at = now(),"
                                                + " excluded_by = :u, exclusion_reason = :r"
                                                + " WHERE id = :id AND excluded_at IS NULL")
                                        .bind("u", actor.id())
                                        .bind("r", reason)
                                        .bind("id", memberEvaluationId)
                                        .fetch()
                                        .rowsUpdated()
                                        .flatMap(
                                            n ->
                                                n == 0
                                                    ? Mono.<MemberEvaluation>error(
                                                        ApiException.conflict(
                                                            "already_excluded",
                                                            "This member is already excluded"))
                                                    : audit
                                                        .record(
                                                            db,
                                                            actor,
                                                            AuditEntry.of(
                                                                    "member_excluded",
                                                                    "member_evaluation",
                                                                    memberEvaluationId)
                                                                .tender(ids[1])
                                                                .vendor(ids[2])
                                                                .note(reason)
                                                                .build())
                                                        .then(advance(db, ids[1]))
                                                        .then(shell(db, memberEvaluationId)));
                                  }));
            });
  }

  /** The status of an evaluation without the owner's rows: all the Chair may read of it. */
  private Mono<MemberEvaluation> shell(DatabaseClient db, UUID memberEvaluationId) {
    return db.sql(
            "SELECT e.id, e.tender_vendor_id, e.status, e.first_submitted_at, e.submitted_at,"
                + " (e.excluded_at IS NOT NULL) AS excluded, v.name FROM member_evaluations e"
                + " JOIN tender_vendors tv ON tv.id = e.tender_vendor_id"
                + " JOIN vendors v ON v.id = tv.vendor_id WHERE e.id = :id")
        .bind("id", memberEvaluationId)
        .map(
            (r, m) ->
                new MemberEvaluation()
                    .id(r.get("id", UUID.class))
                    .tenderVendorId(r.get("tender_vendor_id", UUID.class))
                    .vendorName(r.get("name", String.class))
                    .status(MemberEvaluationStatus.fromValue(r.get("status", String.class)))
                    .firstSubmittedAt(r.get("first_submitted_at", OffsetDateTime.class))
                    .submittedAt(r.get("submitted_at", OffsetDateTime.class))
                    .excluded(Boolean.TRUE.equals(r.get("excluded", Boolean.class))))
        .one();
  }

  // ---- progress and cross-vendor
  // ---------------------------------------------------------------------------------------

  public Mono<EvaluationProgress> progress(DatabaseClient db, UUID tenderId) {
    return db.sql("SELECT 1 AS x FROM tenders WHERE id = :t AND app_is_member_of(:t)")
        .bind("t", tenderId)
        .map((r, m) -> 1)
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("tender")))
        .then(
            db.sql(
                    "SELECT tv.id AS tv, v.name, p.id AS pv, p.status AS pstatus, e.id AS me,"
                        + " coalesce(e.status, 'not_started') AS status, b.id AS baseline,"
                        + " (SELECT j.status FROM jobs j WHERE j.target_id = p.id"
                        + "    AND j.job_type = 'evaluation' ORDER BY j.queued_at DESC LIMIT 1) AS job_status,"
                        + " (SELECT count(*) FROM baseline_requirements br WHERE br.baseline_id = b.id) AS total,"
                        + " (SELECT count(*) FROM member_evaluation_rows r WHERE r.member_evaluation_id = e.id"
                        + "    AND r.review_state IN ('agreed', 'overridden')) AS reviewed,"
                        + " (SELECT count(*) FROM member_evaluation_rows r WHERE r.member_evaluation_id = e.id"
                        + "    AND r.outdated_at IS NOT NULL) AS outdated,"
                        + " (SELECT count(*) FROM member_evaluation_rows r WHERE r.member_evaluation_id = e.id"
                        + "    AND r.needs_clarification) AS flagged"
                        + " FROM tender_vendors tv JOIN vendors v ON v.id = tv.vendor_id"
                        + " JOIN LATERAL (SELECT id, status FROM proposal_versions x WHERE"
                        + "   x.tender_vendor_id = tv.id AND x.status IN ('accepted', 'evaluating',"
                        + "   'evaluated') ORDER BY x.version_no DESC LIMIT 1) p ON true"
                        + " LEFT JOIN baselines b ON b.tender_id = tv.tender_id AND b.status = 'confirmed'"
                        + " LEFT JOIN member_evaluations e ON e.tender_vendor_id = tv.id"
                        + "   AND e.user_id = app_user_id()"
                        + " WHERE tv.tender_id = :t AND tv.removed_at IS NULL ORDER BY v.name")
                .bind("t", tenderId)
                .map(
                    (r, m) ->
                        new EvaluationProgressItemsInner()
                            .tenderVendorId(r.get("tv", UUID.class))
                            .vendorName(r.get("name", String.class))
                            .status(MemberEvaluationStatus.fromValue(r.get("status", String.class)))
                            .reviewed(r.get("reviewed", Long.class).intValue())
                            .total(r.get("total", Long.class).intValue())
                            .outdated(r.get("outdated", Long.class).intValue())
                            .flagged(r.get("flagged", Long.class).intValue())
                            .jobStatus(
                                r.get("job_status", String.class) == null
                                    ? null
                                    : JobStatus.fromValue(r.get("job_status", String.class))))
                .all()
                .collectList()
                .map(
                    items -> {
                      boolean marked = false;
                      for (EvaluationProgressItemsInner i : items) {
                        boolean next =
                            !marked
                                && i.getStatus() != MemberEvaluationStatus.SUBMITTED
                                && i.getJobStatus() != JobStatus.RUNNING
                                && i.getJobStatus() != JobStatus.QUEUED;
                        i.setIsNext(next);
                        marked = marked || next;
                      }
                      return new EvaluationProgress().items(items);
                    }));
  }

  public Mono<CrossVendorView> crossVendor(DatabaseClient db, UUID requirementId) {
    return db.sql(
            "SELECT q.tender_id, q.display_no, q.text, q.importance, q.acceptance_criteria,"
                + " c.name AS category, app_is_member_of(q.tender_id) AS member"
                + " FROM requirements q LEFT JOIN requirement_categories c ON c.id = q.category_id"
                + " WHERE q.id = :r AND q.deleted_at IS NULL")
        .bind("r", requirementId)
        .map(
            (r, m) ->
                new Object[] {
                  r.get("tender_id", UUID.class),
                  new RequirementLite()
                      .id(requirementId)
                      .displayNo(r.get("display_no", String.class))
                      .text(r.get("text", String.class))
                      .category(
                          r.get("category", String.class) == null
                              ? ""
                              : r.get("category", String.class))
                      .importance(Importance.fromValue(r.get("importance", String.class)))
                      .acceptanceCriteria(r.get("acceptance_criteria", String.class)),
                  Boolean.TRUE.equals(r.get("member", Boolean.class))
                })
        .one()
        .filter(o -> (Boolean) o[2])
        .switchIfEmpty(Mono.error(ApiException.notFound("requirement")))
        .flatMap(
            o -> {
              UUID tender = (UUID) o[0];
              RequirementLite lite = (RequirementLite) o[1];
              return lowThreshold(db)
                  .flatMap(
                      th ->
                          Mono.zip(
                                  Mono.just(th),
                                  db.sql(
                                          "SELECT tv.id AS tv, v.name, g.id AS gid, g.outcome, g.grade AS ai_grade,"
                                              + " g.non_compliance_reason, g.rationale_summary, g.rationale,"
                                              + " g.missing_elements::text AS missing, g.confidence,"
                                              + " g.created_at AS ai_at, g.is_current, a.model_name, a.model_version,"
                                              + " r.review_state, r.member_grade, r.note, r.needs_clarification,"
                                              + " r.clarification_note, r.reviewed_at, r.outdated_at,"
                                              + " r.outdated_reason, r.row_version, r.id AS row_id,"
                                              + " q.id AS requirement_id, q.display_no, q.text,"
                                              + " c.name AS category_name, q.importance, q.acceptance_criteria"
                                              + " FROM tender_vendors tv JOIN vendors v ON v.id = tv.vendor_id"
                                              + " JOIN LATERAL (SELECT id FROM proposal_versions x WHERE"
                                              + "   x.tender_vendor_id = tv.id AND x.status IN ('accepted',"
                                              + "   'evaluating', 'evaluated') ORDER BY x.version_no DESC LIMIT 1) p ON true"
                                              + " JOIN requirements q ON q.id = :r"
                                              + " LEFT JOIN requirement_categories c ON c.id = q.category_id"
                                              + " LEFT JOIN ai_grades g ON g.proposal_version_id = p.id"
                                              + "   AND g.requirement_id = :r AND g.is_current"
                                              + " LEFT JOIN ai_runs a ON a.id = g.ai_run_id"
                                              + " LEFT JOIN member_evaluations e ON e.tender_vendor_id = tv.id"
                                              + "   AND e.user_id = app_user_id()"
                                              + " LEFT JOIN member_evaluation_rows r ON r.member_evaluation_id = e.id"
                                              + "   AND r.requirement_id = :r"
                                              + " WHERE tv.tender_id = :t AND tv.removed_at IS NULL ORDER BY v.name")
                                      .bind("t", tender)
                                      .bind("r", requirementId)
                                      .map(
                                          (r, m) ->
                                              Map.entry(
                                                  new Object[] {
                                                    r.get("tv", UUID.class),
                                                    r.get("name", String.class)
                                                  },
                                                  mapCross(r, th)))
                                      .all()
                                      .collectList(),
                                  db.sql(
                                          "SELECT (SELECT id FROM requirements WHERE tender_id = :t AND deleted_at IS NULL"
                                              + "   AND display_no < :n ORDER BY display_no DESC LIMIT 1) AS prev,"
                                              + " (SELECT id FROM requirements WHERE tender_id = :t AND deleted_at IS NULL"
                                              + "   AND display_no > :n ORDER BY display_no LIMIT 1) AS next")
                                      .bind("t", tender)
                                      .bind("n", lite.getDisplayNo())
                                      .map(
                                          (r, m) ->
                                              new UUID[] {
                                                r.get("prev", UUID.class), r.get("next", UUID.class)
                                              })
                                      .one())
                              .map(
                                  z -> {
                                    List<CrossVendorEntry> vendors = new ArrayList<>();
                                    for (var e : z.getT2()) {
                                      EvaluationRow row = e.getValue();
                                      vendors.add(
                                          new CrossVendorEntry()
                                              .tenderVendorId((UUID) e.getKey()[0])
                                              .vendorName((String) e.getKey()[1])
                                              .ai(row.getAi())
                                              .myDecision(row.getMember()));
                                    }
                                    return new CrossVendorView()
                                        .requirement(lite)
                                        .vendors(vendors)
                                        .previousRequirementId(z.getT3()[0])
                                        .nextRequirementId(z.getT3()[1]);
                                  }));
            });
  }

  private EvaluationRow mapCross(io.r2dbc.spi.Readable r, double lowConfidence) {
    return map(r, lowConfidence);
  }
}
