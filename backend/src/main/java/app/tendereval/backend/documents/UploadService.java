package app.tendereval.backend.documents;

import app.tendereval.backend.api.model.DocType;
import app.tendereval.backend.api.model.Document;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.common.RateLimiter;
import app.tendereval.backend.common.Settings;
import app.tendereval.backend.config.AppProperties;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.storage.ObjectStorage;
import app.tendereval.backend.storage.PartAccumulator;
import app.tendereval.backend.web.ApiException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.model.CompletedPart;

/**
 * The upload path of PI 4.4: checks, one transaction that records the version and its {@code
 * document_processing} job, a streamed multipart upload in fixed-size parts hashed on the way, then
 * the completing update. The browser's bytes are never written to the local disk.
 *
 * <p>No command is published: RustFS announces the object ({@code documents.uploaded}); the
 * watchdog republishes the command when that event is lost (P2-2).
 */
@Component
public class UploadService {

  private static final Logger log = LoggerFactory.getLogger(UploadService.class);

  /** The file's own signature decides the format, never the client's name or type (PI 4.2). */
  enum Format {
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    final String extension;
    final String contentType;

    Format(String extension, String contentType) {
      this.extension = extension;
      this.contentType = contentType;
    }
  }

  /** What the caller sent: text fields first, then the file. */
  public record Request(
      UUID tenderId,
      UUID documentId,
      DocType docType,
      String title,
      UUID parentDocumentId,
      String note) {}

  private record Prepared(UUID documentId, UUID versionId, UUID jobId, int versionNo, String key) {}

  private final RequestTransaction tx;
  private final ObjectStorage storage;
  private final AuditService audit;
  private final DocumentService documents;
  private final RateLimiter limiter;
  private final AppProperties props;

  public UploadService(
      RequestTransaction tx,
      ObjectStorage storage,
      AuditService audit,
      DocumentService documents,
      RateLimiter limiter,
      AppProperties props) {
    this.tx = tx;
    this.storage = storage;
    this.audit = audit;
    this.documents = documents;
    this.limiter = limiter;
    this.props = props;
  }

  /** Stored document types: a proposal is uploaded through its vendor (PG-05). */
  private static final java.util.Set<DocType> TENDER_TYPES =
      java.util.Set.of(DocType.RFP, DocType.BRD, DocType.ADDENDUM, DocType.OTHER);

  /**
   * Streams {@code content} into object storage and returns the stored document.
   *
   * @param declaredLength the request's Content-Length, or -1
   */
  public Mono<Document> upload(
      Request req, String fileName, Flux<DataBuffer> content, long declaredLength) {
    return RequestTransaction.currentUser()
        .flatMap(
            actor -> {
              limiter.check(actor.id(), RateLimiter.Bucket.UPLOADS);
              return tx.inRequestTransaction(db -> Settings.load(db))
                  .flatMap(
                      settings -> {
                        long max = settings.uploadMaxBytes();
                        // The envelope adds a few hundred bytes; the stream counts exactly.
                        if (declaredLength > max + 64 * 1024) {
                          return Mono.<Document>error(tooLarge(max));
                        }
                        return stream(actor, req, fileName, content, max);
                      });
            });
  }

  private static ApiException tooLarge(long max) {
    return new ApiException(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "file_too_large",
            "The file is larger than the upload limit")
        .with("max_bytes", max);
  }

  private Mono<Document> stream(
      CurrentUser actor, Request req, String fileName, Flux<DataBuffer> content, long max) {
    int partSize = props.s3MultipartPartMb() * 1024 * 1024;
    MessageDigest digest = sha256();
    AtomicLong size = new AtomicLong();

    Flux<byte[]> parts =
        Flux.defer(
            () -> {
              PartAccumulator acc = new PartAccumulator(partSize);
              return content
                  .<byte[]>handle(
                      (buffer, sink) -> {
                        try {
                          byte[] bytes = new byte[buffer.readableByteCount()];
                          buffer.read(bytes);
                          if (size.addAndGet(bytes.length) > max) {
                            sink.error(tooLarge(max));
                            return;
                          }
                          digest.update(bytes);
                          acc.add(bytes).forEach(sink::next);
                        } finally {
                          DataBufferUtils.release(buffer);
                        }
                      })
                  .concatWith(Mono.fromSupplier(acc::rest).filter(rest -> rest.length > 0));
            });

    return parts
        .switchOnFirst(
            (signal, flux) -> {
              if (signal.isOnError()) {
                return Mono.<Document>error(signal.getThrowable());
              }
              if (!signal.hasValue()) {
                return Mono.<Document>error(
                    ApiException.invalid("file", "empty", "The file is empty"));
              }
              Format format = detect(signal.get(), fileName);
              return transfer(actor, req, fileName, format, flux, digest, size);
            })
        .next()
        .switchIfEmpty(
            Mono.error(
                ApiException.invalid("file", "required", "Send the file in the field named file")));
  }

