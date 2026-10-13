package app.tendereval.backend.baselines;

import app.tendereval.backend.api.model.Baseline;
import app.tendereval.backend.api.model.BaselineImpact;
import app.tendereval.backend.api.model.BaselinePrecheck;
import app.tendereval.backend.api.model.BaselineRequirement;
import app.tendereval.backend.api.model.BaselineRequirementPage;
import app.tendereval.backend.api.model.BaselineStatus;
import app.tendereval.backend.api.model.CategoryWeight;
import app.tendereval.backend.api.model.Importance;
import app.tendereval.backend.api.model.PrecheckFailure;
import app.tendereval.backend.api.model.RequirementSource;
import app.tendereval.backend.api.model.ScoringModel;
import app.tendereval.backend.api.model.ScoringModelInput;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.common.Cursor;
import app.tendereval.backend.common.ETags;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Baseline and scoring (UX-REQ-13, UX-REQ-14, UX-SCO-01..04). The Chair confirms and reopens. */
@Service
public class BaselineService {

  private final AuditService audit;
  private final JsonMapper json = JsonMapper.builder().build();

  public BaselineService(AuditService audit) {
    this.audit = audit;
  }

  // ---- baselines --------------------------------------------------------------------------------

  private static Baseline baseline(io.r2dbc.spi.Readable r) {
    UUID cb = r.get("confirmed_by", UUID.class);
    UUID rb = r.get("reopened_by", UUID.class);
    return new Baseline()
        .id(r.get("id", UUID.class))
        .tenderId(r.get("tender_id", UUID.class))
        .versionNo(r.get("version_no", Integer.class))
        .status(BaselineStatus.fromValue(r.get("status", String.class)))
        .requirementCount(r.get("requirement_count", Integer.class))
        .confirmedAt(r.get("confirmed_at", OffsetDateTime.class))
        .confirmedBy(
            cb == null || r.get("confirmer", String.class) == null
                ? null
                : new UserRef().id(cb).displayName(r.get("confirmer", String.class)))
        .reopenedAt(r.get("reopened_at", OffsetDateTime.class))
        .reopenedBy(
            rb == null || r.get("reopener", String.class) == null
                ? null
                : new UserRef().id(rb).displayName(r.get("reopener", String.class)))
        .reopenReason(r.get("reopen_reason", String.class));
  }

  private static final String BASELINE_SELECT =
      "SELECT b.id, b.tender_id, b.version_no, b.status, b.requirement_count, b.confirmed_at,"
          + " b.confirmed_by, cu.display_name AS confirmer, b.reopened_at, b.reopened_by,"
          + " ru.display_name AS reopener, b.reopen_reason FROM baselines b"
          + " LEFT JOIN users cu ON cu.id = b.confirmed_by LEFT JOIN users ru ON ru.id = b.reopened_by";

  public Mono<List<Baseline>> list(DatabaseClient db, UUID tenderId) {
    return db.sql(BASELINE_SELECT + " WHERE b.tender_id = :t ORDER BY b.version_no DESC")
        .bind("t", tenderId)
        .map((r, m) -> baseline(r))
        .all()
        .collectList();
  }

  private Mono<Baseline> byId(DatabaseClient db, UUID id) {
    return db.sql(BASELINE_SELECT + " WHERE b.id = :id")
        .bind("id", id)
        .map((r, m) -> baseline(r))
        .one();
  }

