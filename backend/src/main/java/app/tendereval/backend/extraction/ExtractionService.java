package app.tendereval.backend.extraction;

import app.tendereval.backend.api.model.ExtractionCoverage;
import app.tendereval.backend.api.model.ExtractionCoverageItemsInner;
import app.tendereval.backend.api.model.ExtractionRequest;
import app.tendereval.backend.api.model.Job;
import app.tendereval.backend.api.model.Scope;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.jobs.JobsService;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.ApiException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Starting an extraction and showing what has been extracted (UX-EXT-02..07). */
@Service
public class ExtractionService {

  private final AuditService audit;
  private final JobsService jobs;

  public ExtractionService(AuditService audit, JobsService jobs) {
    this.audit = audit;
    this.jobs = jobs;
  }

  /** One validated selection: a version, optional sections, optional page range. */
  private record Item(UUID version, UUID section, Integer from, Integer to) {

    boolean wholeDocument() {
      return section == null && from == null && to == null;
    }

    boolean overlaps(Item o, Map<UUID, int[]> sectionPages) {
      if (!version.equals(o.version)) {
        return false;
      }
      if (wholeDocument() || o.wholeDocument()) {
        return true;
      }
      int[] a = range(sectionPages);
      int[] b = o.range(sectionPages);
      return a[0] <= b[1] && b[0] <= a[1];
    }

    private int[] range(Map<UUID, int[]> sectionPages) {
      if (section != null && sectionPages.containsKey(section)) {
        return sectionPages.get(section);
      }
      return new int[] {from == null ? 1 : from, to == null ? Integer.MAX_VALUE : to};
    }
  }

  public Mono<Job> start(
      DatabaseClient db, CurrentUser actor, UUID tenderId, ExtractionRequest in) {
    if (in.getScope() == null || in.getScope().isEmpty()) {
      return Mono.error(ApiException.invalid("scope", "required", "Choose what to extract from"));
    }
    return gate(db, tenderId)
        .then(validate(db, tenderId, in.getScope()))
        .flatMap(
            items ->
                overlap(db, tenderId, items, Boolean.TRUE.equals(in.getAcknowledgeOverlap()))
                    .then(create(db, actor, tenderId, items, null)));
  }