  private Mono<Document> transfer(
      CurrentUser actor,
      Request req,
      String fileName,
      Format format,
      Flux<byte[]> parts,
      MessageDigest digest,
      AtomicLong size) {
    return tx.inRequestTransaction(db -> prepare(db, actor, req, fileName, format))
        .flatMap(
            prepared -> {
              String bucket = props.s3BucketDocuments();
              return storage
                  .createMultipart(bucket, prepared.key(), format.contentType)
                  .flatMap(
                      uploadId -> {
                        AtomicInteger number = new AtomicInteger();
                        List<CompletedPart> done = new ArrayList<>();
                        return parts
                            .concatMap(
                                part ->
                                    storage
                                        .uploadPart(
                                            bucket,
                                            prepared.key(),
                                            uploadId,
                                            number.incrementAndGet(),
                                            part)
                                        .doOnNext(done::add),
                                1)
                            .then(
                                Mono.defer(
                                    () -> storage.complete(bucket, prepared.key(), uploadId, done)))
                            .then(
                                Mono.defer(
                                    () ->
                                        tx.inRequestTransaction(
                                            db ->
                                                finish(
                                                    db,
                                                    prepared,
                                                    size.get(),
                                                    HexFormat.of().formatHex(digest.digest())))))
                            .onErrorResume(
                                e ->
                                    storage
                                        .abort(bucket, prepared.key(), uploadId)
                                        .then(abandon(prepared, e))
                                        .then(Mono.error(e)));
                      })
                  .onErrorResume(
                      e ->
                          e instanceof ApiException
                              ? Mono.error(e)
                              : abandon(prepared, e)
                                  .then(
                                      Mono.error(
                                          new ApiException(
                                              HttpStatus.BAD_GATEWAY,
                                              "storage_unavailable",
                                              "The file could not be stored. Try again"))));
            })
        .flatMap(
            prepared -> tx.inRequestTransaction(db -> documents.get(db, prepared.documentId())));
  }

  // ---- step 2: the row and its job, one transaction (PI 4.4) --------------------------------

  private Mono<Prepared> prepare(
      DatabaseClient db, CurrentUser actor, Request req, String fileName, Format format) {
    Mono<UUID> tenderOf =
        req.documentId() == null
            ? Mono.just(req.tenderId())
            : db.sql("SELECT tender_id FROM documents WHERE id = :d AND deleted_at IS NULL")
                .bind("d", req.documentId())
                .map((r, m) -> r.get("tender_id", UUID.class))
                .one()
                .switchIfEmpty(Mono.error(ApiException.notFound("The document")));
    return tenderOf.flatMap(
        tenderId ->
            db.sql("SELECT archived_at FROM tenders WHERE id = :t AND deleted_at IS NULL")
                .bind("t", tenderId)
                .map((r, m) -> r.get("archived_at", java.time.OffsetDateTime.class) != null)
                .one()
                .switchIfEmpty(Mono.error(ApiException.notFound("The tender")))
                .flatMap(
                    archived -> {
                      if (archived) {
                        return Mono.<Prepared>error(
                            ApiException.conflict(
                                "tender_archived", "Archived tenders are read-only"));
                      }
                      return req.documentId() == null
                          ? newDocument(db, actor, req, tenderId, fileName, format)
                          : newVersion(db, actor, req, fileName, format);
                    }));
  }