  public Mono<BaselineRequirementPage> requirements(
      DatabaseClient db, UUID tenderId, int versionNo, Integer limit, String cursor) {
    int size = Cursor.limit(limit);
    Cursor c = Cursor.decodeOrNull(cursor);
    String paging = c == null ? "" : " AND (substring(br.display_no FROM 5))::integer > :after";
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
                "SELECT br.requirement_id, br.display_no, br.text, br.category_name, br.importance,"
                    + " br.acceptance_criteria, br.sources::text AS sources FROM baseline_requirements br"
                    + " JOIN baselines b ON b.id = br.baseline_id"
                    + " WHERE b.tender_id = :t AND b.version_no = :v"
                    + paging
                    + " ORDER BY (substring(br.display_no FROM 5))::integer LIMIT "
                    + (size + 1))
            .bind("t", tenderId)
            .bind("v", versionNo);
    if (c != null) {
      try {
        spec = spec.bind("after", Integer.parseInt(c.sortValue()));
      } catch (NumberFormatException e) {
        return Mono.error(ApiException.invalid("cursor", "invalid", "The cursor is not valid"));
      }
    }
    return db.sql("SELECT 1 AS x FROM baselines WHERE tender_id = :t AND version_no = :v")
        .bind("t", tenderId)
        .bind("v", versionNo)
        .map((r, m) -> 1)
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("baseline")))
        .then(
            spec.map(
                    (r, m) -> {
                      BaselineRequirement br =
                          new BaselineRequirement()
                              .requirementId(r.get("requirement_id", UUID.class))
                              .displayNo(r.get("display_no", String.class))
                              .text(r.get("text", String.class))
                              .categoryName(r.get("category_name", String.class))
                              .importance(Importance.fromValue(r.get("importance", String.class)))
                              .acceptanceCriteria(r.get("acceptance_criteria", String.class));
                      List<RequirementSource> sources = new ArrayList<>();
                      JsonNode arr = json.readTree(r.get("sources", String.class));
                      for (JsonNode n : arr) {
                        RequirementSource s = new RequirementSource();
                        s.setDocumentVersionId(
                            UUID.fromString(n.path("document_version_id").asString()));
                        if (n.hasNonNull("passage_id")) {
                          s.setPassageId(UUID.fromString(n.path("passage_id").asString()));
                        }
                        if (n.hasNonNull("page_no")) {
                          s.setPageNo(n.path("page_no").asInt());
                        }
                        if (n.hasNonNull("quote")) {
                          s.setQuote(n.path("quote").asString());
                        }
                        sources.add(s);
                      }
                      br.setSources(sources);
                      return br;
                    })
                .all()
                .collectList()
                .map(
                    items -> {
                      String next = null;
                      List<BaselineRequirement> page = items;
                      if (items.size() > size) {
                        page = new ArrayList<>(items.subList(0, size));
                        String no =
                            page.get(size - 1)
                                .getDisplayNo()
                                .substring(4)
                                .replaceFirst("^0+(?!$)", "");
                        next = new Cursor(no, page.get(size - 1).getRequirementId()).encode();
                      }
                      return new BaselineRequirementPage().items(page).nextCursor(next).total(null);
                    }));
  }

  // ---- precheck, confirm, reopen ----------------------------------------------------------------

  public Mono<BaselinePrecheck> precheck(DatabaseClient db, UUID tenderId) {
    Mono<long[]> counts =
        db.sql(
                "SELECT count(*) AS total,"
                    + " count(*) FILTER (WHERE importance = 'mandatory') AS mandatory,"
                    + " count(*) FILTER (WHERE importance = 'optional') AS optional,"
                    + " count(*) FILTER (WHERE category_id IS NULL) AS no_category"
                    + " FROM requirements WHERE tender_id = :t AND deleted_at IS NULL")
            .bind("t", tenderId)
            .map(
                (r, m) ->
                    new long[] {
                      r.get("total", Long.class),
                      r.get("mandatory", Long.class),
                      r.get("optional", Long.class),
                      r.get("no_category", Long.class)
                    })
            .one();
    Mono<List<UUID>> unreviewed = ids(db, tenderId, "r.review_state = 'unreviewed'");
    Mono<List<UUID>> flagged =
        ids(
            db,
            tenderId,
            "EXISTS (SELECT 1 FROM requirement_flags f WHERE f.requirement_id = r.id"
                + " AND f.resolved_at IS NULL)");
    Mono<List<UUID>> uncategorised = ids(db, tenderId, "r.category_id IS NULL");
    Mono<Integer> next =
        db.sql("SELECT coalesce(max(version_no), 0) + 1 AS n FROM baselines WHERE tender_id = :t")
            .bind("t", tenderId)
            .map((r, m) -> r.get("n", Integer.class))
            .one();
    return Mono.zip(counts, unreviewed, flagged, uncategorised, next, scoring(db, tenderId))
        .map(
            t -> {
              long[] c = t.getT1();
              List<PrecheckFailure> failures = new ArrayList<>();
              if (c[0] == 0) {
                failures.add(
                    new PrecheckFailure()
                        .code("no_requirements")
                        .message("There are no requirements to confirm")
                        .objectIds(List.of()));
              }
              if (!t.getT2().isEmpty()) {
                failures.add(
                    new PrecheckFailure()
                        .code("unreviewed")
                        .message(t.getT2().size() + " requirements are not reviewed")
                        .objectType("requirement")
                        .objectIds(t.getT2())
                        .link("/tenders/" + tenderId + "/requirements?review_state=unreviewed"));
              }
              if (!t.getT3().isEmpty()) {
                failures.add(
                    new PrecheckFailure()
                        .code("open_flags")
                        .message(t.getT3().size() + " requirements have flags to resolve")
                        .objectType("requirement")
                        .objectIds(t.getT3())
                        .link("/tenders/" + tenderId + "/requirements?flagged=true"));
              }
              if (!t.getT4().isEmpty()) {
                failures.add(
                    new PrecheckFailure()
                        .code("no_category")
                        .message(t.getT4().size() + " requirements have no category")
                        .objectType("requirement")
                        .objectIds(t.getT4())
                        .link("/tenders/" + tenderId + "/requirements"));
              }
              BaselinePrecheck out =
                  new BaselinePrecheck()
                      .ready(failures.isEmpty())
                      .failures(failures)
                      .scoring(t.getT6())
                      .nextVersionNo(t.getT5());
              app.tendereval.backend.api.model.BaselinePrecheckCounts counts2 =
                  new app.tendereval.backend.api.model.BaselinePrecheckCounts();
              counts2.setTotal((int) c[0]);
              counts2.setMandatory((int) c[1]);
              counts2.setOptional((int) c[2]);
              out.setCounts(counts2);
              return out;
            });
  }

  private Mono<List<UUID>> ids(DatabaseClient db, UUID tenderId, String predicate) {
    return db.sql(
            "SELECT r.id FROM requirements r WHERE r.tender_id = :t AND r.deleted_at IS NULL AND "
                + predicate
                + " ORDER BY (substring(r.display_no FROM 5))::integer LIMIT 200")
        .bind("t", tenderId)
        .map((r, m) -> r.get("id", UUID.class))
        .all()
        .collectList();
  }

  public Mono<Baseline> confirm(DatabaseClient db, CurrentUser actor, UUID tenderId) {
    return db.sql("SELECT app_is_chair_of(:t) AS chair")
        .bind("t", tenderId)
        .map((r, m) -> Boolean.TRUE.equals(r.get("chair", Boolean.class)))
        .one()
        .flatMap(
            chair -> {
              if (!chair) {
                return Mono.<Baseline>error(
                    ApiException.forbidden("forbidden", "Only the Chair confirms requirements"));
              }
              return precheck(db, tenderId)
                  .flatMap(
                      pre -> {
                        if (!pre.getReady()) {
                          Map<String, Object> failures = new LinkedHashMap<>();
                          return Mono.<Baseline>error(
                              new ApiException(
                                      org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                                      "precheck_failed",
                                      "The requirements are not ready to confirm")
                                  .with(
                                      "failures",
                                      pre.getFailures().stream()
                                          .map(
                                              f -> {
                                                Map<String, Object> o = new LinkedHashMap<>();
                                                o.put("code", f.getCode());
                                                o.put("message", f.getMessage());
                                                o.put("object_type", f.getObjectType());
                                                o.put(
                                                    "object_ids",
                                                    f.getObjectIds().stream()
                                                        .map(UUID::toString)
                                                        .toList());
                                                o.put("link", f.getLink());
                                                return o;
                                              })
                                          .toList()));
                        }
                        return doConfirm(db, actor, tenderId, pre);
                      });
            });
  }

  private Mono<Baseline> doConfirm(
      DatabaseClient db, CurrentUser actor, UUID tenderId, BaselinePrecheck pre) {
    int count = pre.getCounts().getTotal();
    int version = pre.getNextVersionNo();
    return db.sql(
            "UPDATE requirements SET review_state = 'confirmed', last_changed_by = :u,"
                + " last_changed_at = now() WHERE tender_id = :t AND deleted_at IS NULL"
                + " AND review_state = 'reviewed'")
        .bind("u", actor.id())
        .bind("t", tenderId)
        .fetch()
        .rowsUpdated()
        .then(
            db.sql(
                    "UPDATE baselines SET status = 'superseded' WHERE tender_id = :t"
                        + " AND status IN ('confirmed', 'reopened')")
                .bind("t", tenderId)
                .fetch()
                .rowsUpdated())
        .then(
            db.sql(
                    "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by)"
                        + " VALUES (:t, :v, :n, :u) RETURNING id")
                .bind("t", tenderId)
                .bind("v", version)
                .bind("n", count)
                .bind("u", actor.id())
                .map((r, m) -> r.get("id", UUID.class))
                .one())
        .flatMap(
            baselineId ->
                db.sql(
                        "INSERT INTO baseline_requirements (baseline_id, requirement_id, display_no,"
                            + " text, category_name, importance, acceptance_criteria, sources,"
                            + " content_hash)"
                            + " SELECT :b, r.id, r.display_no, r.text, c.name, r.importance,"
                            + " r.acceptance_criteria, coalesce((SELECT jsonb_agg(jsonb_build_object("
                            + "   'document_version_id', s.document_version_id, 'passage_id', s.passage_id,"
                            + "   'page_no', s.page_no, 'quote', s.quote) ORDER BY s.created_at)"
                            + "   FROM requirement_sources s WHERE s.requirement_id = r.id), '[]'::jsonb),"
                            + " encode(sha256(convert_to(r.text || '|' || r.importance || '|' ||"
                            + "   r.acceptance_criteria, 'UTF8')), 'hex')"
                            + " FROM requirements r LEFT JOIN requirement_categories c ON c.id = r.category_id"
                            + " WHERE r.tender_id = :t AND r.deleted_at IS NULL")
                    .bind("b", baselineId)
                    .bind("t", tenderId)
                    .fetch()
                    .rowsUpdated()
                    .then(lockScoring(db, tenderId, baselineId))
                    .then(
                        db.sql("SELECT app_chair_move_stage(:t, 'evaluation')")
                            .bind("t", tenderId)
                            .fetch()
                            .rowsUpdated())
                    .then(
                        audit.record(
                            db,
                            actor,
                            AuditEntry.of("baseline_confirmed", "baseline", baselineId)
                                .tender(tenderId)
                                .created(Map.of("version_no", version, "requirement_count", count))
                                .build()))
                    .then(byId(db, baselineId)));
  }

  /** Copies the Chair's editable model (or the defaults) into a locked row of the baseline. */
  private Mono<Void> lockScoring(DatabaseClient db, UUID tenderId, UUID baselineId) {
    return db.sql(
            "INSERT INTO scoring_models (tender_id, baseline_id, points_fully, points_partially,"
                + " points_non, mandatory_rule)"
                + " SELECT :t, :b, coalesce(s.points_fully, 1.0), coalesce(s.points_partially, 0.5),"
                + " coalesce(s.points_non, 0), 'flag_only'"
                + " FROM (SELECT 1) x LEFT JOIN scoring_models s ON s.tender_id = :t"
                + " AND s.baseline_id IS NULL RETURNING id")
        .bind("t", tenderId)
        .bind("b", baselineId)
        .map((r, m) -> r.get("id", UUID.class))
        .one()
        .flatMap(
            lockedId ->
                db.sql(
                        "INSERT INTO scoring_category_weights (scoring_model_id, category_id, weight)"
                            + " SELECT :l, w.category_id, w.weight FROM scoring_category_weights w"
                            + " JOIN scoring_models s ON s.id = w.scoring_model_id"
                            + " WHERE s.tender_id = :t AND s.baseline_id IS NULL")
                    .bind("l", lockedId)
                    .bind("t", tenderId)
                    .fetch()
                    .rowsUpdated())
        .then();
  }

  public Mono<Baseline> reopen(DatabaseClient db, CurrentUser actor, UUID tenderId, String reason) {
    return db.sql(
            "SELECT id, version_no, app_is_chair_of(tender_id) AS chair FROM baselines"
                + " WHERE tender_id = :t AND status = 'confirmed'")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new Object[] {
                  r.get("id", UUID.class),
                  r.get("version_no", Integer.class),
                  r.get("chair", Boolean.class)
                })
        .one()
        .switchIfEmpty(
            Mono.error(
                ApiException.conflict(
                    "no_confirmed_baseline", "There is no confirmed baseline to reopen")))
        .flatMap(
            b -> {
              if (!Boolean.TRUE.equals(b[2])) {
                return Mono.<Baseline>error(
                    ApiException.forbidden("forbidden", "Only the Chair reopens requirements"));
              }
              return db.sql(
                      "UPDATE baselines SET status = 'reopened', reopened_by = :u,"
                          + " reopened_at = now(), reopen_reason = :why WHERE id = :id")
                  .bind("u", actor.id())
                  .bind("why", reason == null ? "" : reason.trim())
                  .bind("id", (UUID) b[0])
                  .fetch()
                  .rowsUpdated()
                  .then(
                      db.sql("SELECT app_chair_move_stage(:t, 'requirements')")
                          .bind("t", tenderId)
                          .fetch()
                          .rowsUpdated())
                  .then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("baseline_reopened", "baseline", (UUID) b[0])
                              .tender(tenderId)
                              .created(Map.of("version_no", b[1]))
                              .note(reason)
                              .build()))
                  .then(byId(db, (UUID) b[0]));
            });
  }

  /** Requirements changed since the baseline now being edited was confirmed. */
  public Mono<BaselineImpact> impact(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT count(*) AS changed FROM requirements r"
                + " JOIN baselines b ON b.tender_id = r.tender_id AND b.status IN ('confirmed', 'reopened')"
                + " LEFT JOIN baseline_requirements br ON br.baseline_id = b.id"
                + "   AND br.requirement_id = r.id"
                + " WHERE r.tender_id = :t AND r.deleted_at IS NULL AND ("
                + "   br.requirement_id IS NULL OR br.content_hash <> encode(sha256(convert_to("
                + "   r.text || '|' || r.importance || '|' || r.acceptance_criteria, 'UTF8')), 'hex'))")
        .bind("t", tenderId)
        .map((r, m) -> r.get("changed", Long.class).intValue())
        .one()
        // Nothing is evaluated before Phase 5, so no row can become outdated yet.
        .map(
            n ->
                new BaselineImpact()
                    .changedRequirements(n)
                    .rowsToOutdate(0)
                    .evaluationsAffected(0));
  }

  // ---- scoring
  // ------------------------------------------------------------------------------------

  /** The model being edited, or the locked one while a baseline is confirmed. */
  public Mono<ScoringModel> scoring(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT s.id, s.points_fully, s.points_partially, s.points_non, s.mandatory_rule,"
                + " s.baseline_id, s.row_version,"
                + " app_baseline_locked(:t) AS locked FROM scoring_models s WHERE s.tender_id = :t"
                + " AND ((app_baseline_locked(:t) AND s.baseline_id = (SELECT id FROM baselines"
                + "   WHERE tender_id = :t AND status = 'confirmed'))"
                + "  OR (NOT app_baseline_locked(:t) AND s.baseline_id IS NULL)) LIMIT 1")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new Object[] {
                  r.get("id", UUID.class),
                  r.get("points_fully", BigDecimal.class),
                  r.get("points_partially", BigDecimal.class),
                  r.get("points_non", BigDecimal.class),
                  r.get("baseline_id", UUID.class),
                  r.get("row_version", Integer.class),
                  r.get("locked", Boolean.class)
                })
        .one()
        .flatMap(
            row ->
                db.sql(
                        "SELECT category_id, weight FROM scoring_category_weights"
                            + " WHERE scoring_model_id = :m ORDER BY category_id")
                    .bind("m", (UUID) row[0])
                    .map(
                        (r, m) ->
                            new CategoryWeight()
                                .categoryId(r.get("category_id", UUID.class))
                                .weight(r.get("weight", BigDecimal.class)))
                    .all()
                    .collectList()
                    .map(
                        w ->
                            model(
                                (BigDecimal) row[1],
                                (BigDecimal) row[2],
                                (BigDecimal) row[3],
                                w,
                                Boolean.TRUE.equals(row[6]),
                                (UUID) row[4],
                                (Integer) row[5])))
        .switchIfEmpty(
            db.sql("SELECT app_baseline_locked(:t) AS locked")
                .bind("t", tenderId)
                .map(
                    (r, m) ->
                        model(
                            new BigDecimal("1.0"),
                            new BigDecimal("0.5"),
                            BigDecimal.ZERO,
                            List.of(),
                            Boolean.TRUE.equals(r.get("locked", Boolean.class)),
                            null,
                            0))
                .one());
  }

  private static ScoringModel model(
      BigDecimal fully,
      BigDecimal partially,
      BigDecimal non,
      List<CategoryWeight> weights,
      boolean locked,
      UUID baselineId,
      int version) {
    ScoringModel m =
        new ScoringModel()
            .pointsFully(fully)
            .pointsPartially(partially)
            .pointsNon(non)
            .mandatoryRule(ScoringModel.MandatoryRuleEnum.FLAG_ONLY)
            .weights(weights)
            .locked(locked)
            .rowVersion(version);
    m.setBaselineId(baselineId);
    return m;
  }

  public Mono<ScoringModel> putScoring(
      DatabaseClient db, CurrentUser actor, UUID tenderId, int ifMatch, ScoringModelInput in) {
    if (in.getPointsFully().compareTo(in.getPointsPartially()) < 0
        || in.getPointsPartially().compareTo(in.getPointsNon()) < 0
        || in.getPointsNon().signum() < 0) {
      return Mono.error(
          ApiException.invalid(
              "points",
              "order",
              "Points must not rise as the grade falls, and cannot be negative"));
    }
    for (CategoryWeight w : in.getWeights()) {
      if (w.getWeight().signum() <= 0) {
        return Mono.error(
            ApiException.invalid("weights", "positive", "Every weight must be above zero"));
      }
    }
    return db.sql("SELECT app_baseline_locked(:t) AS locked, app_is_chair_of(:t) AS chair")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new boolean[] {
                  Boolean.TRUE.equals(r.get("locked", Boolean.class)),
                  Boolean.TRUE.equals(r.get("chair", Boolean.class))
                })
        .one()
        .flatMap(
            s -> {
              if (!s[1]) {
                return Mono.<ScoringModel>error(
                    ApiException.forbidden("forbidden", "Only the Chair sets the scoring model"));
              }
              if (s[0]) {
                return Mono.<ScoringModel>error(
                    ApiException.conflict(
                        "baseline_locked",
                        "The baseline is confirmed. Reopen it to change scoring"));
              }
              return db.sql(
                      "SELECT id, row_version FROM scoring_models WHERE tender_id = :t"
                          + " AND baseline_id IS NULL FOR UPDATE")
                  .bind("t", tenderId)
                  .map(
                      (r, m) ->
                          new Object[] {
                            r.get("id", UUID.class), r.get("row_version", Integer.class)
                          })
                  .one()
                  .map(java.util.Optional::of)
                  .defaultIfEmpty(java.util.Optional.empty())
                  .flatMap(
                      existing -> {
                        int current = existing.map(o -> (Integer) o[1]).orElse(0);
                        if (current != ifMatch) {
                          return Mono.<ScoringModel>error(ETags.stale());
                        }
                        Mono<UUID> id =
                            existing.isPresent()
                                ? db.sql(
                                        "UPDATE scoring_models SET points_fully = :f,"
                                            + " points_partially = :p, points_non = :n WHERE id = :id")
                                    .bind("f", in.getPointsFully())
                                    .bind("p", in.getPointsPartially())
                                    .bind("n", in.getPointsNon())
                                    .bind("id", (UUID) existing.get()[0])
                                    .fetch()
                                    .rowsUpdated()
                                    .thenReturn((UUID) existing.get()[0])
                                : db.sql(
                                        "INSERT INTO scoring_models (tender_id, points_fully,"
                                            + " points_partially, points_non)"
                                            + " VALUES (:t, :f, :p, :n) RETURNING id")
                                    .bind("t", tenderId)
                                    .bind("f", in.getPointsFully())
                                    .bind("p", in.getPointsPartially())
                                    .bind("n", in.getPointsNon())
                                    .map((r, m) -> r.get("id", UUID.class))
                                    .one();
                        return id.flatMap(
                            modelId ->
                                db.sql(
                                        "DELETE FROM scoring_category_weights WHERE scoring_model_id = :m")
                                    .bind("m", modelId)
                                    .fetch()
                                    .rowsUpdated()
                                    .then(insertWeights(db, modelId, in.getWeights()))
                                    .then(
                                        audit.record(
                                            db,
                                            actor,
                                            AuditEntry.of(
                                                    "scoring_changed", "scoring_model", modelId)
                                                .tender(tenderId)
                                                .created(
                                                    Map.of(
                                                        "points_fully", in.getPointsFully(),
                                                        "points_partially", in.getPointsPartially(),
                                                        "points_non", in.getPointsNon(),
                                                        "weights", in.getWeights().size()))
                                                .build()))
                                    .then(scoring(db, tenderId)));
                      });
            });
  }

  private Mono<Void> insertWeights(DatabaseClient db, UUID modelId, List<CategoryWeight> weights) {
    Mono<Void> all = Mono.empty();
    for (CategoryWeight w : weights) {
      all =
          all.then(
              db.sql(
                      "INSERT INTO scoring_category_weights (scoring_model_id, category_id, weight)"
                          + " VALUES (:m, :c, :w)")
                  .bind("m", modelId)
                  .bind("c", w.getCategoryId())
                  .bind("w", w.getWeight())
                  .fetch()
                  .rowsUpdated()
                  .then());
    }
    return all;
  }
}
