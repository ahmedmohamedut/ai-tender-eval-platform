package app.tendereval.backend.vendors;

import app.tendereval.backend.api.model.DocumentStatus;
import app.tendereval.backend.api.model.FailureReason;
import app.tendereval.backend.api.model.FileRole;
import app.tendereval.backend.api.model.ProposalFile;
import app.tendereval.backend.api.model.ProposalStatus;
import app.tendereval.backend.api.model.ProposalVersion;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.ApiException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Proposal versions: one current version per vendor, files linked to stored documents. */
@Service
public class ProposalService {

  /** A file the upload stored: its document version and the name the client sent. */
  public record Uploaded(UUID documentVersionId) {}

  private final AuditService audit;

  public ProposalService(AuditService audit) {
    this.audit = audit;
  }

  public Mono<List<ProposalVersion>> list(DatabaseClient db, UUID tenderVendorId) {
    return db.sql("SELECT 1 AS x FROM tender_vendors WHERE id = :id")
        .bind("id", tenderVendorId)
        .map((r, m) -> 1)
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("vendor of this tender")))
        .thenMany(
            db.sql(
                    "SELECT pv.id, pv.tender_vendor_id, pv.version_no, pv.status, pv.change_reason,"
                        + " pv.uploaded_at, pv.uploaded_by, u.display_name"
                        + " FROM proposal_versions pv LEFT JOIN users u ON u.id = pv.uploaded_by"
                        + " WHERE pv.tender_vendor_id = :id ORDER BY pv.version_no DESC")
                .bind("id", tenderVendorId)
                .map(
                    (r, m) -> {
                      UUID by = r.get("uploaded_by", UUID.class);
                      String name = r.get("display_name", String.class);
                      return new ProposalVersion()
                          .id(r.get("id", UUID.class))
                          .tenderVendorId(r.get("tender_vendor_id", UUID.class))
                          .versionNo(r.get("version_no", Integer.class))
                          .status(ProposalStatus.fromValue(r.get("status", String.class)))
                          .changeReason(r.get("change_reason", String.class))
                          .uploadedAt(r.get("uploaded_at", OffsetDateTime.class))
                          .uploadedBy(
                              by == null || name == null
                                  ? null
                                  : new UserRef().id(by).displayName(name))
                          .files(new ArrayList<>());
                    })
                .all())
        .collectList()
        .flatMap(versions -> withFiles(db, versions));
  }

  private Mono<List<ProposalVersion>> withFiles(DatabaseClient db, List<ProposalVersion> versions) {
    if (versions.isEmpty()) {
      return Mono.just(versions);
    }
    return db.sql(
            "SELECT f.proposal_version_id, f.file_role, v.id AS dv_id, v.file_name, v.status,"
                + " v.failure_reason, v.page_count FROM proposal_version_files f"
                + " JOIN document_versions v ON v.id = f.document_version_id"
                + " WHERE f.proposal_version_id IN (:ids) ORDER BY f.sort_order, f.created_at")
        .bind("ids", versions.stream().map(ProposalVersion::getId).toList())
        .map(
            (r, m) -> {
              String reason = r.get("failure_reason", String.class);
              ProposalFile file =
                  new ProposalFile()
                      .documentVersionId(r.get("dv_id", UUID.class))
                      .fileName(r.get("file_name", String.class))
                      .role(FileRole.fromValue(r.get("file_role", String.class)))
                      .status(DocumentStatus.fromValue(r.get("status", String.class)))
                      .failureReason(reason == null ? null : FailureReason.fromValue(reason))
                      .pageCount(r.get("page_count", Integer.class));
              return Map.entry(r.get("proposal_version_id", UUID.class), file);
            })
        .all()
        .collectList()
        .map(
            entries -> {
              for (var e : entries) {
                versions.stream()
                    .filter(v -> v.getId().equals(e.getKey()))
                    .findFirst()
                    .ifPresent(v -> v.getFiles().add(e.getValue()));
              }
              return versions;
            });
  }

  /**
   * Links stored documents to a new proposal version and supersedes the current one. {@code roles}
   * is in the order the files were sent; exactly one file of the new version is the main one.
   */
  public Mono<ProposalVersion> create(
      DatabaseClient db,
      CurrentUser actor,
      UUID tenderVendorId,
      List<UUID> uploaded,
      List<FileRole> roles,
      List<UUID> reuse,
      String reason) {
    if (uploaded.isEmpty() && reuse.isEmpty()) {
      return Mono.error(ApiException.invalid("files", "required", "Send at least one file"));
    }
    if (roles.size() != uploaded.size()) {
      return Mono.error(
          ApiException.invalid("file_roles", "mismatch", "Send one role for each file"));
    }
    return db.sql(
            "SELECT tv.tender_id, (SELECT count(*) FROM tenders t WHERE t.id = tv.tender_id"
                + " AND t.archived_at IS NOT NULL) AS archived FROM tender_vendors tv"
                + " WHERE tv.id = :id AND tv.removed_at IS NULL FOR UPDATE")
        .bind("id", tenderVendorId)
        .map((r, m) -> new Object[] {r.get("tender_id", UUID.class), r.get("archived", Long.class)})
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("vendor of this tender")))
        .flatMap(
            tv -> {
              if (((Long) tv[1]) > 0) {
                return Mono.error(
                    ApiException.conflict("tender_archived", "Archived tenders are read-only"));
              }
              return current(db, tenderVendorId)
                  .flatMap(
                      prev ->
                          link(
                              db,
                              actor,
                              tenderVendorId,
                              (UUID) tv[0],
                              prev,
                              uploaded,
                              roles,
                              reuse,
                              reason));
            });
  }

  private record Current(UUID id, int versionNo, boolean none) {}

  private Mono<Current> current(DatabaseClient db, UUID tenderVendorId) {
    return db.sql(
            "SELECT id, version_no FROM proposal_versions WHERE tender_vendor_id = :tv"
                + " ORDER BY version_no DESC LIMIT 1 FOR UPDATE")
        .bind("tv", tenderVendorId)
        .map(
            (r, m) ->
                new Current(r.get("id", UUID.class), r.get("version_no", Integer.class), false))
        .one()
        .defaultIfEmpty(new Current(null, 0, true));
  }

  private Mono<ProposalVersion> link(
      DatabaseClient db,
      CurrentUser actor,
      UUID tenderVendorId,
      UUID tenderId,
      Current prev,
      List<UUID> uploaded,
      List<FileRole> roles,
      List<UUID> reuse,
      String reason) {
    boolean newMain = roles.contains(FileRole.MAIN);
    Mono<List<Object[]>> reused =
        reuse.isEmpty() || prev.none()
            ? Mono.just(List.<Object[]>of())
            : db.sql(
                    "SELECT document_version_id, file_role FROM proposal_version_files"
                        + " WHERE proposal_version_id = :p AND document_version_id IN (:ids)")
                .bind("p", prev.id())
                .bind("ids", reuse)
                .map(
                    (r, m) ->
                        new Object[] {
                          r.get("document_version_id", UUID.class), r.get("file_role", String.class)
                        })
                .all()
                .collectList();
    return reused.flatMap(
        kept -> {
          if (kept.size() != reuse.size()) {
            return Mono.error(
                ApiException.invalid(
                    "reuse_document_version_ids",
                    "unknown",
                    "A file to keep is not part of the current version"));
          }
          long mains =
              roles.stream().filter(r -> r == FileRole.MAIN).count()
                  + (newMain ? 0 : kept.stream().filter(k -> "main".equals(k[1])).count());
          if (mains != 1) {
            return Mono.error(
                ApiException.invalid("file_roles", "one_main", "Choose exactly one main file"));
          }
          Mono<Void> supersede =
              prev.none()
                  ? Mono.empty()
                  : db.sql(
                          "UPDATE proposal_versions SET status = 'superseded',"
                              + " superseded_at = now() WHERE id = :id AND status <> 'superseded'")
                      .bind("id", prev.id())
                      .fetch()
                      .rowsUpdated()
                      .then();
          return supersede
              .then(
                  db.sql(
                          "INSERT INTO proposal_versions (tender_vendor_id, version_no, change_reason,"
                              + " uploaded_by) VALUES (:tv, :n, :r, :u) RETURNING id")
                      .bind("tv", tenderVendorId)
                      .bind("n", prev.versionNo() + 1)
                      .bind("u", actor.id())
                      .bind("r", reason == null ? "" : reason.trim())
                      .map((r, m) -> r.get("id", UUID.class))
                      .one())
              .flatMap(
                  pvId -> {
                    Mono<Void> files = Mono.empty();
                    int order = 0;
                    for (int i = 0; i < uploaded.size(); i++) {
                      files =
                          files.then(
                              insertFile(
                                  db, pvId, uploaded.get(i), roles.get(i).getValue(), order++));
                    }
                    for (Object[] k : kept) {
                      String role = newMain && "main".equals(k[1]) ? "annex" : (String) k[1];
                      files = files.then(insertFile(db, pvId, (UUID) k[0], role, order++));
                    }
                    // rows reviewed against the replaced version become Outdated (UX-VND-05)
                    Mono<Void> outdate =
                        prev.none()
                            ? Mono.empty()
                            : db.sql(
                                    "SELECT app_mark_rows_outdated(:v, 'proposal_changed', NULL, :n)"
                                        + " AS n")
                                .bind("v", tenderVendorId)
                                .bind(
                                    "n",
                                    reason == null || reason.trim().length() < 10
                                        ? "A new proposal version was uploaded"
                                        : reason.trim())
                                .map((r, m) -> r.get("n", Integer.class))
                                .one()
                                .then();
                    return files
                        .then(outdate)
                        .then(
                            audit.record(
                                db,
                                actor,
                                AuditEntry.of("proposal_version_uploaded", "proposal_version", pvId)
                                    .tender(tenderId)
                                    .vendor(tenderVendorId)
                                    .created(
                                        Map.of(
                                            "version_no",
                                            prev.versionNo() + 1,
                                            "files",
                                            uploaded.size() + kept.size()))
                                    .build()))
                        .then(
                            list(db, tenderVendorId)
                                .map(
                                    l ->
                                        l.stream()
                                            .filter(v -> v.getId().equals(pvId))
                                            .findFirst()
                                            .orElseThrow()));
                  });
        });
  }

  private Mono<Void> insertFile(DatabaseClient db, UUID pv, UUID dv, String role, int order) {
    return db.sql(
            "INSERT INTO proposal_version_files (proposal_version_id, document_version_id,"
                + " file_role, sort_order) VALUES (:p, :d, :r, :o)")
        .bind("p", pv)
        .bind("d", dv)
        .bind("r", role)
        .bind("o", order)
        .fetch()
        .rowsUpdated()
        .then();
  }

  /** Settles files that were stored for an upload that did not complete. */
  public Mono<Void> discard(DatabaseClient db, CurrentUser actor, List<UUID> documentVersions) {
    if (documentVersions.isEmpty()) {
      return Mono.empty();
    }
    return db.sql(
            "UPDATE jobs SET status = 'cancelled', cancelled_by = :u, finished_at = now()"
                + " WHERE target_id IN (:ids) AND status IN ('queued', 'running')")
        .bind("u", actor.id())
        .bind("ids", documentVersions)
        .fetch()
        .rowsUpdated()
        .then(
            db.sql(
                    "UPDATE document_versions SET deleted_at = now(), deleted_by = :u"
                        + " WHERE id IN (:ids) AND deleted_at IS NULL")
                .bind("u", actor.id())
                .bind("ids", documentVersions)
                .fetch()
                .rowsUpdated())
        .then(
            db.sql(
                    "UPDATE documents SET deleted_at = now(), deleted_by = :u WHERE id IN ("
                        + " SELECT document_id FROM document_versions WHERE id IN (:ids))"
                        + " AND deleted_at IS NULL")
                .bind("u", actor.id())
                .bind("ids", documentVersions)
                .fetch()
                .rowsUpdated())
        .then();
  }
}
