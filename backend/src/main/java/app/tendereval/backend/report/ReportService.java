package app.tendereval.backend.report;

import app.tendereval.backend.api.model.ActionState;
import app.tendereval.backend.api.model.AiGrade;
import app.tendereval.backend.api.model.AiOutcome;
import app.tendereval.backend.api.model.CategoryWeight;
import app.tendereval.backend.api.model.Citation;
import app.tendereval.backend.api.model.Clarification;
import app.tendereval.backend.api.model.Disagreement;
import app.tendereval.backend.api.model.DisagreementPage;
import app.tendereval.backend.api.model.FinalGradeInput;
import app.tendereval.backend.api.model.Grade;
import app.tendereval.backend.api.model.GradeBasis;
import app.tendereval.backend.api.model.GradeDecision;
import app.tendereval.backend.api.model.Importance;
import app.tendereval.backend.api.model.Matrix;
import app.tendereval.backend.api.model.MatrixCell;
import app.tendereval.backend.api.model.MatrixCellDetail;
import app.tendereval.backend.api.model.MatrixRow;
import app.tendereval.backend.api.model.MemberEvaluationStatus;
import app.tendereval.backend.api.model.MemberGrade;
import app.tendereval.backend.api.model.NonComplianceReason;
import app.tendereval.backend.api.model.ProgressMatrix;
import app.tendereval.backend.api.model.ReportAppendix;
import app.tendereval.backend.api.model.ReportHeader;
import app.tendereval.backend.api.model.ReportOverview;
import app.tendereval.backend.api.model.ReportStatus;
import app.tendereval.backend.api.model.ReportVersion;
import app.tendereval.backend.api.model.RequirementLite;
import app.tendereval.backend.api.model.RowReviewState;
import app.tendereval.backend.api.model.ScoringModel;
import app.tendereval.backend.api.model.ScreeningOutcome;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.api.model.VendorOutcome;
import app.tendereval.backend.api.model.VendorResult;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.common.Actions;
import app.tendereval.backend.common.Cursor;
import app.tendereval.backend.common.ETags;
import app.tendereval.backend.common.Settings;
import app.tendereval.backend.report.ScoringService.Points;
import app.tendereval.backend.report.ScoringService.Ranked;
import app.tendereval.backend.report.ScoringService.Row;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The consolidated report (UX-RPT-01..13, UX-SCO-03). The database decides who may read what (V24:
 * per vendor only once every member has submitted or been excluded; members and the Observer read
 * finalized versions only). This service shapes the answers, derives "all agree" from the members'
 * own rows, applies the narrower rules of the report API (P85), computes the score at finalization
 * (P83) and writes the audit trail.
 */
@Service
public class ReportService {

  private static final UUID NONE = new UUID(0, 0);
  private static final String ACTIVE_PROPOSAL =
      " JOIN LATERAL (SELECT x.id, x.status FROM proposal_versions x WHERE x.tender_vendor_id = tv.id"
          + "   AND x.status IN ('accepted', 'evaluating', 'evaluated')"
          + "   ORDER BY x.version_no DESC LIMIT 1) p ON true";

  private final AuditService audit;
  private final app.tendereval.backend.notifications.NotificationService notifications;

  public ReportService(
      AuditService audit, app.tendereval.backend.notifications.NotificationService notifications) {
    this.audit = audit;
    this.notifications = notifications;
  }

  // ---- who and which version ---------------------------------------------------------------

  record Access(
      UUID tenderId, String name, String stage, boolean chair, String role, boolean member) {
    boolean privileged() {
      return chair || "admin".equals(role);
    }
  }

  /** The version a read answers: a stored row, or the one the Chair has not yet opened. */
  record Ver(
      UUID id,
      int no,
      String status,
      UUID baselineId,
      int baselineNo,
      UUID scoringModelId,
      OffsetDateTime finalizedAt,
      UUID finalizedBy,
      String reopenReason,
      boolean stored) {
    boolean live() {
      return "in_progress".equals(status);
    }
  }