  /**
   * Retry of a failed or cancelled extraction (UX-JOB-05): a new job over the same scope that
   * points back to the first. The same stage and baseline rules as a start apply.
   */
  public Mono<Job> retry(DatabaseClient db, CurrentUser actor, UUID jobId) {
    return db.sql("SELECT tender_id, job_type FROM jobs WHERE id = :j")
        .bind("j", jobId)
        .map(
            (r, m) ->
                new Object[] {r.get("tender_id", UUID.class), r.get("job_type", String.class)})
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("job")))
        .flatMap(
            row -> {
              UUID tenderId = (UUID) row[0];
              if (!"requirement_extraction".equals(row[1])) {
                return Mono.<Job>error(
                    ApiException.conflict("not_retryable", "This job type cannot be retried here"));
              }
              return jobs.get(db, jobId)
                  .flatMap(
                      job -> {
                        if (!Boolean.TRUE.equals(job.getCanRetry())) {
                          return Mono.<Job>error(
                              ApiException.conflict("not_retryable", "This job cannot be retried"));
                        }
                        return gate(db, tenderId)
                            .then(scopeOf(db, jobId))
                            .flatMap(
                                items ->
                                    overlap(db, tenderId, items, false)
                                        .then(create(db, actor, tenderId, items, jobId)));
                      });
            });
  }

  private Mono<List<Item>> scopeOf(DatabaseClient db, UUID jobId) {
    return db.sql(
            "SELECT document_version_id, section_id, page_from, page_to FROM job_scope_items"
                + " WHERE job_id = :j")
        .bind("j", jobId)
        .map(
            (r, m) ->
                new Item(
                    r.get("document_version_id", UUID.class),
                    r.get("section_id", UUID.class),
                    r.get("page_from", Integer.class),
                    r.get("page_to", Integer.class)))
        .all()
        .collectList();
  }

  /** The tender must be open to the committee, writable and without a confirmed baseline. */
  private Mono<Void> gate(DatabaseClient db, UUID tenderId) {
    return db.sql(
            "SELECT status, archived_at, app_baseline_locked(id) AS locked,"
                + " app_is_member_of(id) AS member FROM tenders WHERE id = :t AND deleted_at IS NULL")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new Object[] {
                  r.get("status", String.class),
                  r.get("archived_at", OffsetDateTime.class),
                  r.get("locked", Boolean.class),
                  r.get("member", Boolean.class)
                })
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("tender")))
        .flatMap(
            t -> {
              if (t[1] != null) {
                return Mono.<Void>error(
                    ApiException.conflict("tender_archived", "Archived tenders are read-only"));
              }
              if (!Boolean.TRUE.equals(t[3])) {
                return Mono.<Void>error(
                    ApiException.forbidden(
                        "forbidden", "Only the committee extracts requirements"));
              }
              if (Boolean.TRUE.equals(t[2])) {
                return Mono.<Void>error(
                    ApiException.conflict(
                        "baseline_locked",
                        "The baseline is confirmed. Reopen it to extract again"));
              }
              if (!"requirements".equals(t[0])) {
                return Mono.<Void>error(
                    ApiException.conflict(
                        "wrong_stage", "Requirements are extracted once the tender is open"));
              }
              return Mono.<Void>empty();
            });
  }

  /** Scope items: every version belongs to the tender, is ready and is not a proposal file. */
  private Mono<List<Item>> validate(DatabaseClient db, UUID tenderId, List<Scope> scope) {
    List<UUID> versions = scope.stream().map(Scope::getDocumentVersionId).distinct().toList();
    return db.sql(
            "SELECT v.id, v.status, v.page_count, d.doc_type FROM document_versions v"
                + " JOIN documents d ON d.id = v.document_id WHERE d.tender_id = :t"
                + " AND v.id IN (:ids) AND v.deleted_at IS NULL AND d.deleted_at IS NULL")
        .bind("t", tenderId)
        .bind("ids", versions)
        .map(
            (r, m) ->
                new Object[] {
                  r.get("id", UUID.class),
                  r.get("status", String.class),
                  r.get("page_count", Integer.class),
                  r.get("doc_type", String.class)
                })
        .all()
        .collectList()
        .flatMap(
            rows -> {
              Map<UUID, Object[]> byId = new java.util.HashMap<>();
              rows.forEach(o -> byId.put((UUID) o[0], o));
              List<Item> items = new ArrayList<>();
              for (Scope s : scope) {
                Object[] v = byId.get(s.getDocumentVersionId());
                if (v == null || "proposal".equals(v[3])) {
                  return Mono.<List<Item>>error(
                      ApiException.invalid(
                          "scope", "unknown_document", "Choose a document of this tender"));
                }
                if (!"ready".equals(v[1])) {
                  return Mono.<List<Item>>error(
                      ApiException.conflict(
                          "document_not_ready", "The document has not finished processing"));
                }
                Integer from = s.getPageFrom();
                Integer to = s.getPageTo();
                int pages = v[2] == null ? 0 : (Integer) v[2];
                if ((from != null && (from < 1 || from > pages))
                    || (to != null && (to < 1 || to > pages))
                    || (from != null && to != null && to < from)) {
                  return Mono.<List<Item>>error(
                      ApiException.invalid(
                          "scope", "pages", "The page range is outside the document"));
                }
                if (s.getSectionIds() == null || s.getSectionIds().isEmpty()) {
                  items.add(new Item(s.getDocumentVersionId(), null, from, to));
                } else {
                  for (UUID section : s.getSectionIds()) {
                    items.add(new Item(s.getDocumentVersionId(), section, from, to));
                  }
                }
              }
              Set<UUID> sections = new HashSet<>();
              items.stream().filter(i -> i.section != null).forEach(i -> sections.add(i.section));
              if (sections.isEmpty()) {
                return Mono.just(items);
              }
              return db.sql(
                      "SELECT id, document_version_id FROM document_sections WHERE id IN (:ids)")
                  .bind("ids", new ArrayList<>(sections))
                  .map(
                      (r, m) ->
                          Map.entry(
                              r.get("id", UUID.class), r.get("document_version_id", UUID.class)))
                  .all()
                  .collectList()
                  .flatMap(
                      found -> {
                        Map<UUID, UUID> owner = new java.util.HashMap<>();
                        found.forEach(e -> owner.put(e.getKey(), e.getValue()));
                        for (Item i : items) {
                          if (i.section != null && !i.version.equals(owner.get(i.section))) {
                            return Mono.<List<Item>>error(
                                ApiException.invalid(
                                    "scope",
                                    "unknown_section",
                                    "A section is not part of its document"));
                          }
                        }
                        return Mono.just(items);
                      });
            });
  }

  private Mono<Void> overlap(
      DatabaseClient db, UUID tenderId, List<Item> mine, boolean acknowledged) {
    return db.sql(
            "SELECT i.document_version_id, i.section_id, i.page_from, i.page_to,"
                + " s.start_page, s.end_page FROM job_scope_items i JOIN jobs j ON j.id = i.job_id"
                + " LEFT JOIN document_sections s ON s.id = i.section_id"
                + " WHERE j.tender_id = :t AND j.job_type = 'requirement_extraction'"
                + " AND j.status IN ('queued', 'running')")
        .bind("t", tenderId)
        .map(
            (r, m) ->
                new Object[] {
                  new Item(
                      r.get("document_version_id", UUID.class),
                      r.get("section_id", UUID.class),
                      r.get("page_from", Integer.class),
                      r.get("page_to", Integer.class)),
                  r.get("section_id", UUID.class),
                  r.get("start_page", Integer.class),
                  r.get("end_page", Integer.class)
                })
        .all()
        .collectList()
        .flatMap(
            open -> {
              Map<UUID, int[]> pages = new java.util.HashMap<>();
              for (Object[] o : open) {
                if (o[1] != null && o[2] != null) {
                  pages.put((UUID) o[1], new int[] {(Integer) o[2], (Integer) o[3]});
                }
              }
              // My own sections' ranges are needed too: read them for the comparison.
              Set<UUID> missing = new HashSet<>();
              mine.stream().filter(i -> i.section != null).forEach(i -> missing.add(i.section));
              missing.removeAll(pages.keySet());
              Mono<Void> load =
                  missing.isEmpty()
                      ? Mono.empty()
                      : db.sql(
                              "SELECT id, start_page, end_page FROM document_sections WHERE id IN (:ids)")
                          .bind("ids", new ArrayList<>(missing))
                          .map(
                              (r, m) ->
                                  Map.entry(
                                      r.get("id", UUID.class),
                                      new int[] {
                                        r.get("start_page", Integer.class),
                                        r.get("end_page", Integer.class)
                                      }))
                          .all()
                          .doOnNext(e -> pages.put(e.getKey(), e.getValue()))
                          .then();
              return load.then(
                  Mono.defer(
                      () -> {
                        boolean clash =
                            open.stream()
                                .map(o -> (Item) o[0])
                                .anyMatch(o -> mine.stream().anyMatch(i -> i.overlaps(o, pages)));
                        if (clash && !acknowledged) {
                          return Mono.<Void>error(
                              ApiException.conflict(
                                  "job_overlap",
                                  "Part of this selection is being extracted by someone else"));
                        }
                        return Mono.<Void>empty();
                      }));
            });
  }

  private Mono<Job> create(
      DatabaseClient db, CurrentUser actor, UUID tenderId, List<Item> items, UUID retryOf) {
    DatabaseClient.GenericExecuteSpec insert =
        db.sql(
                "INSERT INTO jobs (tender_id, job_type, target_type, target_id, started_by,"
                    + " retry_of_job_id) VALUES (:t, 'requirement_extraction', 'tender', :t, :u,"
                    + " :r) RETURNING id")
            .bind("t", tenderId)
            .bind("u", actor.id());
    insert = retryOf == null ? insert.bindNull("r", UUID.class) : insert.bind("r", retryOf);
    return insert
        .map((r, m) -> r.get("id", UUID.class))
        .one()
        .flatMap(
            jobId -> {
              Mono<Void> rows = Mono.empty();
              for (Item i : items) {
                DatabaseClient.GenericExecuteSpec spec =
                    db.sql(
                            "INSERT INTO job_scope_items (job_id, document_version_id, section_id,"
                                + " page_from, page_to) VALUES (:j, :v, :s, :f, :p)")
                        .bind("j", jobId)
                        .bind("v", i.version);
                spec =
                    i.section == null ? spec.bindNull("s", UUID.class) : spec.bind("s", i.section);
                spec = i.from == null ? spec.bindNull("f", Integer.class) : spec.bind("f", i.from);
                spec = i.to == null ? spec.bindNull("p", Integer.class) : spec.bind("p", i.to);
                rows = rows.then(spec.fetch().rowsUpdated().then());
              }
              return rows.then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("job_status_changed", "job", jobId)
                              .tender(tenderId)
                              .created(
                                  Map.of(
                                      "status",
                                      "queued",
                                      "job_type",
                                      "requirement_extraction",
                                      "scope_items",
                                      items.size()))
                              .build()))
                  .then(jobs.get(db, jobId));
            });
  }

  public Mono<ExtractionCoverage> coverage(DatabaseClient db, UUID tenderId, UUID versionId) {
    String filter = versionId == null ? "" : " AND i.document_version_id = :v";
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
                "SELECT i.document_version_id, i.section_id, i.page_from, i.page_to, j.id AS job_id,"
                    + " j.status, j.started_by, u.display_name,"
                    + " coalesce(j.finished_at, j.queued_at) AS at FROM job_scope_items i"
                    + " JOIN jobs j ON j.id = i.job_id LEFT JOIN users u ON u.id = j.started_by"
                    + " WHERE j.tender_id = :t AND j.job_type = 'requirement_extraction'"
                    + " AND j.status IN ('queued', 'running', 'completed', 'completed_with_warnings')"
                    + filter
                    + " ORDER BY j.queued_at DESC")
            .bind("t", tenderId);
    if (versionId != null) {
      spec = spec.bind("v", versionId);
    }
    return spec.map(
            (r, m) -> {
              String status = r.get("status", String.class);
              UUID by = r.get("started_by", UUID.class);
              String name = r.get("display_name", String.class);
              return new ExtractionCoverageItemsInner()
                  .documentVersionId(r.get("document_version_id", UUID.class))
                  .sectionId(r.get("section_id", UUID.class))
                  .pageFrom(r.get("page_from", Integer.class))
                  .pageTo(r.get("page_to", Integer.class))
                  .status(
                      "queued".equals(status) || "running".equals(status)
                          ? ExtractionCoverageItemsInner.StatusEnum.RUNNING
                          : ExtractionCoverageItemsInner.StatusEnum.COMPLETED)
                  .by(by == null || name == null ? null : new UserRef().id(by).displayName(name))
                  .at(r.get("at", OffsetDateTime.class))
                  .jobId(r.get("job_id", UUID.class));
            })
        .all()
        .collectList()
        .map(items -> new ExtractionCoverage().items(new ArrayList<>(items)));
  }
}