  private Mono<Prepared> newDocument(
      DatabaseClient db,
      CurrentUser actor,
      Request req,
      UUID tenderId,
      String fileName,
      Format format) {
    if (!TENDER_TYPES.contains(req.docType())) {
      return Mono.error(
          ApiException.invalid(
              "doc_type", "not_allowed", "Proposal files are uploaded for a vendor"));
    }
    Mono<Void> parentCheck =
        req.docType() == DocType.ADDENDUM
            ? (req.parentDocumentId() == null
                ? Mono.error(
                    ApiException.invalid(
                        "parent_document_id", "required", "An addendum belongs to the RFP"))
                : db.sql(
                        "SELECT 1 AS x FROM documents WHERE id = :p AND tender_id = :t"
                            + " AND doc_type = 'rfp' AND deleted_at IS NULL")
                    .bind("p", req.parentDocumentId())
                    .bind("t", req.tenderId())
                    .map((r, m) -> 1)
                    .one()
                    .switchIfEmpty(
                        Mono.error(
                            ApiException.invalid(
                                "parent_document_id",
                                "not_the_rfp",
                                "Choose the RFP of this tender")))
                    .then())
            : Mono.empty();
    Mono<Void> rfpCheck =
        req.docType() == DocType.RFP
            ? db.sql(
                    "SELECT 1 AS x FROM documents WHERE tender_id = :t AND doc_type = 'rfp'"
                        + " AND deleted_at IS NULL")
                .bind("t", tenderId)
                .map((r, m) -> 1)
                .one()
                .flatMap(
                    x ->
                        Mono.<Void>error(
                            ApiException.conflict(
                                "rfp_exists",
                                "This tender already has an RFP. Upload a new version of it")))
            : Mono.empty();
    String title =
        req.title() == null || req.title().isBlank() ? baseName(fileName) : req.title().trim();
    return parentCheck
        .then(rfpCheck)
        .then(Mono.defer(() -> insertDocument(db, req.tenderId(), req, title)))
        .flatMap(
            documentId ->
                insertVersion(
                    db,
                    actor,
                    req.tenderId(),
                    documentId,
                    1,
                    fileName,
                    format,
                    "document_uploaded",
                    Map.of("title", title, "doc_type", req.docType().getValue())));
  }

