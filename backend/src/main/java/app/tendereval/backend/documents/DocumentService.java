package app.tendereval.backend.documents;

import app.tendereval.backend.api.model.DocType;
import app.tendereval.backend.api.model.Document;
import app.tendereval.backend.api.model.DocumentImpact;
import app.tendereval.backend.api.model.DocumentPage;
import app.tendereval.backend.api.model.DocumentStatus;
import app.tendereval.backend.api.model.DocumentVersion;
import app.tendereval.backend.api.model.FailureReason;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.common.Cursor;
import app.tendereval.backend.common.Keyset;
import app.tendereval.backend.common.Sql;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.ApiException;
import io.r2dbc.spi.Row;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Reading documents and their versions, and the impact decision of UX-DOC-04. */
@Component
public class DocumentService {

  private static final Map<String, Keyset.Field> SORTS =
      Map.of(
          "created_at", new Keyset.Field("d.created_at", Keyset.Type.TIMESTAMP),
          "title", new Keyset.Field("lower(d.title)", Keyset.Type.TEXT));

  private final AuditService audit;

  public DocumentService(AuditService audit) {
    this.audit = audit;
  }

  private static final String VERSION_SELECT =
      "SELECT v.id, v.document_id, v.version_no, v.file_name, v.file_format, v.size_bytes, v.status,"
          + " v.failure_reason, v.page_count, v.uploaded_by, v.superseded_at, v.created_at,"
          + " u.display_name AS uploader,"
          + " (SELECT j.id FROM jobs j WHERE j.target_id = v.id AND j.job_type = 'document_processing'"
          + "   ORDER BY j.queued_at DESC LIMIT 1) AS job_id,"
          + " (SELECT j.failure_message FROM jobs j WHERE j.target_id = v.id"
          + "   AND j.job_type = 'document_processing' ORDER BY j.queued_at DESC LIMIT 1) AS failure_message"
          + " FROM document_versions v LEFT JOIN users u ON u.id = v.uploaded_by";

  static DocumentVersion toVersion(Row r) {
    UUID by = r.get("uploaded_by", UUID.class);
    String reason = r.get("failure_reason", String.class);
    Long size = r.get("size_bytes", Long.class);
    return new DocumentVersion()
        .id(r.get("id", UUID.class))
        .documentId(r.get("document_id", UUID.class))
        .versionNo(r.get("version_no", Integer.class))
        .fileName(r.get("file_name", String.class))
        .fileFormat(r.get("file_format", String.class))
        .sizeBytes(size == null ? 0 : (int) Math.min(Integer.MAX_VALUE, size))
        .status(DocumentStatus.fromValue(r.get("status", String.class)))
        .failureReason(reason == null ? null : FailureReason.fromValue(reason))
        .failureMessage(r.get("failure_message", String.class))
        .pageCount(r.get("page_count", Integer.class))
        .uploadedBy(
            by == null || r.get("uploader", String.class) == null
                ? null
                : new UserRef().id(by).displayName(r.get("uploader", String.class)))
        .uploadedAt(r.get("created_at", OffsetDateTime.class))
        .supersededAt(r.get("superseded_at", OffsetDateTime.class))
        .jobId(r.get("job_id", UUID.class));
  }

  Mono<List<DocumentVersion>> versionsOf(DatabaseClient db, List<UUID> documentIds) {
    if (documentIds.isEmpty()) {
      return Mono.just(List.of());
    }
    return db.sql(
            VERSION_SELECT
                + " WHERE v.document_id IN (:ids) AND v.deleted_at IS NULL"
                + " ORDER BY v.document_id, v.version_no DESC")
        .bind("ids", documentIds)
        .map((r, m) -> toVersion(r))
        .all()
        .collectList();
  }

  private record DocRow(
      UUID id,
      UUID tenderId,
      String docType,
      String title,
      UUID parent,
      OffsetDateTime createdAt) {}

  private static DocRow toDoc(Row r) {
    return new DocRow(
        r.get("id", UUID.class),
        r.get("tender_id", UUID.class),
        r.get("doc_type", String.class),
        r.get("title", String.class),
        r.get("parent_document_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static Document compose(DocRow d, List<DocumentVersion> versions) {
    Document doc =
        new Document()
            .id(d.id())
            .tenderId(d.tenderId())
            .docType(DocType.fromValue(d.docType()))
            .title(d.title())
            .parentDocumentId(d.parent())
            .versionCount(versions.size())
            .versions(versions);
    if (!versions.isEmpty()) {
      doc.currentVersion(versions.get(0));
    }
    return doc;
  }

  public Mono<Document> get(DatabaseClient db, UUID documentId) {
    return db.sql(
            "SELECT id, tender_id, doc_type, title, parent_document_id, created_at FROM documents"
                + " WHERE id = :id AND deleted_at IS NULL")
        .bind("id", documentId)
        .map((r, m) -> toDoc(r))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("The document")))
        .flatMap(d -> versionsOf(db, List.of(d.id())).map(v -> compose(d, v)));
  }

