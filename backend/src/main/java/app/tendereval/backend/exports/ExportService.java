package app.tendereval.backend.exports;

import app.tendereval.backend.api.model.AuditExportRequest;
import app.tendereval.backend.api.model.Clarification;
import app.tendereval.backend.api.model.Export;
import app.tendereval.backend.api.model.ExportFormat;
import app.tendereval.backend.api.model.ExportStatus;
import app.tendereval.backend.api.model.ExportType;
import app.tendereval.backend.api.model.Matrix;
import app.tendereval.backend.api.model.MatrixRow;
import app.tendereval.backend.api.model.ReportHeader;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.common.Idempotency;
import app.tendereval.backend.common.RateLimiter;
import app.tendereval.backend.config.AppProperties;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.evaluation.MemberEvaluationService;
import app.tendereval.backend.report.ReportService;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.storage.ObjectStorage;
import app.tendereval.backend.web.ApiException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exports (P84, UX-RPT-09, UX-REQ-15, UX-EVA-06, UX-AUD-03). The POST stores a pending row and
 * answers 202; after the commit a bounded executor of this process builds the file as the requester
 * (so row-level security applies to the content), writes it to the exports bucket and marks the row
 * ready or failed. No job row and no message: a lost file is built again by a new request, and a
 * pending row nobody finished is shown as failed after ten minutes.
 */
@Service
public class ExportService {

  private static final Logger log = LoggerFactory.getLogger(ExportService.class);
  private static final int STALE_MINUTES = 10;
  private static final String XLSX =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  private final RequestTransaction tx;
  private final Idempotency idempotency;
  private final RateLimiter limiter;
  private final AuditService audit;
  private final ObjectStorage storage;
  private final AppProperties props;
  private final ReportService report;
  private final MemberEvaluationService member;
  private final Scheduler workers = Schedulers.newBoundedElastic(2, 50, "exports");
  private final JsonMapper json = JsonMapper.builder().build();