  private Mono<UUID> insertDocument(DatabaseClient db, UUID tenderId, Request req, String title) {
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
                "INSERT INTO documents (tender_id, doc_type, title, parent_document_id)"
                    + " VALUES (:t, :d, :title, :p) RETURNING id")
            .bind("t", tenderId)
            .bind("d", req.docType().getValue())
            .bind("title", title);
    spec =
        req.parentDocumentId() == null
            ? spec.bindNull("p", UUID.class)
            : spec.bind("p", req.parentDocumentId());
    return spec.map((r, m) -> r.get("id", UUID.class)).one();
  }

  private Mono<Prepared> newVersion(
      DatabaseClient db, CurrentUser actor, Request req, String fileName, Format format) {
    return db.sql(
            "SELECT d.tender_id, d.doc_type, coalesce(max(v.version_no), 0) AS last_no"
                + " FROM documents d LEFT JOIN document_versions v ON v.document_id = d.id"
                + " AND v.deleted_at IS NULL WHERE d.id = :d AND d.deleted_at IS NULL"
                + " GROUP BY d.tender_id, d.doc_type")
        .bind("d", req.documentId())
        .map(
            (r, m) ->
                new Object[] {
                  r.get("tender_id", UUID.class),
                  r.get("doc_type", String.class),
                  r.get("last_no", Integer.class)
                })
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("The document")))
        .flatMap(
            row -> {
              if ("proposal".equals(row[1])) {
                return Mono.<Prepared>error(
                    ApiException.invalid(
                        "document", "not_allowed", "Proposal versions are uploaded for a vendor"));
              }
              // Phase 3: refused with baseline_locked once the baseline is confirmed and not
              // reopened.
              return insertVersion(
                  db,
                  actor,
                  (UUID) row[0],
                  req.documentId(),
                  ((Integer) row[2]) + 1,
                  fileName,
                  format,
                  "document_version_uploaded",
                  req.note() == null ? Map.of() : Map.of("note", req.note()));
            });
  }

  private Mono<Prepared> insertVersion(
      DatabaseClient db,
      CurrentUser actor,
      UUID tenderId,
      UUID documentId,
      int versionNo,
      String fileName,
      Format format,
      String action,
      Map<String, Object> extra) {
    UUID versionId = UUID.randomUUID();
    String key =
        "originals/%s/%s/%s.%s".formatted(tenderId, documentId, versionId, format.extension);
    return db.sql(
            "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format,"
                + " storage_key, uploaded_by) VALUES (:id, :d, :n, :f, :fmt, :k, :u)")
        .bind("id", versionId)
        .bind("d", documentId)
        .bind("n", versionNo)
        .bind("f", fileName)
        .bind("fmt", format.extension)
        .bind("k", key)
        .bind("u", actor.id())
        .fetch()
        .rowsUpdated()
        .then(
            db.sql(
                    "INSERT INTO jobs (tender_id, job_type, target_type, target_id, started_by)"
                        + " VALUES (:t, 'document_processing', 'document_version', :v, :u) RETURNING id")
                .bind("t", tenderId)
                .bind("v", versionId)
                .bind("u", actor.id())
                .map((r, m) -> r.get("id", UUID.class))
                .one())
        .flatMap(
            jobId -> {
              Map<String, Object> after = new java.util.LinkedHashMap<>(extra);
              after.put("file_name", fileName);
              after.put("file_format", format.extension);
              after.put("version_no", versionNo);
              after.put("job_id", jobId.toString());
              return audit
                  .record(
                      db,
                      actor,
                      AuditEntry.of(action, "document_version", versionId)
                          .tender(tenderId)
                          .created(after)
                          .build())
                  .thenReturn(new Prepared(documentId, versionId, jobId, versionNo, key));
            });
  }

  // ---- step 4: the completed upload
  // ---------------------------------------------------------------

  private Mono<Prepared> finish(DatabaseClient db, Prepared p, long size, String sha256) {
    return db.sql(
            "UPDATE document_versions SET size_bytes = :s, sha256 = :h, status = 'processing'"
                + " WHERE id = :id AND status = 'uploading'")
        .bind("s", size)
        .bind("h", sha256)
        .bind("id", p.versionId())
        .fetch()
        .rowsUpdated()
        .thenReturn(p);
  }

  /**
   * A stream that broke or outgrew the limit: nothing is stored, the version is soft-deleted and
   * its job closed (PI 4.4). A new document with no other version goes with it.
   */
  private Mono<Void> abandon(Prepared p, Throwable cause) {
    log.warn(
        "upload abandoned version={} reason={}", p.versionId(), cause.getClass().getSimpleName());
    return RequestTransaction.currentUser()
        .flatMap(
            actor ->
                tx.inRequestTransaction(
                    db ->
                        db.sql(
                                "UPDATE document_versions SET deleted_at = now(), deleted_by = :u"
                                    + " WHERE id = :id")
                            .bind("u", actor.id())
                            .bind("id", p.versionId())
                            .fetch()
                            .rowsUpdated()
                            .then(
                                db.sql(
                                        "UPDATE jobs SET status = 'failed', failure_code = 'other',"
                                            + " failure_message = 'The upload did not complete',"
                                            + " finished_at = now() WHERE id = :j"
                                            + " AND status = 'queued'")
                                    .bind("j", p.jobId())
                                    .fetch()
                                    .rowsUpdated())
                            .then(
                                db.sql(
                                        "UPDATE documents SET deleted_at = now(), deleted_by = :u"
                                            + " WHERE id = :d AND NOT EXISTS (SELECT 1 FROM"
                                            + " document_versions WHERE document_id = :d"
                                            + " AND deleted_at IS NULL)")
                                    .bind("u", actor.id())
                                    .bind("d", p.documentId())
                                    .fetch()
                                    .rowsUpdated())
                            .then(
                                audit.recordSystem(
                                    db,
                                    AuditEntry.of("job_status_changed", "job", p.jobId())
                                        .changed(
                                            Map.of("status", "queued"), Map.of("status", "failed"))
                                        .note("The upload did not complete")
                                        .build()))))
        .onErrorResume(e -> Mono.empty());
  }

  // ---- helpers
  // ---------------------------------------------------------------------------------------

  /** PDF starts with %PDF-; a DOCX is a ZIP (PK 03 04). The extension must agree. */
  static Format detect(byte[] head, String fileName) {
    String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
    boolean pdf =
        head.length >= 5
            && head[0] == '%'
            && head[1] == 'P'
            && head[2] == 'D'
            && head[3] == 'F'
            && head[4] == '-';
    boolean zip =
        head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
    if (pdf && lower.endsWith(".pdf")) {
      return Format.PDF;
    }
    if (zip && lower.endsWith(".docx")) {
      return Format.DOCX;
    }
    throw new ApiException(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "unsupported_format",
        "Only PDF and DOCX files are supported. Convert the file and upload it again");
  }

  private static String baseName(String fileName) {
    String n = fileName == null ? "Document" : fileName;
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