  Mono<Access> access(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT t.name, t.status, app_is_chair_of(t.id) AS chair,"
                + " app_is_member_of(t.id) AS member, app_role() AS role FROM tenders t WHERE t.id = :t")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new Access(
                    tenderId,
                    r.get("name", String.class),
                    r.get("status", String.class),
                    Boolean.TRUE.equals(r.get("chair", Boolean.class)),
                    r.get("role", String.class),
                    Boolean.TRUE.equals(r.get("member", Boolean.class))))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("tender")));
  }

  private Mono<List<Ver>> versions(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT id, version_no, status, baseline_id, baseline_no AS bno, scoring_model_id,"
                + " finalized_at, finalized_by, reopen_reason FROM app_report_versions(:t)"
                + " ORDER BY version_no DESC")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new Ver(
                    r.get("id", UUID.class),
                    r.get("version_no", Integer.class),
                    r.get("status", String.class),
                    r.get("baseline_id", UUID.class),
                    r.get("bno", Integer.class),
                    r.get("scoring_model_id", UUID.class),
                    r.get("finalized_at", OffsetDateTime.class),
                    r.get("finalized_by", UUID.class),
                    r.get("reopen_reason", String.class),
                    true))
        .all()
        .collectList();
  }

  /** The confirmed baseline and its scoring model, or a 409 when there is none. */
  private Mono<Ver> openingVersion(DatabaseClient db, UUID tenderId, int no) {
    return db.sql(
            "SELECT b.id, b.version_no, sm.id AS sm FROM baselines b"
                + " JOIN scoring_models sm ON sm.baseline_id = b.id"
                + " WHERE b.tender_id = :t AND b.status = 'confirmed'")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new Ver(
                    null,
                    no,
                    "in_progress",
                    r.get("id", UUID.class),
                    r.get("version_no", Integer.class),
                    r.get("sm", UUID.class),
                    null,
                    null,
                    null,
                    false))
        .one()
        .switchIfEmpty(
            Mono.error(
                ApiException.conflict(
                    "no_confirmed_baseline", "The tender has no confirmed baseline")));
  }

  Mono<Ver> resolve(DatabaseClient db, Access a, Integer requested) {
    return versions(db, a.tenderId())
        .flatMap(
            all -> {
              if (requested != null) {
                return all.stream()
                    .filter(v -> v.no() == requested && (a.privileged() || !v.live()))
                    .findFirst()
                    .map(Mono::just)
                    .orElseGet(
                        () ->
                            // the version the Chair has not opened yet
                            a.privileged()
                                    && ("evaluation".equals(a.stage())
                                        || "consolidation".equals(a.stage()))
                                    && requested == (all.isEmpty() ? 1 : all.get(0).no() + 1)
                                    && all.stream().noneMatch(Ver::live)
                                ? openingVersion(db, a.tenderId(), requested)
                                : Mono.<Ver>error(ApiException.notFound("report version")));
              }
              if (a.privileged()) {
                var open = all.stream().filter(Ver::live).findFirst();
                if (open.isPresent()) {
                  return Mono.just(open.get());
                }
                boolean collecting =
                    "evaluation".equals(a.stage()) || "consolidation".equals(a.stage());
                if (collecting) {
                  int next = all.isEmpty() ? 1 : all.get(0).no() + 1;
                  return openingVersion(db, a.tenderId(), next);
                }
              }
              return all.stream()
                  .filter(v -> !v.live())
                  .findFirst()
                  .map(Mono::just)
                  .orElseGet(
                      () -> Mono.error(ApiException.notFound("report: it is not finalized yet")));
            });
  }

  // ---- cells -------------------------------------------------------------------------------

  private record Copy(UUID userId, String name, String grade, String note, String state) {}

  /** One requirement of one vendor, with the members' decisions and the Chair's. */
  private static final class Cell {
    UUID requirementId;
    String displayNo;
    String text;
    String category;
    String importance;
    String criteria;
    UUID tv;
    String vendor;
    UUID aiGradeId;
    String aiGrade;
    String finalGrade;
    String basis;
    String justification;
    UUID decidedBy;
    String decidedByName;
    OffsetDateTime decidedAt;
    Boolean storedDisagreement;
    boolean complete = true;
    final List<Copy> members = new ArrayList<>();

    boolean disagreement() {
      if (storedDisagreement != null) {
        return storedDisagreement;
      }
      return members.stream().map(Copy::grade).filter(g -> g != null).distinct().count() > 1;
    }

    /** All members graded and every grade is the same. */
    String agreed() {
      if (members.isEmpty() || members.stream().anyMatch(m -> m.grade() == null)) {
        return null;
      }
      Set<String> g = new LinkedHashSet<>();
      members.forEach(m -> g.add(m.grade()));
      return g.size() == 1 ? g.iterator().next() : null;
    }

    String grade() {
      if (finalGrade != null) {
        return finalGrade;
      }
      return agreed();
    }

    String gradeBasis() {
      if (finalGrade != null) {
        return basis;
      }
      return agreed() != null ? "all_agree" : null;
    }
  }

  private static final String CELL_COLUMNS =
      "br.requirement_id AS rq, br.display_no, br.text, br.category_name, br.importance,"
          + " br.acceptance_criteria AS criteria, tv.id AS tv, v.name AS vendor,";
  private static final String MEMBER_COLUMNS = " mu.id AS m_user, mu.display_name AS m_name";

  private static Cell cell(io.r2dbc.spi.Readable r) {
    Cell c = new Cell();
    c.requirementId = r.get("rq", UUID.class);
    c.displayNo = r.get("display_no", String.class);
    c.text = r.get("text", String.class);
    c.category = r.get("category_name", String.class);
    c.importance = r.get("importance", String.class);
    c.criteria = r.get("criteria", String.class);
    c.tv = r.get("tv", UUID.class);
    c.vendor = r.get("vendor", String.class);
    c.aiGradeId = r.get("ai_grade_id", UUID.class);
    c.aiGrade = r.get("ai_grade", String.class);
    c.finalGrade = r.get("fin_grade", String.class);
    c.basis = r.get("basis", String.class);
    c.justification = r.get("justification", String.class);
    c.decidedBy = r.get("decided_by", UUID.class);
    c.decidedByName = r.get("decider", String.class);
    c.decidedAt = r.get("decided_at", OffsetDateTime.class);
    c.storedDisagreement = r.get("has_dis", Boolean.class);
    Boolean complete = r.get("complete", Boolean.class);
    c.complete = complete == null || complete;
    return c;
  }

  /** Cells of a version: live from the members' rows, or the stored copies of a finalized one. */
  private Mono<List<Cell>> cells(
      DatabaseClient db, Access a, Ver v, UUID tvFilter, UUID requirementFilter) {
    String filters =
        (tvFilter == null ? "" : " AND tv.id = :tvf")
            + (requirementFilter == null ? "" : " AND br.requirement_id = :rqf");
    String sql;
    if (v.live()) {
      sql =
          "SELECT "
              + CELL_COLUMNS
              + " g.id AS ai_grade_id, g.grade AS ai_grade, rg.final_grade AS fin_grade, rg.basis,"
              + " rg.justification, rg.decided_by, du.display_name AS decider, rg.decided_at,"
              + " CASE WHEN rg.id IS NULL THEN NULL ELSE rg.has_disagreement END AS has_dis,"
              + " (app_all_submitted_or_excluded(tv.id)"
              + "  AND EXISTS (SELECT 1 FROM member_evaluations ae WHERE ae.tender_vendor_id = tv.id AND ae.excluded_at IS NULL AND ae.status = 'submitted')) AS complete,"
              + " e.user_id AS m_user, mu.display_name AS m_name, r.member_grade AS m_grade,"
              + " r.note AS m_note, r.review_state AS m_state"
              + " FROM tender_vendors tv JOIN vendors v ON v.id = tv.vendor_id"
              + ACTIVE_PROPOSAL
              + " JOIN baseline_requirements br ON br.baseline_id = :b"
              + " LEFT JOIN ai_grades g ON g.proposal_version_id = p.id"
              + "   AND g.requirement_id = br.requirement_id AND g.is_current"
              + " LEFT JOIN report_grades rg ON rg.report_version_id = :rv"
              + "   AND rg.tender_vendor_id = tv.id AND rg.requirement_id = br.requirement_id"
              + " LEFT JOIN users du ON du.id = rg.decided_by"
              + " LEFT JOIN member_evaluations e ON e.tender_vendor_id = tv.id AND e.excluded_at IS NULL"
              + " LEFT JOIN users mu ON mu.id = e.user_id"
              + " LEFT JOIN member_evaluation_rows r ON r.member_evaluation_id = e.id"
              + "   AND r.requirement_id = br.requirement_id"
              + " WHERE tv.tender_id = :t AND tv.removed_at IS NULL"
              + filters
              + " ORDER BY br.display_no, v.name, mu.display_name";
    } else {
      sql =
          "SELECT "
              + CELL_COLUMNS
              + " rg.ai_grade_id, rg.ai_grade, rg.final_grade AS fin_grade, rg.basis,"
              + " rg.justification, rg.decided_by, du.display_name AS decider, rg.decided_at,"
              + " rg.has_disagreement AS has_dis, true AS complete,"
              + " mg.user_id AS m_user, mu.display_name AS m_name, mg.member_grade AS m_grade,"
              + " mg.note AS m_note, mg.review_state AS m_state"
              + " FROM report_grades rg"
              + " JOIN tender_vendors tv ON tv.id = rg.tender_vendor_id"
              + " JOIN app_report_vendors(:rv) v ON v.tender_vendor_id = tv.id"
              + " JOIN app_report_requirements(:rv) br ON br.requirement_id = rg.requirement_id"
              + " LEFT JOIN users du ON du.id = rg.decided_by"
              + " LEFT JOIN report_member_grades mg ON mg.report_grade_id = rg.id"
              + " LEFT JOIN users mu ON mu.id = mg.user_id"
              + " WHERE rg.report_version_id = :rv"
              + filters
              + " ORDER BY br.display_no, v.name, mu.display_name";
    }
    DatabaseClient.GenericExecuteSpec spec = db.sql(sql).bind("rv", v.id() == null ? NONE : v.id());
    if (v.live()) {
      spec = spec.bind("t", a.tenderId()).bind("b", v.baselineId());
    }
    if (tvFilter != null) {
      spec = spec.bind("tvf", tvFilter);
    }
    if (requirementFilter != null) {
      spec = spec.bind("rqf", requirementFilter);
    }
    return spec.map(
            (r, m) -> {
              Cell c = cell(r);
              // The narrower rule of the report (P85): nothing of a vendor's members until all
              // of them have submitted, whatever the database would let the Chair read.
              UUID user = c.complete ? r.get("m_user", UUID.class) : null;
              Copy copy =
                  user == null
                      ? null
                      : new Copy(
                          user,
                          r.get("m_name", String.class),
                          r.get("m_grade", String.class),
                          r.get("m_note", String.class),
                          r.get("m_state", String.class));
              return Map.entry(c, java.util.Optional.ofNullable(copy));
            })
        .all()
        .collectList()
        .map(
            list -> {
              Map<String, Cell> byKey = new LinkedHashMap<>();
              for (var e : list) {
                Cell c = e.getKey();
                Cell kept = byKey.computeIfAbsent(c.requirementId + ":" + c.tv, k -> c);
                e.getValue().ifPresent(kept.members::add);
              }
              return new ArrayList<>(byKey.values());
            });
  }

  // ---- vendors and results -----------------------------------------------------------------

  private record Vendor(UUID tv, String name, String proposalStatus, boolean complete) {}

  /** Vendors with a proposal in or past evaluation, and whether every member has finished. */
  private Mono<List<Vendor>> vendors(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT tv.id AS tv, v.name, p.status, (app_all_submitted_or_excluded(tv.id) AND EXISTS (SELECT 1 FROM member_evaluations ae WHERE ae.tender_vendor_id = tv.id AND ae.excluded_at IS NULL AND ae.status = 'submitted')) AS done"
                + " FROM tender_vendors tv JOIN vendors v ON v.id = tv.vendor_id"
                + ACTIVE_PROPOSAL
                + " WHERE tv.tender_id = :t AND tv.removed_at IS NULL ORDER BY v.name")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new Vendor(
                    r.get("tv", UUID.class),
                    r.get("name", String.class),
                    r.get("status", String.class),
                    Boolean.TRUE.equals(r.get("done", Boolean.class))))
        .all()
        .collectList();
  }

  /** The vendors of a version: live ones, or those a finalized version holds a result for. */
  private Mono<List<Vendor>> vendorsOf(DatabaseClient db, Access a, Ver v) {
    if (v.live()) {
      return vendors(db, a.tenderId());
    }
    return db.sql(
            "SELECT r.tender_vendor_id AS tv, vd.name FROM report_vendor_results r"
                + " JOIN app_report_vendors(:rv) vd ON vd.tender_vendor_id = r.tender_vendor_id"
                + " WHERE r.report_version_id = :rv AND r.outcome = 'evaluated' ORDER BY vd.name")
        .bind("rv", v.id())
        .map(
            (r, m) ->
                new Vendor(r.get("tv", UUID.class), r.get("name", String.class), "evaluated", true))
        .all()
        .collectList();
  }

  private record Model(Points points, Map<String, BigDecimal> weights, ScoringModel api) {}

  /** The model of a stored version through the read function (the Observer cannot read it). */
  private Mono<Model> model(DatabaseClient db, Ver v) {
    if (v.id() == null) {
      return modelDirect(db, v.scoringModelId());
    }
    return db.sql(
            "SELECT points_fully, points_partially, points_non, baseline_id, row_version,"
                + " weights::text AS weights FROM app_report_scoring(:rv)")
        .bind("rv", v.id())
        .map(
            (r, m) -> {
              Points p =
                  new Points(
                      r.get("points_fully", BigDecimal.class),
                      r.get("points_partially", BigDecimal.class),
                      r.get("points_non", BigDecimal.class));
              List<CategoryWeight> weights = new ArrayList<>();
              Map<String, BigDecimal> byName = new LinkedHashMap<>();
              List<?> raw = json.readValue(r.get("weights", String.class), List.class);
              for (Object o : raw) {
                Map<?, ?> w = (Map<?, ?>) o;
                BigDecimal weight = new BigDecimal(String.valueOf(w.get("weight")));
                weights.add(
                    new CategoryWeight()
                        .categoryId(UUID.fromString(String.valueOf(w.get("category_id"))))
                        .weight(weight));
                byName.put(String.valueOf(w.get("name")), weight);
              }
              ScoringModel api =
                  new ScoringModel()
                      .pointsFully(p.fully())
                      .pointsPartially(p.partially())
                      .pointsNon(p.non())
                      .mandatoryRule(ScoringModel.MandatoryRuleEnum.FLAG_ONLY)
                      .weights(weights)
                      .locked(true)
                      .baselineId(r.get("baseline_id", UUID.class))
                      .rowVersion(r.get("row_version", Integer.class));
              return new Model(p, byName, api);
            })
        .one();
  }

  private Mono<Model> modelDirect(DatabaseClient db, UUID scoringModelId) {
    return db.sql(
            "SELECT points_fully, points_partially, points_non, mandatory_rule, baseline_id,"
                + " row_version FROM scoring_models WHERE id = :id")
        .bind("id", scoringModelId)
        .map(
            (r, m) ->
                new Object[] {
                  new Points(
                      r.get("points_fully", BigDecimal.class),
                      r.get("points_partially", BigDecimal.class),
                      r.get("points_non", BigDecimal.class)),
                  r.get("baseline_id", UUID.class),
                  r.get("row_version", Integer.class)
                })
        .one()
        .flatMap(
            o ->
                db.sql(
                        "SELECT c.id, c.name, w.weight FROM scoring_category_weights w"
                            + " JOIN requirement_categories c ON c.id = w.category_id"
                            + " WHERE w.scoring_model_id = :id ORDER BY c.sort_order, c.name")
                    .bind("id", scoringModelId)
                    .map(
                        (r, m) ->
                            Map.entry(
                                r.get("name", String.class),
                                new CategoryWeight()
                                    .categoryId(r.get("id", UUID.class))
                                    .weight(r.get("weight", BigDecimal.class))))
                    .all()
                    .collectList()
                    .map(
                        weights -> {
                          Points p = (Points) o[0];
                          Map<String, BigDecimal> byName = new LinkedHashMap<>();
                          weights.forEach(w -> byName.put(w.getKey(), w.getValue().getWeight()));
                          ScoringModel api =
                              new ScoringModel()
                                  .pointsFully(p.fully())
                                  .pointsPartially(p.partially())
                                  .pointsNon(p.non())
                                  .mandatoryRule(ScoringModel.MandatoryRuleEnum.FLAG_ONLY)
                                  .weights(weights.stream().map(Map.Entry::getValue).toList())
                                  .locked(true)
                                  .baselineId((UUID) o[1])
                                  .rowVersion((Integer) o[2]);
                          return new Model(p, byName, api);
                        }));
  }

  /** What one vendor scored, before it is stored or shown. */
  private record Scored(
      Vendor vendor, ScoringService.Result result, Integer rank, List<UserRef> awaiting) {}

  private static List<Row> rowsOf(List<Cell> cells, UUID tv) {
    List<Row> rows = new ArrayList<>();
    for (Cell c : cells) {
      if (!c.tv.equals(tv)) {
        continue;
      }
      int overridden = (int) c.members.stream().filter(m -> "overridden".equals(m.state())).count();
      rows.add(
          new Row(
              c.category,
              c.importance,
              c.grade(),
              !c.disagreement() && c.gradeBasis() != null,
              overridden,
              c.members.size()));
    }
    return rows;
  }

  private Mono<List<Scored>> scoreVendors(
      DatabaseClient db, Access a, Ver v, List<Vendor> vendors, List<Cell> cells, Model model) {
    return awaiting(db, a.tenderId())
        .map(
            waiting -> {
              List<Scored> scored = new ArrayList<>();
              for (Vendor vd : vendors) {
                if (!"evaluated".equals(vd.proposalStatus()) && !vd.complete()) {
                  scored.add(new Scored(vd, null, null, waiting.getOrDefault(vd.tv(), List.of())));
                  continue;
                }
                if (!vd.complete()) {
                  scored.add(new Scored(vd, null, null, waiting.getOrDefault(vd.tv(), List.of())));
                  continue;
                }
                scored.add(
                    new Scored(
                        vd,
                        ScoringService.score(
                            rowsOf(cells, vd.tv()), model.points(), model.weights()),
                        null,
                        List.of()));
              }
              Map<UUID, Integer> ranks =
                  ScoringService.rank(
                      scored.stream()
                          .filter(s -> s.result() != null)
                          .map(
                              s ->
                                  new Ranked(
                                      s.vendor().tv(),
                                      s.vendor().name(),
                                      s.result().finalScore(),
                                      s.result().mandatoryFailures()))
                          .toList());
              List<Scored> out = new ArrayList<>();
              for (Scored s : scored) {
                out.add(
                    new Scored(s.vendor(), s.result(), ranks.get(s.vendor().tv()), s.awaiting()));
              }
              out.sort(
                  Comparator.comparing(
                          (Scored s) -> s.rank() == null ? Integer.MAX_VALUE : s.rank())
                      .thenComparingInt(
                          s -> s.result() == null ? 0 : s.result().mandatoryFailures())
                      .thenComparing(s -> s.vendor().name()));
              return out;
            });
  }

  /** Members who have not submitted, per vendor (names only: no grades, UX-RPT-02). */
  private Mono<Map<UUID, List<UserRef>>> awaiting(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT tv.id AS tv, u.id, u.display_name FROM tender_vendors tv"
                + " JOIN tender_members m ON m.tender_id = tv.tender_id AND m.status = 'active'"
                + " JOIN users u ON u.id = m.user_id"
                + " LEFT JOIN member_evaluations e ON e.tender_vendor_id = tv.id AND e.user_id = u.id"
                + " WHERE tv.tender_id = :t AND tv.removed_at IS NULL"
                + " AND (e.id IS NULL OR (e.status <> 'submitted' AND e.excluded_at IS NULL))"
                + " ORDER BY u.display_name")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                Map.entry(
                    r.get("tv", UUID.class),
                    new UserRef()
                        .id(r.get("id", UUID.class))
                        .displayName(r.get("display_name", String.class))))
        .all()
        .collectList()
        .map(
            l -> {
              Map<UUID, List<UserRef>> out = new LinkedHashMap<>();
              l.forEach(
                  e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
              return out;
            });
  }

  // ---- reads -------------------------------------------------------------------------------

  private static UserRef ref(UUID id, String name) {
    return id == null ? null : new UserRef().id(id).displayName(name);
  }

  private Mono<UserRef> userRef(DatabaseClient db, UUID id) {
    if (id == null) {
      return Mono.empty();
    }
    return db.sql("SELECT id, display_name FROM users WHERE id = :id")
        .bind("id", id)
        .map((r, m) -> ref(r.get("id", UUID.class), r.get("display_name", String.class)))
        .one();
  }

  private ReportVersion versionDto(Ver v, UserRef by) {
    return new ReportVersion()
        .versionNo(v.no())
        .status(ReportStatus.fromValue(v.status()))
        .baselineVersion(v.baselineNo())
        .finalizedBy(by)
        .finalizedAt(v.finalizedAt())
        .reopenReason(v.reopenReason());
  }

  public Mono<List<ReportVersion>> listVersions(DatabaseClient db, UUID tenderId) {
    return access(db, tenderId)
        .flatMap(
            a ->
                versions(db, tenderId)
                    .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                    .filter(v -> a.privileged() || !v.live())
                    .concatMap(
                        v ->
                            userRef(db, v.finalizedBy())
                                .map(u -> versionDto(v, u))
                                .defaultIfEmpty(versionDto(v, null)))
                    .collectList());
  }

  public Mono<ReportHeader> header(DatabaseClient db, UUID tenderId, Integer version) {
    return access(db, tenderId)
        .flatMap(
            a ->
                resolve(db, a, version)
                    .flatMap(
                        v ->
                            Mono.zip(
                                    model(db, v),
                                    committee(db, tenderId),
                                    userRef(db, v.finalizedBy())
                                        .map(java.util.Optional::of)
                                        .defaultIfEmpty(java.util.Optional.empty()),
                                    cells(db, a, v, null, null),
                                    models(db, v))
                                .map(
                                    z -> {
                                      long dis =
                                          z.getT4().stream().filter(Cell::disagreement).count();
                                      long open =
                                          z.getT4().stream()
                                              .filter(c -> c.disagreement() && c.finalGrade == null)
                                              .count();
                                      boolean chair = a.chair();
                                      Actions act = Actions.builder();
                                      act.when(
                                          "finalize",
                                          chair
                                              && v.live()
                                              && "consolidation".equals(a.stage())
                                              && open == 0,
                                          !chair
                                              ? "Only the Chair finalizes"
                                              : open > 0
                                                  ? open + " disagreements still need a final grade"
                                                  : "The tender is not in consolidation");
                                      act.when(
                                          "reopen",
                                          chair
                                              && "finalized".equals(v.status())
                                              && "finalized".equals(a.stage()),
                                          chair
                                              ? "Only the latest finalized version is reopened"
                                              : "Only the Chair reopens");
                                      act.when(
                                          "export",
                                          a.privileged(),
                                          "Only the Chair and the administrator export");
                                      Map<String, ActionState> actions = act.build();
                                      return new ReportHeader()
                                          .tenderId(tenderId)
                                          .tenderName(a.name())
                                          .versionNo(v.no())
                                          .status(ReportStatus.fromValue(v.status()))
                                          .baselineVersion(v.baselineNo())
                                          .scoringModel(z.getT1().api())
                                          .committee(z.getT2())
                                          .finalizedAt(v.finalizedAt())
                                          .finalizedBy(z.getT3().orElse(null))
                                          .disagreementCount((int) (v.live() ? open : dis))
                                          .aiModelVersions(z.getT5())
                                          .actions(actions);
                                    })));
  }

  private Mono<List<String>> models(DatabaseClient db, Ver v) {
    if (v.id() != null && !v.live()) {
      return db.sql("SELECT model FROM app_report_models(:rv) ORDER BY 1")
          .bind("rv", v.id())
          .map((r, m) -> r.get("model", String.class))
          .all()
          .collectList();
    }
    return db.sql(
            "SELECT DISTINCT a.model_name || coalesce(' ' || a.model_version, '') AS m"
                + " FROM ai_grades g JOIN ai_runs a ON a.id = g.ai_run_id"
                + " WHERE g.id IN (SELECT ai_grade_id FROM report_grades WHERE report_version_id = :rv)"
                + " OR (:live AND g.is_current AND g.baseline_id = :b) ORDER BY 1")
        .bind("rv", v.id() == null ? NONE : v.id())
        .bind("live", v.live())
        .bind("b", v.baselineId())
        .map((r, m) -> r.get("m", String.class))
        .all()
        .collectList();
  }

  private Mono<List<UserRef>> committee(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT u.id, u.display_name FROM tender_members m JOIN users u ON u.id = m.user_id"
                + " WHERE m.tender_id = :t AND m.status = 'active' ORDER BY u.display_name")
        .bind("t", tenderId)
        .map((r, m) -> ref(r.get("id", UUID.class), r.get("display_name", String.class)))
        .all()
        .collectList();
  }

  public Mono<ProgressMatrix> progress(DatabaseClient db, UUID tenderId) {
    return access(db, tenderId)
        .flatMap(
            a -> {
              if (!a.privileged()) {
                return Mono.<ProgressMatrix>error(
                    ApiException.forbidden(
                        "forbidden", "Only the Chair and the administrator see the progress"));
              }
              return Mono.zip(
                      vendors(db, tenderId),
                      committee(db, tenderId),
                      db.sql(
                              "SELECT tv.id AS tv, m.user_id, e.id AS me, coalesce(e.status, 'not_started') AS status,"
                                  + " (e.excluded_at IS NOT NULL) AS excluded"
                                  + " FROM tender_vendors tv"
                                  + ACTIVE_PROPOSAL
                                  + " JOIN tender_members m ON m.tender_id = tv.tender_id"
                                  + "   AND m.status = 'active'"
                                  + " LEFT JOIN member_evaluations e ON e.tender_vendor_id = tv.id"
                                  + "   AND e.user_id = m.user_id"
                                  + " WHERE tv.tender_id = :t AND tv.removed_at IS NULL")
                          .bind("t", tenderId)
                          .map(
                              (r, m) -> {
                                Map<String, Object> cell = new LinkedHashMap<>();
                                cell.put("tv", r.get("tv", UUID.class));
                                cell.put("user", r.get("user_id", UUID.class));
                                cell.put("me", r.get("me", UUID.class));
                                cell.put("status", r.get("status", String.class));
                                cell.put(
                                    "excluded",
                                    Boolean.TRUE.equals(r.get("excluded", Boolean.class)));
                                return cell;
                              })
                          .all()
                          .collectList())
                  .map(
                      z -> {
                        ProgressMatrix pm = new ProgressMatrix();
                        z.getT1()
                            .forEach(
                                v ->
                                    pm.addVendorsItem(
                                        new app.tendereval.backend.api.model
                                                .ProgressMatrixVendorsInner()
                                            .tenderVendorId(v.tv())
                                            .name(v.name())));
                        pm.members(z.getT2());
                        for (Map<String, Object> c : z.getT3()) {
                          pm.addCellsItem(
                              new app.tendereval.backend.api.model.ProgressMatrixCellsInner()
                                  .tenderVendorId((UUID) c.get("tv"))
                                  .userId((UUID) c.get("user"))
                                  .status(
                                      MemberEvaluationStatus.fromValue((String) c.get("status")))
                                  .excluded((Boolean) c.get("excluded"))
                                  .memberEvaluationId((UUID) c.get("me")));
                        }
                        return pm;
                      });
            });
  }

  public Mono<ReportOverview> overview(DatabaseClient db, UUID tenderId, Integer version) {
    return access(db, tenderId)
        .flatMap(a -> resolve(db, a, version).flatMap(v -> overviewOf(db, a, v)));
  }

  private Mono<ReportOverview> overviewOf(DatabaseClient db, Access a, Ver v) {
    if (!v.live()) {
      return db.sql(
              "SELECT r.*, vd.name FROM report_vendor_results r"
                  + " JOIN app_report_vendors(:rv) vd ON vd.tender_vendor_id = r.tender_vendor_id"
                  + " WHERE r.report_version_id = :rv"
                  + " ORDER BY r.rank NULLS LAST, r.mandatory_failures, vd.name")
          .bind("rv", v.id())
          .map(
              (r, m) -> {
                boolean evaluated = "evaluated".equals(r.get("outcome", String.class));
                Map<String, Object> counts = new LinkedHashMap<>();
                counts.put("fully_compliant", r.get("count_fully", Integer.class));
                counts.put("partially_compliant", r.get("count_partially", Integer.class));
                counts.put("non_compliant", r.get("count_non", Integer.class));
                return new VendorResult()
                    .tenderVendorId(r.get("tender_vendor_id", UUID.class))
                    .vendorName(r.get("name", String.class))
                    .available(true)
                    .outcome(VendorOutcome.fromValue(r.get("outcome", String.class)))
                    .rank(r.get("rank", Integer.class))
                    .finalScore(r.get("final_score", BigDecimal.class))
                    .compliancePercent(r.get("compliance_percent", BigDecimal.class))
                    .mandatoryFailures(
                        evaluated ? r.get("mandatory_failures", Integer.class) : null)
                    .counts(evaluated ? counts : null)
                    .agreementLevel(r.get("agreement_level", BigDecimal.class))
                    .failsMandatory(evaluated && r.get("mandatory_failures", Integer.class) > 0)
                    .reason(r.get("reason", String.class))
                    .awaiting(new ArrayList<>());
              })
          .all()
          .collectList()
          .map(l -> new ReportOverview().vendors(l));
    }
    return Mono.zip(vendors(db, a.tenderId()), model(db, v))
        .flatMap(
            z ->
                cells(db, a, v, null, null)
                    .flatMap(
                        cells ->
                            scoreVendors(db, a, v, z.getT1(), cells, z.getT2())
                                .map(
                                    scored -> {
                                      List<VendorResult> out = new ArrayList<>();
                                      for (Scored s : scored) {
                                        out.add(vendorResult(s));
                                      }
                                      return out;
                                    })))
        .zipWith(screeningOutcomes(db, a.tenderId()))
        .map(
            z -> {
              List<VendorResult> all = new ArrayList<>(z.getT1());
              for (ScreeningOutcome o : z.getT2()) {
                all.add(
                    new VendorResult()
                        .tenderVendorId(o.getTenderVendorId())
                        .vendorName(o.getVendorName())
                        .available(true)
                        .outcome(VendorOutcome.REJECTED_AT_SCREENING)
                        .reason(o.getReason()));
              }
              return new ReportOverview().vendors(all);
            });
  }

  private static VendorResult vendorResult(Scored s) {
    VendorResult out =
        new VendorResult()
            .tenderVendorId(s.vendor().tv())
            .vendorName(s.vendor().name())
            .outcome(VendorOutcome.EVALUATED)
            .available(s.result() != null)
            .awaiting(new ArrayList<>(s.awaiting()));
    if (s.result() != null) {
      var r = s.result();
      Map<String, Object> counts = new LinkedHashMap<>();
      counts.put("fully_compliant", r.fully());
      counts.put("partially_compliant", r.partially());
      counts.put("non_compliant", r.non());
      counts.put("ungraded", r.ungraded());
      out.rank(s.rank())
          .finalScore(r.finalScore())
          .compliancePercent(r.compliancePercent())
          .mandatoryFailures(r.mandatoryFailures())
          .counts(counts)
          .agreementLevel(r.agreementLevel())
          .failsMandatory(r.mandatoryFailures() > 0);
    } else {
      out.failsMandatory(false);
    }
    return out;
  }

  private Mono<List<ScreeningOutcome>> screeningOutcomes(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT tv.id AS tv, vd.name, d.reason, d.decided_by, u.display_name, d.decided_at"
                + " FROM tender_vendors tv JOIN vendors vd ON vd.id = tv.vendor_id"
                + " JOIN LATERAL (SELECT x.id FROM proposal_versions x WHERE x.tender_vendor_id = tv.id"
                + "   AND x.status <> 'superseded' ORDER BY x.version_no DESC LIMIT 1) p ON true"
                + " JOIN proposal_versions pv ON pv.id = p.id AND pv.status = 'rejected'"
                + " JOIN LATERAL (SELECT * FROM screening_decisions s WHERE s.proposal_version_id = pv.id"
                + "   AND s.decision = 'reject' ORDER BY s.decided_at DESC LIMIT 1) d ON true"
                + " JOIN users u ON u.id = d.decided_by"
                + " WHERE tv.tender_id = :t AND tv.removed_at IS NULL ORDER BY vd.name")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new ScreeningOutcome()
                    .tenderVendorId(r.get("tv", UUID.class))
                    .vendorName(r.get("name", String.class))
                    .reason(r.get("reason", String.class))
                    .decidedBy(
                        ref(r.get("decided_by", UUID.class), r.get("display_name", String.class)))
                    .decidedAt(r.get("decided_at", OffsetDateTime.class)))
        .all()
        .collectList();
  }

  public Mono<List<ScreeningOutcome>> screening(DatabaseClient db, UUID tenderId, Integer version) {
    return access(db, tenderId)
        .flatMap(
            a ->
                resolve(db, a, version)
                    .flatMap(
                        v ->
                            v.live()
                                ? screeningOutcomes(db, tenderId)
                                : db.sql(
                                        "SELECT r.tender_vendor_id, vd.name, r.reason"
                                            + " FROM report_vendor_results r JOIN"
                                            + " app_report_vendors(:rv) vd ON vd.tender_vendor_id ="
                                            + " r.tender_vendor_id WHERE r.report_version_id = :rv"
                                            + " AND r.outcome = 'rejected_at_screening'"
                                            + " ORDER BY vd.name")
                                    .bind("rv", v.id())
                                    .map(
                                        (r, m) ->
                                            new ScreeningOutcome()
                                                .tenderVendorId(
                                                    r.get("tender_vendor_id", UUID.class))
                                                .vendorName(r.get("name", String.class))
                                                .reason(r.get("reason", String.class)))
                                    .all()
                                    .collectList()));
  }

  public Mono<Matrix> matrix(
      DatabaseClient db,
      UUID tenderId,
      Integer version,
      Integer limit,
      String cursor,
      UUID categoryId,
      Importance importance,
      Grade grade,
      UUID tvFilter,
      Boolean disagreement) {
    return access(db, tenderId)
        .flatMap(
            a ->
                resolve(db, a, version)
                    .flatMap(
                        v ->
                            Mono.zip(
                                    vendorsOf(db, a, v),
                                    cells(db, a, v, tvFilter, null),
                                    categoryName(db, categoryId))
                                .map(
                                    z ->
                                        matrixOf(
                                            v,
                                            z.getT1(),
                                            z.getT2(),
                                            z.getT3(),
                                            limit,
                                            cursor,
                                            importance,
                                            grade,
                                            tvFilter,
                                            disagreement))));
  }

  private Mono<String> categoryName(DatabaseClient db, UUID categoryId) {
    if (categoryId == null) {
      return Mono.just("");
    }
    return db.sql("SELECT name FROM requirement_categories WHERE id = :id")
        .bind("id", categoryId)
        .map((r, m) -> r.get("name", String.class))
        .one()
        .defaultIfEmpty("\u0000");
  }

  private Matrix matrixOf(
      Ver v,
      List<Vendor> vendors,
      List<Cell> cells,
      String category,
      Integer limit,
      String cursor,
      Importance importance,
      Grade grade,
      UUID tvFilter,
      Boolean disagreement) {
    Map<UUID, Vendor> shown = new LinkedHashMap<>();
    for (Vendor vd : vendors) {
      if (tvFilter == null || tvFilter.equals(vd.tv())) {
        shown.put(vd.tv(), vd);
      }
    }
    Map<String, List<Cell>> byRequirement = new LinkedHashMap<>();
    Map<String, Cell> head = new LinkedHashMap<>();
    for (Cell c : cells) {
      if (!category.isEmpty() && !category.equals(c.category)) {
        continue;
      }
      if (importance != null && !importance.getValue().equals(c.importance)) {
        continue;
      }
      byRequirement.computeIfAbsent(c.displayNo, k -> new ArrayList<>()).add(c);
      head.putIfAbsent(c.displayNo, c);
    }
    List<String> keys = new ArrayList<>();
    for (var e : byRequirement.entrySet()) {
      List<Cell> row = e.getValue();
      boolean anyGrade =
          grade == null || row.stream().anyMatch(c -> grade.getValue().equals(c.grade()));
      boolean anyDisagreement =
          !Boolean.TRUE.equals(disagreement) || row.stream().anyMatch(Cell::disagreement);
      if (anyGrade && anyDisagreement) {
        keys.add(e.getKey());
      }
    }
    int start = 0;
    Cursor cur = Cursor.decodeOrNull(cursor);
    if (cur != null) {
      start = Integer.parseInt(cur.sortValue());
    }
    int lim = Cursor.limit(limit);
    List<String> page =
        keys.subList(Math.min(start, keys.size()), Math.min(start + lim, keys.size()));
    List<MatrixRow> rows = new ArrayList<>();
    for (String key : page) {
      Cell h = head.get(key);
      MatrixRow row = new MatrixRow().requirement(requirement(h));
      Map<UUID, Cell> byVendor = new LinkedHashMap<>();
      byRequirement.get(key).forEach(c -> byVendor.put(c.tv, c));
      for (Vendor vd : shown.values()) {
        Cell c = byVendor.get(vd.tv());
        MatrixCell mc = new MatrixCell().tenderVendorId(vd.tv());
        if (c == null) {
          mc.available(false).hasDisagreement(false);
        } else {
          mc.available(c.complete).hasDisagreement(c.complete && c.disagreement());
          if (c.complete && c.grade() != null) {
            mc.finalGrade(Grade.fromValue(c.grade())).basis(GradeBasis.fromValue(c.gradeBasis()));
          }
        }
        row.addCellsItem(mc);
      }
      rows.add(row);
    }
    String next =
        start + lim < keys.size() ? new Cursor(String.valueOf(start + lim), NONE).encode() : null;
    Matrix out = new Matrix().rows(rows).nextCursor(next);
    shown.values().stream()
        .filter(vd -> "evaluated".equals(vd.proposalStatus()) || v.live())
        .forEach(
            vd ->
                out.addVendorsItem(
                    new app.tendereval.backend.api.model.ProgressMatrixVendorsInner()
                        .tenderVendorId(vd.tv())
                        .name(vd.name())));
    return out;
  }

  private static RequirementLite requirement(Cell c) {
    return new RequirementLite()
        .id(c.requirementId)
        .displayNo(c.displayNo)
        .text(c.text)
        .category(c.category == null ? "" : c.category)
        .importance(Importance.fromValue(c.importance))
        .acceptanceCriteria(c.criteria);
  }

  private MatrixCellDetail detail(Cell c, AiGrade ai) {
    MatrixCellDetail d =
        new MatrixCellDetail()
            .requirement(requirement(c))
            .tenderVendorId(c.tv)
            .justification(c.justification)
            .decidedBy(ref(c.decidedBy, c.decidedByName))
            .decidedAt(c.decidedAt)
            .ai(ai);
    if (c.grade() != null) {
      d.finalGrade(Grade.fromValue(c.grade())).basis(GradeBasis.fromValue(c.gradeBasis()));
    }
    for (Copy m : c.members) {
      d.addMemberGradesItem(memberGrade(m));
    }
    return d;
  }

  private static MemberGrade memberGrade(Copy m) {
    MemberGrade g =
        new MemberGrade()
            .member(ref(m.userId(), m.name()))
            .note(m.note())
            .reviewState(RowReviewState.fromValue(m.state() == null ? "not_reviewed" : m.state()));
    if (m.grade() != null) {
      g.setGrade(Grade.fromValue(m.grade()));
    }
    return g;
  }

  private Mono<AiGrade> ai(DatabaseClient db, UUID gradeId) {
    if (gradeId == null) {
      return Mono.empty();
    }
    return db.sql(
            "SELECT g.id, g.outcome, g.grade, g.non_compliance_reason, g.rationale_summary,"
                + " g.rationale, g.missing_elements::text AS missing, g.confidence, g.created_at,"
                + " g.is_current, a.model_name, a.model_version FROM ai_grades g"
                + " JOIN ai_runs a ON a.id = g.ai_run_id WHERE g.id = :id")
        .bind("id", gradeId)
        .map(
            (r, m) -> {
              AiGrade out =
                  new AiGrade()
                      .id(r.get("id", UUID.class))
                      .outcome(AiOutcome.fromValue(r.get("outcome", String.class)))
                      .rationaleSummary(r.get("rationale_summary", String.class))
                      .rationale(r.get("rationale", String.class))
                      .confidence(r.get("confidence", BigDecimal.class))
                      .citations(new ArrayList<>())
                      .missingElements(missing(r.get("missing", String.class)))
                      .modelVersion(
                          r.get("model_name", String.class)
                              + (r.get("model_version", String.class) == null
                                  ? ""
                                  : " " + r.get("model_version", String.class)))
                      .runAt(r.get("created_at", OffsetDateTime.class))
                      .isCurrent(Boolean.TRUE.equals(r.get("is_current", Boolean.class)));
              if (r.get("grade", String.class) != null) {
                out.setGrade(Grade.fromValue(r.get("grade", String.class)));
              }
              if (r.get("non_compliance_reason", String.class) != null) {
                out.setNonComplianceReason(
                    NonComplianceReason.fromValue(r.get("non_compliance_reason", String.class)));
              }
              return out;
            })
        .one()
        .flatMap(
            g ->
                db.sql(
                        "SELECT citation_no, document_version_id, passage_id, page_no, excerpt"
                            + " FROM grade_citations WHERE ai_grade_id = :id ORDER BY citation_no")
                    .bind("id", gradeId)
                    .map(
                        (r, m) ->
                            new Citation()
                                .citationNo(r.get("citation_no", Integer.class))
                                .documentVersionId(r.get("document_version_id", UUID.class))
                                .passageId(r.get("passage_id", UUID.class))
                                .pageNo(r.get("page_no", Integer.class))
                                .excerpt(r.get("excerpt", String.class)))
                    .all()
                    .collectList()
                    .map(
                        cs -> {
                          g.setCitations(cs);
                          return g;
                        }));
  }

  private static List<String> missing(String jsonText) {
    if (jsonText == null || jsonText.length() < 3) {
      return new ArrayList<>();
    }
    List<String> out = new ArrayList<>();
    var m = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(jsonText);
    while (m.find()) {
      out.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
    }
    return out;
  }

  public Mono<MatrixCellDetail> cellDetail(
      DatabaseClient db, UUID tenderId, UUID requirementId, UUID tenderVendorId, Integer version) {
    return access(db, tenderId)
        .flatMap(
            a ->
                resolve(db, a, version)
                    .flatMap(v -> cellOf(db, a, v, requirementId, tenderVendorId)));
  }

  private Mono<MatrixCellDetail> cellOf(
      DatabaseClient db, Access a, Ver v, UUID requirementId, UUID tenderVendorId) {
    return cells(db, a, v, tenderVendorId, requirementId)
        .flatMap(
            cs -> {
              if (cs.isEmpty() || !cs.get(0).complete) {
                return Mono.<MatrixCellDetail>error(
                    ApiException.notFound("this cell: the vendor is not complete yet"));
              }
              Cell c = cs.get(0);
              return Mono.zip(
                      ai(db, c.aiGradeId)
                          .map(java.util.Optional::of)
                          .defaultIfEmpty(java.util.Optional.empty()),
                      decisionVersion(db, v, tenderVendorId, requirementId)
                          .map(java.util.Optional::of)
                          .defaultIfEmpty(java.util.Optional.empty()),
                      history(db, v, tenderVendorId, requirementId))
                  .map(
                      z -> {
                        MatrixCellDetail d = detail(c, z.getT1().orElse(null));
                        d.setRowVersion(z.getT2().orElse(null));
                        d.setHistory(z.getT3());
                        return d;
                      });
            });
  }

  /** Version of the Chair's decision on the row, for If-Match; only readers of the grade see it. */
  private Mono<Integer> decisionVersion(
      DatabaseClient db, Ver v, UUID tenderVendorId, UUID requirementId) {
    if (v.id() == null) {
      return Mono.empty();
    }
    return db.sql(
            "SELECT row_version FROM report_grades WHERE report_version_id = :rv"
                + " AND tender_vendor_id = :tv AND requirement_id = :rq AND basis = 'chair_decision'")
        .bind("rv", v.id())
        .bind("tv", tenderVendorId)
        .bind("rq", requirementId)
        .map((r, m) -> r.get("row_version", Integer.class))
        .one();
  }

  private Mono<List<GradeDecision>> history(
      DatabaseClient db, Ver v, UUID tenderVendorId, UUID requirementId) {
    if (v.id() == null) {
      return Mono.just(List.of());
    }
    return db.sql(
            "SELECT final_grade, justification, decided_by, decided_by_name, decided_at,"
                + " superseded_at FROM app_report_grade_history(:rv, :tv, :rq)")
        .bind("rv", v.id())
        .bind("tv", tenderVendorId)
        .bind("rq", requirementId)
        .map(
            (r, m) ->
                new GradeDecision()
                    .finalGrade(Grade.fromValue(r.get("final_grade", String.class)))
                    .justification(r.get("justification", String.class))
                    .decidedBy(
                        ref(
                            r.get("decided_by", UUID.class),
                            r.get("decided_by_name", String.class)))
                    .decidedAt(r.get("decided_at", OffsetDateTime.class))
                    .supersededAt(r.get("superseded_at", OffsetDateTime.class)))
        .all()
        .collectList();
  }

  public Mono<DisagreementPage> disagreements(
      DatabaseClient db, UUID tenderId, Integer version, Integer limit, String cursor) {
    return access(db, tenderId)
        .flatMap(
            a ->
                resolve(db, a, version)
                    .flatMap(v -> cells(db, a, v, null, null))
                    .map(
                        cs -> {
                          List<Cell> dis =
                              cs.stream().filter(c -> c.complete && c.disagreement()).toList();
                          int start = 0;
                          Cursor cur = Cursor.decodeOrNull(cursor);
                          if (cur != null) {
                            start = Integer.parseInt(cur.sortValue());
                          }
                          int lim = Cursor.limit(limit);
                          List<Disagreement> items = new ArrayList<>();
                          for (Cell c :
                              dis.subList(
                                  Math.min(start, dis.size()), Math.min(start + lim, dis.size()))) {
                            Disagreement d =
                                new Disagreement()
                                    .requirement(requirement(c))
                                    .tenderVendorId(c.tv)
                                    .vendorName(c.vendor)
                                    .justification(c.justification);
                            c.members.forEach(m -> d.addMemberGradesItem(memberGrade(m)));
                            if (c.aiGrade != null) {
                              d.setAiGrade(Grade.fromValue(c.aiGrade));
                            }
                            if (c.finalGrade != null) {
                              d.setFinalGrade(Grade.fromValue(c.finalGrade));
                            }
                            items.add(d);
                          }
                          return new DisagreementPage()
                              .items(items)
                              .total(dis.size())
                              .nextCursor(
                                  start + lim < dis.size()
                                      ? new Cursor(String.valueOf(start + lim), NONE).encode()
                                      : null);
                        }));
  }

  public Mono<ReportAppendix> appendix(DatabaseClient db, UUID tenderId, Integer version) {
    return access(db, tenderId)
        .flatMap(
            a ->
                resolve(db, a, version)
                    .flatMap(
                        v ->
                            Mono.zip(
                                cells(db, a, v, null, null),
                                models(db, v),
                                clarifications(db, tenderId),
                                audit(db, tenderId, v))))
        .map(
            z -> {
              ReportAppendix out = new ReportAppendix();
              Map<UUID, int[]> stats = new LinkedHashMap<>();
              for (Cell c : z.getT1()) {
                int[] s = stats.computeIfAbsent(c.tv, k -> new int[2]);
                for (Copy m : c.members) {
                  s[1]++;
                  if ("overridden".equals(m.state())) {
                    s[0]++;
                  }
                }
              }
              stats.forEach(
                  (tv, s) ->
                      out.addOverrideStatsItem(
                          new app.tendereval.backend.api.model.ReportAppendixOverrideStatsInner()
                              .tenderVendorId(tv)
                              .overridden(s[0])
                              .total(s[1])
                              .percent(
                                  s[1] == 0
                                      ? BigDecimal.ZERO
                                      : BigDecimal.valueOf(s[0] * 100.0 / s[1])
                                          .setScale(2, java.math.RoundingMode.HALF_UP))));
              out.setClarificationLists(z.getT3());
              out.setAiModelVersions(z.getT2());
              out.setAuditSummary(z.getT4());
              return out;
            });
  }

  private Mono<app.tendereval.backend.api.model.ReportAppendixAuditSummary> audit(
      DatabaseClient db, UUID tenderId, Ver v) {
    return db.sql(
            "SELECT action, count(*) AS n FROM audit_events WHERE tender_id = :t"
                + " AND object_type <> 'member_evaluation_row'"
                + " AND occurred_at <= :cut"
                + " GROUP BY action ORDER BY action")
        .bind("t", tenderId)
        .bind(
            "cut",
            v.finalizedAt() == null
                ? OffsetDateTime.parse("9999-12-31T00:00:00Z")
                : v.finalizedAt())
        .map((r, m) -> Map.entry(r.get("action", String.class), r.get("n", Long.class)))
        .all()
        .collectList()
        .map(
            l -> {
              Map<String, Object> by = new LinkedHashMap<>();
              long total = 0;
              for (var e : l) {
                by.put(e.getKey(), e.getValue());
                total += e.getValue();
              }
              return new app.tendereval.backend.api.model.ReportAppendixAuditSummary()
                  .events((int) total)
                  .byAction(by);
            });
  }

  private Mono<List<app.tendereval.backend.api.model.ReportAppendixClarificationListsInner>>
      clarifications(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT tv.id AS tv, br.requirement_id, br.display_no, br.text, br.category_name,"
                + " br.importance, br.acceptance_criteria, r.clarification_note"
                + " FROM tender_vendors tv"
                + " JOIN member_evaluations e ON e.tender_vendor_id = tv.id AND e.excluded_at IS NULL"
                + " JOIN member_evaluation_rows r ON r.member_evaluation_id = e.id AND r.needs_clarification"
                + " JOIN baselines b ON b.tender_id = tv.tender_id AND b.status = 'confirmed'"
                + " JOIN baseline_requirements br ON br.baseline_id = b.id"
                + "   AND br.requirement_id = r.requirement_id"
                + " WHERE tv.tender_id = :t AND app_all_submitted_or_excluded(tv.id)"
                + " ORDER BY tv.id, br.display_no")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                Map.entry(
                    r.get("tv", UUID.class),
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
                                .importance(Importance.fromValue(r.get("importance", String.class)))
                                .acceptanceCriteria(r.get("acceptance_criteria", String.class)))
                        .note(r.get("clarification_note", String.class))))
        .all()
        .collectList()
        .map(
            l -> {
              Map<UUID, List<Clarification>> by = new LinkedHashMap<>();
              l.forEach(
                  e -> by.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
              List<app.tendereval.backend.api.model.ReportAppendixClarificationListsInner> out =
                  new ArrayList<>();
              by.forEach(
                  (tv, items) ->
                      out.add(
                          new app.tendereval.backend.api.model
                                  .ReportAppendixClarificationListsInner()
                              .tenderVendorId(tv)
                              .items(items)));
              return out;
            });
  }

  // ---- writes: the Chair settles disagreements, finalizes and reopens ------------------------

  private final tools.jackson.databind.json.JsonMapper json =
      tools.jackson.databind.json.JsonMapper.builder().build();

  private static ApiException unprocessable(
      String code, String title, List<Map<String, Object>> failures) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, title)
        .with("failures", failures);
  }

  private static Map<String, Object> failure(String code, String message, UUID vendor) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("code", code);
    f.put("message", message);
    f.put("object_type", "tender_vendor");
    f.put("object_id", vendor);
    return f;
  }

  private static void requireChair(Access a, String what) {
    if (!a.chair()) {
      throw ApiException.forbidden("forbidden", "Only the Chair " + what);
    }
  }

  /** Moves evaluation to consolidation when the rule holds, then returns the stage. */
  private Mono<String> stage(DatabaseClient db, UUID tenderId) {
    return db.sql("SELECT app_advance_to_consolidation(:t) AS moved")
        .bind("t", tenderId)
        .fetch()
        .rowsUpdated()
        .then(
            db.sql("SELECT status FROM tenders WHERE id = :t")
                .bind("t", tenderId)
                .map((r, m) -> r.get("status", String.class))
                .one());
  }

  private Mono<String> consolidating(DatabaseClient db, UUID tenderId) {
    return stage(db, tenderId)
        .flatMap(
            s ->
                "consolidation".equals(s)
                    ? Mono.just(s)
                    : Mono.<String>error(
                        ApiException.conflict(
                            "not_in_consolidation",
                            "The tender is not in consolidation: every accepted vendor needs all"
                                + " evaluations submitted or excluded first")));
  }

  private Mono<Ver> insertVersion(
      DatabaseClient db,
      CurrentUser user,
      Access a,
      Ver opening,
      UUID reopenedFrom,
      String reason) {
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
                "INSERT INTO report_versions (tender_id, version_no, baseline_id, scoring_model_id,"
                    + " created_by, reopened_from_version_id, reopen_reason)"
                    + " VALUES (:t, :no, :b, :sm, :u, :from, :reason) RETURNING id")
            .bind("t", a.tenderId())
            .bind("no", opening.no())
            .bind("b", opening.baselineId())
            .bind("sm", opening.scoringModelId())
            .bind("u", user.id());
    spec =
        reopenedFrom == null
            ? spec.bindNull("from", UUID.class).bindNull("reason", String.class)
            : spec.bind("from", reopenedFrom).bind("reason", reason);
    return spec.map((r, m) -> r.get("id", UUID.class))
        .one()
        .map(
            id ->
                new Ver(
                    id,
                    opening.no(),
                    "in_progress",
                    opening.baselineId(),
                    opening.baselineNo(),
                    opening.scoringModelId(),
                    null,
                    null,
                    reason,
                    true));
  }

  /** The version in progress, opened with the Chair's first action. */
  private Mono<Ver> ensureOpen(DatabaseClient db, CurrentUser user, Access a) {
    return versions(db, a.tenderId())
        .flatMap(
            all -> {
              var open = all.stream().filter(Ver::live).findFirst();
              if (open.isPresent()) {
                return Mono.just(open.get());
              }
              int next = all.isEmpty() ? 1 : all.get(0).no() + 1;
              return openingVersion(db, a.tenderId(), next)
                  .flatMap(o -> insertVersion(db, user, a, o, null, null));
            })
        // Two first actions at once: the loser reads the version the winner opened.
        .onErrorResume(
            org.springframework.dao.DataIntegrityViolationException.class,
            e ->
                versions(db, a.tenderId())
                    .flatMap(
                        all ->
                            Mono.justOrEmpty(all.stream().filter(Ver::live).findFirst())
                                .switchIfEmpty(Mono.error(e))));
  }

  public Mono<MatrixCellDetail> recordFinalGrade(
      DatabaseClient db,
      CurrentUser user,
      UUID tenderId,
      UUID tenderVendorId,
      UUID requirementId,
      Integer ifMatch,
      FinalGradeInput in) {
    String justification = in.getJustification() == null ? "" : in.getJustification().strip();
    return access(db, tenderId)
        .flatMap(
            a -> {
              requireChair(a, "records a final grade");
              return Settings.load(db)
                  .flatMap(
                      s -> {
                        if (justification.length() < Math.max(10, s.minNoteLength())) {
                          return Mono.<MatrixCellDetail>error(
                              ApiException.invalid(
                                  "justification",
                                  "too_short",
                                  "Give a justification of at least 10 characters"));
                        }
                        return consolidating(db, tenderId)
                            .then(ensureOpen(db, user, a))
                            .flatMap(
                                v ->
                                    cells(db, a, v, tenderVendorId, requirementId)
                                        .flatMap(
                                            cs ->
                                                decide(
                                                    db,
                                                    user,
                                                    a,
                                                    v,
                                                    cs,
                                                    tenderVendorId,
                                                    requirementId,
                                                    ifMatch,
                                                    in,
                                                    justification)));
                      });
            });
  }

  private Mono<MatrixCellDetail> decide(
      DatabaseClient db,
      CurrentUser user,
      Access a,
      Ver v,
      List<Cell> cs,
      UUID tenderVendorId,
      UUID requirementId,
      Integer ifMatch,
      FinalGradeInput in,
      String justification) {
    if (cs.isEmpty() || !cs.get(0).complete) {
      return Mono.error(
          ApiException.conflict(
              "vendor_not_complete", "Every member must submit this vendor before a final grade"));
    }
    Cell c = cs.get(0);
    if (!c.disagreement() && c.finalGrade == null) {
      return Mono.error(
          new ApiException(
              HttpStatus.UNPROCESSABLE_ENTITY,
              "no_disagreement",
              "All members agree on this row: the final grade is derived and cannot be set"));
    }
    // An earlier decision of the Chair is changed only knowing its version (G15); the trigger keeps
    // it
    // in the row's history.
    return db.sql(
            "SELECT row_version, basis FROM report_grades WHERE report_version_id = :rv"
                + " AND tender_vendor_id = :tv AND requirement_id = :rq")
        .bind("rv", v.id())
        .bind("tv", tenderVendorId)
        .bind("rq", requirementId)
        .map((r, m) -> r.get("row_version", Integer.class))
        .one()
        .map(java.util.Optional::of)
        .defaultIfEmpty(java.util.Optional.empty())
        .flatMap(
            current -> {
              if (current.isPresent()) {
                if (ifMatch == null) {
                  return Mono.<UUID>error(
                      new ApiException(
                          HttpStatus.PRECONDITION_REQUIRED,
                          "precondition_required",
                          "If-Match is required to change a recorded final grade"));
                }
                if (!current.get().equals(ifMatch)) {
                  return Mono.<UUID>error(ETags.stale());
                }
              }
              return upsert(
                  db, user, v, c, tenderVendorId, requirementId, in, justification, ifMatch);
            })
        .flatMap(
            id ->
                audit.record(
                    db,
                    user,
                    // The grade and the justification stay in the report row: the Observer reads
                    // the audit trail before release (UX-RPT-01), so only the fact is recorded.
                    AuditEntry.of("report_grade_recorded", "report_grade", id)
                        .tender(a.tenderId())
                        .created(Map.of("basis", "chair_decision", "version_no", v.no()))
                        .note("Final grade recorded by the Chair")
                        .build()))
        .then(cellOf(db, a, v, requirementId, tenderVendorId));
  }

  private Mono<UUID> upsert(
      DatabaseClient db,
      CurrentUser user,
      Ver v,
      Cell c,
      UUID tenderVendorId,
      UUID requirementId,
      FinalGradeInput in,
      String justification,
      Integer ifMatch) {
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
                "INSERT INTO report_grades (report_version_id, tender_vendor_id, requirement_id,"
                    + " ai_grade_id, ai_grade, final_grade, basis, justification, decided_by,"
                    + " decided_at, has_disagreement)"
                    + " VALUES (:rv, :tv, :rq, :ag, :aig, :fg, 'chair_decision', :j, :u, now(), true)"
                    + " ON CONFLICT (report_version_id, tender_vendor_id, requirement_id) DO UPDATE"
                    + " SET final_grade = EXCLUDED.final_grade, basis = 'chair_decision',"
                    + " justification = EXCLUDED.justification, decided_by = EXCLUDED.decided_by,"
                    + " decided_at = now() WHERE report_grades.row_version = :ifm RETURNING id")
            .bind("rv", v.id())
            .bind("tv", tenderVendorId)
            .bind("rq", requirementId)
            .bind("fg", in.getFinalGrade().getValue())
            .bind("j", justification)
            .bind("u", user.id());
    spec = ifMatch == null ? spec.bind("ifm", 0) : spec.bind("ifm", ifMatch);
    spec =
        c.aiGradeId == null
            ? spec.bindNull("ag", UUID.class).bindNull("aig", String.class)
            : c.aiGrade == null
                ? spec.bind("ag", c.aiGradeId).bindNull("aig", String.class)
                : spec.bind("ag", c.aiGradeId).bind("aig", c.aiGrade);
    return spec.map((r, m) -> r.get("id", UUID.class))
        .one()
        .switchIfEmpty(Mono.error(ETags.stale()));
  }

  public Mono<ReportVersion> finalizeReport(DatabaseClient db, CurrentUser user, UUID tenderId) {
    return access(db, tenderId)
        .flatMap(
            a -> {
              requireChair(a, "finalizes the report");
              return consolidating(db, tenderId)
                  .then(ensureOpen(db, user, a))
                  .flatMap(v -> finalizeVersion(db, user, a, v));
            });
  }

  private Mono<ReportVersion> finalizeVersion(
      DatabaseClient db, CurrentUser user, Access a, Ver v) {
    return Mono.zip(vendors(db, a.tenderId()), cells(db, a, v, null, null))
        .flatMap(
            z -> {
              List<Vendor> vendors = z.getT1();
              List<Cell> cells = z.getT2();
              List<Map<String, Object>> failures = new ArrayList<>();
              boolean any = false;
              for (Vendor vd : vendors) {
                if (!vd.complete()) {
                  failures.add(
                      failure(
                          "vendor_incomplete",
                          vd.name() + ": not every member has submitted or been excluded",
                          vd.tv()));
                  continue;
                }
                if (!"evaluated".equals(vd.proposalStatus())) {
                  failures.add(
                      failure(
                          "evaluation_pending",
                          vd.name() + ": the evaluation has not finished",
                          vd.tv()));
                  continue;
                }
                any = true;
                long open =
                    cells.stream()
                        .filter(
                            c -> c.tv.equals(vd.tv()) && c.disagreement() && c.finalGrade == null)
                        .count();
                if (open > 0) {
                  failures.add(
                      failure(
                          "disagreements_undecided",
                          vd.name() + ": " + open + " disagreements have no final grade",
                          vd.tv()));
                }
              }
              if (!any && failures.isEmpty()) {
                failures.add(failure("no_evaluated_vendor", "No vendor has been evaluated", null));
              }
              if (!failures.isEmpty()) {
                return Mono.<ReportVersion>error(
                    unprocessable(
                        "precheck_failed", "The report is not ready to be finalized", failures));
              }
              return write(db, user, a, v);
            });
  }

  private Mono<ReportVersion> write(DatabaseClient db, CurrentUser user, Access a, Ver v) {
    return db.sql(
            "INSERT INTO report_grades (report_version_id, tender_vendor_id, requirement_id,"
                + " ai_grade_id, ai_grade, final_grade, basis, has_disagreement)"
                + " SELECT :rv, tv.id, br.requirement_id, g.id, g.grade, min(r.member_grade),"
                + " 'all_agree', false FROM tender_vendors tv"
                + ACTIVE_PROPOSAL
                + " JOIN baseline_requirements br ON br.baseline_id = :b"
                + " LEFT JOIN ai_grades g ON g.proposal_version_id = p.id"
                + "   AND g.requirement_id = br.requirement_id AND g.is_current"
                + " JOIN member_evaluations e ON e.tender_vendor_id = tv.id AND e.excluded_at IS NULL"
                + " JOIN member_evaluation_rows r ON r.member_evaluation_id = e.id"
                + "   AND r.requirement_id = br.requirement_id"
                + " WHERE tv.tender_id = :t AND tv.removed_at IS NULL AND p.status = 'evaluated'"
                + " AND app_all_submitted_or_excluded(tv.id)"
                + " GROUP BY tv.id, br.requirement_id, g.id, g.grade"
                + " HAVING count(*) FILTER (WHERE r.member_grade IS NULL) = 0"
                + " AND count(DISTINCT r.member_grade) = 1"
                + " ON CONFLICT (report_version_id, tender_vendor_id, requirement_id) DO NOTHING")
        .bind("rv", v.id())
        .bind("b", v.baselineId())
        .bind("t", a.tenderId())
        .fetch()
        .rowsUpdated()
        .then(
            db.sql(
                    "INSERT INTO report_member_grades (report_grade_id, user_id, member_grade, note,"
                        + " review_state) SELECT rg.id, e.user_id, r.member_grade, r.note,"
                        + " r.review_state FROM report_grades rg"
                        + " JOIN member_evaluations e ON e.tender_vendor_id = rg.tender_vendor_id"
                        + "   AND e.excluded_at IS NULL"
                        + " JOIN member_evaluation_rows r ON r.member_evaluation_id = e.id"
                        + "   AND r.requirement_id = rg.requirement_id"
                        + " WHERE rg.report_version_id = :rv ON CONFLICT DO NOTHING")
                .bind("rv", v.id())
                .fetch()
                .rowsUpdated())
        .then(Mono.zip(vendors(db, a.tenderId()), model(db, v)))
        .flatMap(
            z ->
                cells(db, a, v, null, null)
                    .flatMap(
                        cells ->
                            scoreVendors(db, a, v, z.getT1(), cells, z.getT2())
                                .flatMap(scored -> storeResults(db, a, v, scored, cells))))
        .then(
            db.sql(
                    "UPDATE report_versions SET status = 'superseded' WHERE tender_id = :t"
                        + " AND status = 'finalized'")
                .bind("t", a.tenderId())
                .fetch()
                .rowsUpdated())
        .then(
            db.sql(
                    "UPDATE report_versions SET status = 'finalized', finalized_by = :u,"
                        + " finalized_at = now() WHERE id = :id AND status = 'in_progress'")
                .bind("u", user.id())
                .bind("id", v.id())
                .fetch()
                .rowsUpdated())
        .then(
            db.sql("SELECT app_chair_report_stage(:t, 'finalized')")
                .bind("t", a.tenderId())
                .fetch()
                .rowsUpdated())
        .then(
            audit.record(
                db,
                user,
                AuditEntry.of("report_finalized", "report_version", v.id())
                    .tender(a.tenderId())
                    .created(Map.of("version_no", v.no(), "baseline_version", v.baselineNo()))
                    .build()))
        .then(
            notifications
                .activeMembers(db, a.tenderId())
                .flatMap(
                    users ->
                        notifications.notify(
                            db,
                            a.tenderId(),
                            "report_finalized",
                            "report_version",
                            v.id(),
                            Map.of("version_no", v.no()),
                            users)))
        .then(
            versions(db, a.tenderId())
                .flatMap(
                    all ->
                        Mono.justOrEmpty(all.stream().filter(x -> x.no() == v.no()).findFirst())))
        .flatMap(x -> userRef(db, x.finalizedBy()).map(u -> versionDto(x, u)));
  }

  private Mono<Void> storeResults(
      DatabaseClient db, Access a, Ver v, List<Scored> scored, List<Cell> cells) {
    List<Mono<Long>> inserts = new ArrayList<>();
    for (Scored s : scored) {
      if (s.result() == null || !"evaluated".equals(s.vendor().proposalStatus())) {
        continue;
      }
      var r = s.result();
      DatabaseClient.GenericExecuteSpec spec =
          db.sql(
                  "INSERT INTO report_vendor_results (report_version_id, tender_vendor_id, outcome,"
                      + " rank, final_score, compliance_percent, mandatory_failures, count_fully,"
                      + " count_partially, count_non, agreement_level, override_count,"
                      + " override_percent, category_scores, explanation)"
                      + " VALUES (:rv, :tv, 'evaluated', :rank, :score, :cp, :mf, :f, :p, :n, :al,"
                      + " :oc, :op, CAST(:cs AS jsonb), CAST(:ex AS jsonb))")
              .bind("rv", v.id())
              .bind("tv", s.vendor().tv())
              .bind("mf", r.mandatoryFailures())
              .bind("f", r.fully())
              .bind("p", r.partially())
              .bind("n", r.non())
              .bind("oc", r.overrideCount())
              .bind("cs", json.writeValueAsString(r.categoryScores()))
              .bind("ex", json.writeValueAsString(r.explanation()));
      spec = s.rank() == null ? spec.bindNull("rank", Integer.class) : spec.bind("rank", s.rank());
      spec = nullable(spec, "score", r.finalScore());
      spec = nullable(spec, "cp", r.compliancePercent());
      spec = nullable(spec, "al", r.agreementLevel());
      spec = nullable(spec, "op", r.overridePercent());
      inserts.add(spec.fetch().rowsUpdated());
    }
    Mono<Void> rejected =
        screeningOutcomes(db, a.tenderId())
            .flatMapMany(reactor.core.publisher.Flux::fromIterable)
            .concatMap(
                o ->
                    db.sql(
                            "INSERT INTO report_vendor_results (report_version_id, tender_vendor_id,"
                                + " outcome, reason) VALUES (:rv, :tv, 'rejected_at_screening', :r)")
                        .bind("rv", v.id())
                        .bind("tv", o.getTenderVendorId())
                        .bind("r", o.getReason())
                        .fetch()
                        .rowsUpdated())
            .then();
    return reactor.core.publisher.Flux.concat(inserts).then(rejected);
  }

  private static DatabaseClient.GenericExecuteSpec nullable(
      DatabaseClient.GenericExecuteSpec spec, String name, BigDecimal value) {
    return value == null ? spec.bindNull(name, BigDecimal.class) : spec.bind(name, value);
  }

  public Mono<ReportVersion> reopen(
      DatabaseClient db, CurrentUser user, UUID tenderId, String reasonText) {
    String reason = reasonText == null ? "" : reasonText.strip();
    return access(db, tenderId)
        .flatMap(
            a -> {
              requireChair(a, "reopens the report");
              return Settings.load(db)
                  .flatMap(
                      s -> {
                        if (reason.length() < Math.max(10, s.minNoteLength())) {
                          return Mono.<ReportVersion>error(
                              ApiException.invalid(
                                  "reason",
                                  "too_short",
                                  "Give a reason of at least 10 characters"));
                        }
                        return versions(db, tenderId)
                            .flatMap(
                                all -> {
                                  var done =
                                      all.stream()
                                          .filter(x -> "finalized".equals(x.status()))
                                          .findFirst();
                                  if (done.isEmpty()
                                      || all.stream().anyMatch(Ver::live)
                                      || !"finalized".equals(a.stage())) {
                                    return Mono.<ReportVersion>error(
                                        ApiException.conflict(
                                            "not_finalized",
                                            "Only a finalized report is reopened"));
                                  }
                                  return reopenFrom(
                                      db, user, a, done.get(), all.get(0).no() + 1, reason);
                                });
                      });
            });
  }

  private Mono<ReportVersion> reopenFrom(
      DatabaseClient db, CurrentUser user, Access a, Ver from, int next, String reason) {
    return db.sql("SELECT app_chair_report_stage(:t, 'consolidation')")
        .bind("t", a.tenderId())
        .fetch()
        .rowsUpdated()
        .then(openingVersion(db, a.tenderId(), next))
        .flatMap(o -> insertVersion(db, user, a, o, from.id(), reason))
        .flatMap(
            nv ->
                db.sql(
                        "INSERT INTO report_grades (report_version_id, tender_vendor_id,"
                            + " requirement_id, ai_grade_id, ai_grade, final_grade, basis,"
                            + " justification, decided_by, decided_at, has_disagreement)"
                            + " SELECT :new, o.tender_vendor_id, o.requirement_id, o.ai_grade_id,"
                            + " o.ai_grade, o.final_grade, 'chair_decision', o.justification,"
                            + " o.decided_by, o.decided_at, true FROM report_grades o"
                            + " JOIN baseline_requirements br ON br.baseline_id = :b"
                            + "   AND br.requirement_id = o.requirement_id"
                            + " WHERE o.report_version_id = :old AND o.basis = 'chair_decision'")
                    .bind("new", nv.id())
                    .bind("b", nv.baselineId())
                    .bind("old", from.id())
                    .fetch()
                    .rowsUpdated()
                    .then(
                        audit.record(
                            db,
                            user,
                            AuditEntry.of("report_reopened", "report_version", nv.id())
                                .tender(a.tenderId())
                                .created(
                                    Map.of("version_no", nv.no(), "from_version_no", from.no()))
                                .note("Report reopened by the Chair")
                                .build()))
                    .thenReturn(versionDto(nv, null)));
  }

  /** The Chair or the administrator reminds the members who have not submitted (UX-RPT-02). */
  public Mono<Void> remind(
      DatabaseClient db,
      CurrentUser user,
      UUID tenderId,
      List<UUID> vendorFilter,
      List<UUID> userFilter) {
    return access(db, tenderId)
        .flatMap(
            a -> {
              if (!a.privileged()) {
                return Mono.<Void>error(
                    ApiException.forbidden(
                        "forbidden", "Only the Chair and the administrator send reminders"));
              }
              return awaiting(db, tenderId)
                  .flatMap(
                      waiting -> {
                        Set<UUID> recipients = new LinkedHashSet<>();
                        waiting.forEach(
                            (tv, users) -> {
                              if (vendorFilter != null
                                  && !vendorFilter.isEmpty()
                                  && !vendorFilter.contains(tv)) {
                                return;
                              }
                              for (UserRef u : users) {
                                if (userFilter == null
                                    || userFilter.isEmpty()
                                    || userFilter.contains(u.getId())) {
                                  recipients.add(u.getId());
                                }
                              }
                            });
                        return notifications
                            .notify(
                                db,
                                tenderId,
                                "manual_reminder",
                                "tender",
                                tenderId,
                                Map.of(),
                                recipients)
                            .then(
                                audit.record(
                                    db,
                                    user,
                                    AuditEntry.of("reminders_sent", "tender", tenderId)
                                        .tender(tenderId)
                                        .created(Map.of("recipients", recipients.size()))
                                        .build()));
                      });
            });
  }
}