  public Mono<DocumentVersion> getVersion(DatabaseClient db, UUID versionId) {
    return db.sql(VERSION_SELECT + " WHERE v.id = :id AND v.deleted_at IS NULL")
        .bind("id", versionId)
        .map((r, m) -> toVersion(r))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("The document version")));
  }

  public Mono<DocumentPage> list(
      DatabaseClient db,
      UUID tenderId,
      DocType docType,
      Integer limitParam,
      String cursor,
      String sort) {
    int limit = Cursor.limit(limitParam);
    Keyset.Order order = Keyset.parse(sort, SORTS, "created_at");
    Sql filter = new Sql().and("d.tender_id = :t", "t", tenderId).and("d.deleted_at IS NULL");
    if (docType != null) {
      filter.and("d.doc_type = :dt", "dt", docType.getValue());
    } else {
      // Proposal files belong to vendors (PG-05), not to the tender's document list (PG-04).
      filter.and("d.doc_type <> 'proposal'");
    }
    Sql page = new Sql();
    page.binds().putAll(filter.binds());
    Cursor c = Cursor.decodeOrNull(cursor);
    String keyset = "";
    if (c != null) {
      keyset = " AND " + order.after("d.id");
      page.bind("sort_value", c.sortValue()).bind("sort_id", c.id());
    }
    Mono<List<DocRow>> rows =
        page.apply(
                db.sql(
                    "SELECT d.id, d.tender_id, d.doc_type, d.title, d.parent_document_id, d.created_at"
                        + " FROM documents d"
                        + filter.where()
                        + keyset
                        + " ORDER BY "
                        + order.orderBy("d.id")
                        + " LIMIT "
                        + (limit + 1)))
            .map((r, m) -> toDoc(r))
            .all()
            .collectList();
    Mono<Long> total =
        filter
            .apply(db.sql("SELECT count(*) AS n FROM documents d" + filter.where()))
            .map((r, m) -> r.get("n", Long.class))
            .one();
    return Mono.zip(rows, total)
        .flatMap(
            t -> {
              List<DocRow> docs = t.getT1();
              String next = null;
              if (docs.size() > limit) {
                docs = new ArrayList<>(docs.subList(0, limit));
                DocRow last = docs.get(limit - 1);
                next =
                    new Cursor(
                            order.name().equals("title")
                                ? last.title().toLowerCase()
                                : last.createdAt().toString(),
                            last.id())
                        .encode();
              }
              List<DocRow> finalDocs = docs;
              String nextCursor = next;
              return versionsOf(db, finalDocs.stream().map(DocRow::id).toList())
                  .map(
                      vs -> {
                        Map<UUID, List<DocumentVersion>> byDoc =
                            vs.stream()
                                .collect(Collectors.groupingBy(DocumentVersion::getDocumentId));
                        List<Document> items =
                            finalDocs.stream()
                                .map(d -> compose(d, byDoc.getOrDefault(d.id(), List.of())))
                                .toList();
                        return new DocumentPage()
                            .items(items)
                            .nextCursor(nextCursor)
                            .total(t.getT2().intValue());
                      });
            });
  }

  // ---- impact (UX-DOC-04) ---------------------------------------------------------------------

  /** Requirements are extracted in Phase 3, so no requirement points at a version yet. */
  public Mono<DocumentImpact> impact(DatabaseClient db, UUID versionId) {
    return db.sql("SELECT id FROM document_versions WHERE id = :id AND deleted_at IS NULL")
        .bind("id", versionId)
        .map((r, m) -> r.get("id", UUID.class))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("document version")))
        .then(
            db.sql(
                    "SELECT p.id FROM document_versions v JOIN document_versions p"
                        + " ON p.document_id = v.document_id AND p.version_no = v.version_no - 1"
                        + " WHERE v.id = :id AND v.deleted_at IS NULL AND p.deleted_at IS NULL")
                .bind("id", versionId)
                .map((r, m) -> r.get("id", UUID.class))
                .one())
        .switchIfEmpty(
            Mono.error(
                ApiException.conflict(
                    "no_previous_version", "This is the first version of the document")))
        .map(
            previous ->
                new DocumentImpact()
                    .previousVersionId(previous)
                    .requirementCount(0)
                    .decisionRequired(false));
  }

  public Mono<Void> decideImpact(
      DatabaseClient db, CurrentUser actor, UUID versionId, String choice, String note) {
    return impact(db, versionId)
        .flatMap(
            imp ->
                audit.record(
                    db,
                    actor,
                    AuditEntry.of("document_impact_decided", "document_version", versionId)
                        .created(
                            new LinkedHashMap<>(
                                Map.of(
                                    "choice",
                                    choice,
                                    "previous_version_id",
                                    imp.getPreviousVersionId().toString())))
                        .note(note == null || note.isBlank() ? null : note.trim())
                        .build()));
  }
}