  public ExportService(
      RequestTransaction tx,
      Idempotency idempotency,
      RateLimiter limiter,
      AuditService audit,
      ObjectStorage storage,
      AppProperties props,
      ReportService report,
      MemberEvaluationService member) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.limiter = limiter;
    this.audit = audit;
    this.storage = storage;
    this.props = props;
    this.report = report;
    this.member = member;
  }

  /** What a request asks for, decided and checked inside the request's transaction. */
  public record Spec(
      ExportType type,
      String sourceType,
      UUID sourceId,
      UUID tenderId,
      ExportFormat format,
      Map<String, Object> context) {}

  /** Creates the pending export in the request's transaction and starts building it after it. */
  public Mono<ResponseEntity<Export>> start(
      String idempotencyKey,
      String operation,
      String requestHash,
      BiFunction<DatabaseClient, CurrentUser, Mono<Spec>> spec) {
    return tx.asUser(
            (db, user) -> {
              limiter.check(user.id(), RateLimiter.Bucket.EXPORTS);
              return idempotency.run(
                  db,
                  user.id(),
                  idempotencyKey,
                  operation,
                  requestHash,
                  Export.class,
                  () ->
                      spec.apply(db, user)
                          .flatMap(s -> insert(db, user, s))
                          .map(
                              e ->
                                  ResponseEntity.status(HttpStatus.ACCEPTED)
                                      .location(URI.create("/api/v1/exports/" + e.getId()))
                                      .body(e)));
            })
        .flatMap(
            r -> {
              // After the commit (rule 4 applies the same way to our own executor).
              if (r.getBody() != null
                  && ExportStatus.PENDING == r.getBody().getStatus()
                  && !"true".equals(r.getHeaders().getFirst("Idempotent-Replay"))) {
                return RequestTransaction.currentUser()
                    .doOnNext(u -> schedule(r.getBody().getId(), u))
                    .thenReturn(r);
              }
              return Mono.just(r);
            });
  }

  private Mono<Export> insert(DatabaseClient db, CurrentUser user, Spec s) {
    DatabaseClient.GenericExecuteSpec q =
        db.sql(
                "INSERT INTO exports (tender_id, export_type, source_type, source_id, format,"
                    + " requested_by, context) VALUES (:t, :type, :st, :sid, :f, :u,"
                    + " CAST(:c AS jsonb)) RETURNING id, created_at")
            .bind("type", s.type().getValue())
            .bind("st", s.sourceType())
            .bind("f", s.format().getValue())
            .bind("u", user.id())
            .bind("c", json.writeValueAsString(s.context()));
    q = s.tenderId() == null ? q.bindNull("t", UUID.class) : q.bind("t", s.tenderId());
    q = s.sourceId() == null ? q.bindNull("sid", UUID.class) : q.bind("sid", s.sourceId());
    return q.map(
            (r, m) ->
                new Object[] {r.get("id", UUID.class), r.get("created_at", OffsetDateTime.class)})
        .one()
        .flatMap(
            row -> {
              UUID id = (UUID) row[0];
              Export e =
                  new Export()
                      .id(id)
                      .exportType(s.type())
                      .format(s.format())
                      .status(ExportStatus.PENDING)
                      .createdAt((OffsetDateTime) row[1])
                      .requestedBy(new UserRef().id(user.id()).displayName(user.displayName()))
                      .context(s.context());
              AuditEntry.Builder b =
                  AuditEntry.of("export_created", "export", id)
                      .created(
                          Map.of(
                              "export_type", s.type().getValue(), "format", s.format().getValue()));
              if (s.tenderId() != null) {
                b.tender(s.tenderId());
              }
              return audit.record(db, user, b.build()).thenReturn(e);
            });
  }

  // ---- building the file -----------------------------------------------------------------

  private void schedule(UUID id, CurrentUser user) {
    generate(id, user)
        .subscribeOn(workers)
        .onErrorResume(
            e -> {
              log.warn(
                  "export failed id={} error={} code={}",
                  id,
                  e.getClass().getSimpleName(),
                  e instanceof ApiException a ? a.code() + " " + a.getMessage() : "");
              return fail(
                  id, user, e instanceof ApiException ? "source_unavailable" : "generation_failed");
            })
        .subscribe();
  }

  private Mono<Void> fail(UUID id, CurrentUser user, String code) {
    return tx.run(
            user.id().toString(),
            user.role(),
            db ->
                db.sql(
                        "UPDATE exports SET status = 'failed', failure_code = :c WHERE id = :id"
                            + " AND status = 'pending'")
                    .bind("c", code)
                    .bind("id", id)
                    .fetch()
                    .rowsUpdated())
        .then();
  }

  /** Public for the drills: builds and stores the file of a pending export. */
  public Mono<Void> generate(UUID id, CurrentUser user) {
    return tx.run(
            user.id().toString(),
            user.role(),
            db ->
                db.sql(
                        "SELECT tender_id, export_type, source_id, format, context::text AS ctx"
                            + " FROM exports WHERE id = :id AND status = 'pending'")
                    .bind("id", id)
                    .map(
                        (r, m) ->
                            new Object[] {
                              r.get("tender_id", UUID.class),
                              r.get("export_type", String.class),
                              r.get("source_id", UUID.class),
                              r.get("format", String.class),
                              r.get("ctx", String.class)
                            })
                    .one()
                    .flatMap(
                        o ->
                            build(
                                    db,
                                    (UUID) o[0],
                                    (String) o[1],
                                    (UUID) o[2],
                                    (String) o[3],
                                    context((String) o[4]))
                                .flatMap(
                                    bytes -> {
                                      UUID tender = (UUID) o[0];
                                      String key =
                                          "exports/"
                                              + (tender == null ? "global" : tender.toString())
                                              + "/"
                                              + id
                                              + "."
                                              + o[3];
                                      return storage
                                          .putBytes(
                                              props.s3BucketExports(),
                                              key,
                                              bytes,
                                              contentType((String) o[3]))
                                          .then(
                                              db.sql(
                                                      "UPDATE exports SET status = 'ready',"
                                                          + " storage_key = :k WHERE id = :id"
                                                          + " AND status = 'pending'")
                                                  .bind("k", key)
                                                  .bind("id", id)
                                                  .fetch()
                                                  .rowsUpdated());
                                    })))
        .then();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> context(String text) {
    return text == null ? Map.of() : json.readValue(text, Map.class);
  }

  private static String contentType(String format) {
    return switch (format) {
      case "pdf" -> "application/pdf";
      case "xlsx" -> XLSX;
      default -> "text/csv; charset=utf-8";
    };
  }

  private Mono<byte[]> build(
      DatabaseClient db,
      UUID tender,
      String type,
      UUID source,
      String format,
      Map<String, Object> ctx) {
    return switch (type) {
      case "report" ->
          reportSheets(db, tender, ctx).map(s -> render(format, s.title(), s.sheets()));
      case "requirements_list" ->
          requirementsSheet(db, tender).map(s -> render(format, "Requirements", List.of(s)));
      case "clarification_list" ->
          clarificationSheet(db, source).map(s -> render(format, "Clarifications", List.of(s)));
      case "audit_log" -> auditSheet(db, ctx).map(s -> render(format, "Audit trail", List.of(s)));
      default -> Mono.error(new IllegalStateException("export type " + type));
    };
  }

  private static byte[] render(String format, String title, List<Sheet> sheets) {
    return switch (format) {
      case "pdf" -> PdfWriter.write(title, sheets);
      case "xlsx" -> XlsxWriter.write(sheets);
      default -> CsvWriter.write(sheets.get(0));
    };
  }

  // ---- the contents ------------------------------------------------------------------------

  private record Titled(String title, List<Sheet> sheets) {}

  private static String label(Object grade) {
    if (grade == null) {
      return "";
    }
    return grade.toString().replace('_', ' ');
  }

  private static String d(OffsetDateTime t) {
    return t == null ? "" : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t);
  }

  private Mono<Titled> reportSheets(DatabaseClient db, UUID tender, Map<String, Object> ctx) {
    Integer version = ctx.get("version_no") instanceof Number n ? n.intValue() : null;
    return report
        .header(db, tender, version)
        .flatMap(
            h ->
                Mono.zip(
                        report.overview(db, tender, version),
                        allRows(db, tender, version),
                        allDisagreements(db, tender, version),
                        report.appendix(db, tender, version))
                    .map(z -> assemble(h, z.getT1(), z.getT2(), z.getT3(), z.getT4())));
  }

  private Mono<Matrix> allRows(DatabaseClient db, UUID tender, Integer version) {
    return page(db, tender, version, null, new ArrayList<>(), null);
  }

  private Mono<Matrix> page(
      DatabaseClient db,
      UUID tender,
      Integer version,
      String cursor,
      List<MatrixRow> acc,
      Matrix first) {
    return report
        .matrix(db, tender, version, 500, cursor, null, null, null, null, null)
        .flatMap(
            m -> {
              acc.addAll(m.getRows());
              Matrix head = first == null ? m : first;
              if (m.getNextCursor() == null) {
                head.setRows(acc);
                return Mono.just(head);
              }
              return page(db, tender, version, m.getNextCursor(), acc, head);
            });
  }

  private Mono<List<app.tendereval.backend.api.model.Disagreement>> allDisagreements(
      DatabaseClient db, UUID tender, Integer version) {
    return dpage(db, tender, version, null, new ArrayList<>());
  }

  private Mono<List<app.tendereval.backend.api.model.Disagreement>> dpage(
      DatabaseClient db,
      UUID tender,
      Integer version,
      String cursor,
      List<app.tendereval.backend.api.model.Disagreement> acc) {
    return report
        .disagreements(db, tender, version, 500, cursor)
        .flatMap(
            p -> {
              acc.addAll(p.getItems());
              return p.getNextCursor() == null
                  ? Mono.just(acc)
                  : dpage(db, tender, version, p.getNextCursor(), acc);
            });
  }

  private Titled assemble(
      ReportHeader h,
      app.tendereval.backend.api.model.ReportOverview overview,
      Matrix matrix,
      List<app.tendereval.backend.api.model.Disagreement> disagreements,
      app.tendereval.backend.api.model.ReportAppendix appendix) {
    var sm = h.getScoringModel();
    List<List<String>> facts = new ArrayList<>();
    facts.add(List.of("Tender", h.getTenderName()));
    facts.add(
        List.of(
            "Report version",
            h.getVersionNo()
                + (h.getStatus() == null ? "" : " (" + label(h.getStatus().getValue()) + ")")));
    facts.add(List.of("Finalized at", d(h.getFinalizedAt())));
    facts.add(
        List.of(
            "Finalized by", h.getFinalizedBy() == null ? "" : h.getFinalizedBy().getDisplayName()));
    facts.add(List.of("Baseline version", String.valueOf(h.getBaselineVersion())));
    facts.add(
        List.of(
            "Scoring model",
            "points fully "
                + sm.getPointsFully()
                + ", partially "
                + sm.getPointsPartially()
                + ", non "
                + sm.getPointsNon()
                + "; mandatory failures are flagged"));
    facts.add(List.of("AI model versions", String.join("; ", h.getAiModelVersions())));
    facts.add(
        List.of(
            "Committee",
            String.join("; ", h.getCommittee().stream().map(UserRef::getDisplayName).toList())));
    facts.add(List.of("Disagreements", String.valueOf(h.getDisagreementCount())));

    List<List<String>> ranking = new ArrayList<>();
    Map<UUID, String> names = new LinkedHashMap<>();
    overview
        .getVendors()
        .forEach(
            v -> {
              names.put(v.getTenderVendorId(), v.getVendorName());
              Map<String, Object> c = v.getCounts() == null ? Map.of() : v.getCounts();
              ranking.add(
                  List.of(
                      v.getRank() == null ? "" : String.valueOf(v.getRank()),
                      v.getVendorName(),
                      label(v.getOutcome().getValue()),
                      v.getFinalScore() == null ? "" : v.getFinalScore().toPlainString(),
                      v.getCompliancePercent() == null
                          ? ""
                          : v.getCompliancePercent().toPlainString(),
                      v.getMandatoryFailures() == null
                          ? ""
                          : String.valueOf(v.getMandatoryFailures()),
                      String.valueOf(c.getOrDefault("fully_compliant", "")),
                      String.valueOf(c.getOrDefault("partially_compliant", "")),
                      String.valueOf(c.getOrDefault("non_compliant", "")),
                      v.getAgreementLevel() == null ? "" : v.getAgreementLevel().toPlainString(),
                      v.getReason() == null ? "" : v.getReason()));
            });

    List<String> matrixHead =
        new ArrayList<>(List.of("No", "Requirement", "Category", "Importance"));
    matrix.getVendors().forEach(v -> matrixHead.add(v.getName()));
    List<List<String>> matrixRows = new ArrayList<>();
    for (MatrixRow r : matrix.getRows()) {
      List<String> row = new ArrayList<>();
      row.add(r.getRequirement().getDisplayNo());
      row.add(r.getRequirement().getText());
      row.add(r.getRequirement().getCategory());
      row.add(label(r.getRequirement().getImportance().getValue()));
      r.getCells()
          .forEach(
              c ->
                  row.add(
                      !c.getAvailable()
                          ? "not available"
                          : (c.getFinalGrade() == null ? "" : label(c.getFinalGrade().getValue()))
                              + (c.getBasis() == null
                                  ? ""
                                  : " (" + label(c.getBasis().getValue()) + ")")));
      matrixRows.add(row);
    }

    List<List<String>> dis = new ArrayList<>();
    disagreements.forEach(
        x ->
            dis.add(
                List.of(
                    x.getRequirement().getDisplayNo(),
                    x.getRequirement().getText(),
                    x.getVendorName(),
                    String.join(
                        "; ",
                        x.getMemberGrades().stream()
                            .map(
                                g ->
                                    g.getMember().getDisplayName()
                                        + ": "
                                        + (g.getGrade() == null
                                            ? "no grade"
                                            : label(g.getGrade().getValue())))
                            .toList()),
                    x.getFinalGrade() == null ? "" : label(x.getFinalGrade().getValue()),
                    x.getJustification() == null ? "" : x.getJustification())));

    List<List<String>> overrides = new ArrayList<>();
    appendix
        .getOverrideStats()
        .forEach(
            s ->
                overrides.add(
                    List.of(
                        names.getOrDefault(s.getTenderVendorId(), s.getTenderVendorId().toString()),
                        String.valueOf(s.getOverridden()),
                        String.valueOf(s.getTotal()),
                        s.getPercent().toPlainString())));

    List<Sheet> sheets =
        List.of(
            new Sheet("Summary", List.of("Fact", "Value"), facts),
            new Sheet(
                "Ranking",
                List.of(
                    "Rank",
                    "Vendor",
                    "Outcome",
                    "Final score",
                    "Compliance %",
                    "Mandatory failures",
                    "Fully",
                    "Partially",
                    "Non",
                    "Agreement %",
                    "Reason"),
                ranking),
            new Sheet("Matrix", matrixHead, matrixRows),
            new Sheet(
                "Disagreements",
                List.of(
                    "No", "Requirement", "Vendor", "Member grades", "Final grade", "Justification"),
                dis),
            new Sheet(
                "Overrides", List.of("Vendor", "Overridden", "Decisions", "Percent"), overrides));
    return new Titled("Consolidated report: " + h.getTenderName(), sheets);
  }

  private Mono<Sheet> requirementsSheet(DatabaseClient db, UUID tender) {
    return db.sql(
            "SELECT q.display_no, q.text, c.name AS category, q.importance, q.acceptance_criteria,"
                + " q.origin, q.review_state FROM requirements q"
                + " LEFT JOIN requirement_categories c ON c.id = q.category_id"
                + " WHERE q.tender_id = :t AND q.deleted_at IS NULL ORDER BY q.display_no")
        .bind("t", tender)
        .map(
            (r, m) ->
                List.of(
                    r.get("display_no", String.class),
                    r.get("text", String.class),
                    String.valueOf(
                        r.get("category", String.class) == null
                            ? ""
                            : r.get("category", String.class)),
                    label(r.get("importance", String.class)),
                    r.get("acceptance_criteria", String.class),
                    label(r.get("origin", String.class)),
                    label(r.get("review_state", String.class))))
        .all()
        .collectList()
        .map(
            rows ->
                new Sheet(
                    "Requirements",
                    List.of(
                        "No",
                        "Requirement",
                        "Category",
                        "Importance",
                        "Acceptance criteria",
                        "Origin",
                        "State"),
                    rows));
  }

  private Mono<Sheet> clarificationSheet(DatabaseClient db, UUID tenderVendor) {
    return member
        .clarifications(db, tenderVendor)
        .map(
            list -> {
              List<List<String>> rows = new ArrayList<>();
              for (Clarification c : list) {
                rows.add(
                    List.of(
                        c.getRequirement().getDisplayNo(),
                        c.getRequirement().getText(),
                        c.getNote() == null ? "" : c.getNote(),
                        c.getDecision() == null ? "" : c.getDecision()));
              }
              return new Sheet(
                  "Clarifications", List.of("No", "Requirement", "Question", "Decision"), rows);
            });
  }

  private Mono<Sheet> auditSheet(DatabaseClient db, Map<String, Object> f) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT a.occurred_at, u.display_name, a.actor_role, a.source, a.tender_id,"
                + " a.tender_vendor_id, a.object_type, a.object_id, a.action, a.note"
                + " FROM audit_events a LEFT JOIN users u ON u.id = a.actor_user_id WHERE true");
    Map<String, Object> binds = new LinkedHashMap<>();
    addFilter(sql, binds, f, "tender_id", " AND a.tender_id = :tender_id", UUID::fromString);
    addFilter(
        sql,
        binds,
        f,
        "tender_vendor_id",
        " AND a.tender_vendor_id = :tender_vendor_id",
        UUID::fromString);
    addFilter(sql, binds, f, "user_id", " AND a.actor_user_id = :user_id", UUID::fromString);
    addFilter(sql, binds, f, "action", " AND a.action = :action", s -> s);
    addFilter(sql, binds, f, "from", " AND a.occurred_at >= :from", OffsetDateTime::parse);
    addFilter(sql, binds, f, "to", " AND a.occurred_at <= :to", OffsetDateTime::parse);
    sql.append(" ORDER BY a.occurred_at, a.id LIMIT 100000");
    DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString());
    for (var e : binds.entrySet()) {
      spec = spec.bind(e.getKey(), e.getValue());
    }
    return spec.map(
            (r, m) ->
                List.of(
                    d(r.get("occurred_at", OffsetDateTime.class)),
                    nz(r.get("display_name", String.class)),
                    nz(r.get("actor_role", String.class)),
                    nz(r.get("source", String.class)),
                    nz(
                        String.valueOf(
                            r.get("tender_id", UUID.class) == null
                                ? ""
                                : r.get("tender_id", UUID.class))),
                    nz(
                        String.valueOf(
                            r.get("tender_vendor_id", UUID.class) == null
                                ? ""
                                : r.get("tender_vendor_id", UUID.class))),
                    nz(r.get("object_type", String.class)),
                    nz(
                        String.valueOf(
                            r.get("object_id", UUID.class) == null
                                ? ""
                                : r.get("object_id", UUID.class))),
                    nz(r.get("action", String.class)),
                    nz(r.get("note", String.class))))
        .all()
        .collectList()
        .map(
            rows ->
                new Sheet(
                    "Audit trail",
                    List.of(
                        "Time",
                        "Actor",
                        "Role",
                        "Source",
                        "Tender",
                        "Vendor",
                        "Object type",
                        "Object",
                        "Action",
                        "Note"),
                    rows));
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  private static <T> void addFilter(
      StringBuilder sql,
      Map<String, Object> binds,
      Map<String, Object> f,
      String key,
      String clause,
      java.util.function.Function<String, T> parse) {
    Object v = f.get(key);
    if (v != null && !String.valueOf(v).isBlank()) {
      sql.append(clause);
      binds.put(key, parse.apply(String.valueOf(v)));
    }
  }

  // ---- specs of each export type -----------------------------------------------------------

  public Mono<Spec> reportSpec(DatabaseClient db, UUID tender, String format, Integer version) {
    ExportFormat f = ExportFormat.fromValue(format);
    return report
        .header(db, tender, version)
        .flatMap(
            h -> {
              var export = h.getActions() == null ? null : h.getActions().get("export");
              if (export == null || !Boolean.TRUE.equals(export.getAllowed())) {
                return Mono.<Spec>error(
                    ApiException.forbidden(
                        "forbidden", "Only the Chair and the administrator export the report"));
              }
              Map<String, Object> ctx = new LinkedHashMap<>();
              ctx.put("version_no", h.getVersionNo());
              return Mono.just(new Spec(ExportType.REPORT, "report_version", null, tender, f, ctx));
            });
  }

  public Mono<Spec> requirementsSpec(DatabaseClient db, UUID tender) {
    return db.sql("SELECT 1 AS x FROM tenders WHERE id = :t")
        .bind("t", tender)
        .map((r, m) -> 1)
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("tender")))
        .thenReturn(
            new Spec(
                ExportType.REQUIREMENTS_LIST,
                "tender",
                tender,
                tender,
                ExportFormat.XLSX,
                Map.of()));
  }

  public Mono<Spec> clarificationsSpec(DatabaseClient db, UUID tenderVendor) {
    // clarifications() applies the declaration and membership rules of the member's own view
    return member
        .clarifications(db, tenderVendor)
        .then(
            db.sql("SELECT tender_id FROM tender_vendors WHERE id = :v")
                .bind("v", tenderVendor)
                .map((r, m) -> r.get("tender_id", UUID.class))
                .one()
                .map(
                    t ->
                        new Spec(
                            ExportType.CLARIFICATION_LIST,
                            "tender_vendor",
                            tenderVendor,
                            t,
                            ExportFormat.XLSX,
                            Map.of())));
  }

  public Mono<Spec> auditSpec(CurrentUser user, AuditExportRequest in) {
    String role = user.role().platformName();
    if (!"admin".equals(role) && !"observer".equals(role)) {
      return Mono.error(
          ApiException.forbidden("forbidden", "Only the administrator and the Observer export"));
    }
    Map<String, Object> ctx = new LinkedHashMap<>();
    if (in.getTenderId() != null) ctx.put("tender_id", in.getTenderId().toString());
    if (in.getTenderVendorId() != null)
      ctx.put("tender_vendor_id", in.getTenderVendorId().toString());
    if (in.getUserId() != null) ctx.put("user_id", in.getUserId().toString());
    if (in.getAction() != null) ctx.put("action", in.getAction().getValue());
    if (in.getFrom() != null) ctx.put("from", in.getFrom().toString());
    if (in.getTo() != null) ctx.put("to", in.getTo().toString());
    return Mono.just(
        new Spec(
            ExportType.AUDIT_LOG, "audit_filter", null, in.getTenderId(), ExportFormat.CSV, ctx));
  }

  // ---- reading -----------------------------------------------------------------------------

  private record Row(
      UUID id,
      UUID tender,
      String type,
      String format,
      String status,
      String key,
      String failure,
      OffsetDateTime createdAt,
      UUID by,
      String byName,
      String context) {}

  private Mono<Row> row(DatabaseClient db, UUID id) {
    return db.sql(
            "SELECT e.id, e.tender_id, e.export_type, e.format, e.status, e.storage_key,"
                + " e.failure_code, e.created_at, e.requested_by, u.display_name,"
                + " e.context::text AS ctx FROM exports e JOIN users u ON u.id = e.requested_by"
                + " WHERE e.id = :id")
        .bind("id", id)
        .map(
            (r, m) ->
                new Row(
                    r.get("id", UUID.class),
                    r.get("tender_id", UUID.class),
                    r.get("export_type", String.class),
                    r.get("format", String.class),
                    r.get("status", String.class),
                    r.get("storage_key", String.class),
                    r.get("failure_code", String.class),
                    r.get("created_at", OffsetDateTime.class),
                    r.get("requested_by", UUID.class),
                    r.get("display_name", String.class),
                    r.get("ctx", String.class)))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("export")));
  }

  private static String fileName(Row r) {
    return r.type().replace('_', '-')
        + "-"
        + r.createdAt().toLocalDate()
        + "-"
        + r.id().toString().substring(0, 8)
        + "."
        + r.format();
  }

  /** The export as the API shows it; a pending one nobody finished is shown as failed. */
  public Mono<Export> get(DatabaseClient db, CurrentUser user, UUID id) {
    return row(db, id)
        .flatMap(
            r -> {
              boolean stale =
                  "pending".equals(r.status())
                      && r.createdAt().isBefore(OffsetDateTime.now().minusMinutes(STALE_MINUTES));
              if (stale && r.by().equals(user.id())) {
                return db.sql(
                        "UPDATE exports SET status = 'failed', failure_code = 'interrupted'"
                            + " WHERE id = :id AND status = 'pending'")
                    .bind("id", id)
                    .fetch()
                    .rowsUpdated()
                    .then(row(db, id));
              }
              return Mono.just(r);
            })
        .flatMap(
            r -> {
              Export e =
                  new Export()
                      .id(r.id())
                      .exportType(ExportType.fromValue(r.type()))
                      .format(ExportFormat.fromValue(r.format()))
                      .status(ExportStatus.fromValue(r.status()))
                      .createdAt(r.createdAt())
                      .requestedBy(new UserRef().id(r.by()).displayName(r.byName()))
                      .context(context(r.context()));
              if ("ready".equals(r.status())) {
                e.setFileName(fileName(r));
                e.setDownloadPath("/exports/" + r.id() + "/file");
                return storage
                    .head(props.s3BucketExports(), r.key())
                    .map(h -> e.sizeBytes((int) Math.min(Integer.MAX_VALUE, h.size())))
                    .defaultIfEmpty(e);
              }
              return Mono.just(e);
            });
  }

  public record Download(String fileName, String contentType, byte[] bytes) {}

  public Mono<Download> download(DatabaseClient db, CurrentUser user, UUID id) {
    return row(db, id)
        .flatMap(
            r -> {
              if (!"ready".equals(r.status())) {
                return Mono.<Download>error(
                    ApiException.conflict("export_not_ready", "The export is not ready"));
              }
              return storage
                  .head(props.s3BucketExports(), r.key())
                  .switchIfEmpty(
                      Mono.error(
                          ApiException.conflict(
                              "export_expired",
                              "The file has expired; export again to get a new copy")))
                  .then(storage.getBytes(props.s3BucketExports(), r.key()))
                  .map(b -> new Download(fileName(r), contentType(r.format()), b));
            });
  }
}
