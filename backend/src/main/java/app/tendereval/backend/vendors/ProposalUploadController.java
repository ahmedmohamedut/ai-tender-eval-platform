package app.tendereval.backend.vendors;

import app.tendereval.backend.api.model.DocType;
import app.tendereval.backend.api.model.FileRole;
import app.tendereval.backend.api.model.ProposalVersion;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.documents.UploadService;
import app.tendereval.backend.web.ApiException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePartEvent;
import org.springframework.http.codec.multipart.FormPartEvent;
import org.springframework.http.codec.multipart.PartEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code POST /tender-vendors/{id}/proposal-versions} (UX-VND-04, UX-VND-05): several files, each
 * streamed to storage as it arrives (like a document upload), then one transaction links them to a
 * new proposal version and supersedes the old one. Files stored for an upload that does not
 * complete are discarded. Fields may come before or after the files.
 */
@RestController
public class ProposalUploadController {

  private final RequestTransaction tx;
  private final UploadService uploads;
  private final ProposalService proposals;

  public ProposalUploadController(
      RequestTransaction tx, UploadService uploads, ProposalService proposals) {
    this.tx = tx;
    this.uploads = uploads;
    this.proposals = proposals;
  }

  @PostMapping(
      path = "/tender-vendors/{tenderVendorId}/proposal-versions",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
  public Mono<ResponseEntity<ProposalVersion>> upload(
      @PathVariable UUID tenderVendorId, @RequestBody Flux<PartEvent> parts) {
    List<UUID> stored = new ArrayList<>();
    List<String> roles = new ArrayList<>();
    List<UUID> reuse = new ArrayList<>();
    String[] reason = new String[1];

    Mono<UUID> tenderOf =
        tx.inRequestTransaction(
            db ->
                db.sql("SELECT tender_id FROM tender_vendors WHERE id = :id AND removed_at IS NULL")
                    .bind("id", tenderVendorId)
                    .map((r, m) -> r.get("tender_id", UUID.class))
                    .one()
                    .switchIfEmpty(Mono.error(ApiException.notFound("vendor of this tender"))));

    return tenderOf
        .flatMap(
            tender ->
                parts
                    .windowUntil(PartEvent::isLast)
                    .concatMap(
                        window ->
                            window.switchOnFirst(
                                (signal, part) -> {
                                  PartEvent first = signal.get();
                                  if (first == null) {
                                    return Flux.<Object>empty();
                                  }
                                  if (first instanceof FilePartEvent file
                                      && "files".equals(file.name())) {
                                    String name = file.filename();
                                    return uploads
                                        .upload(
                                            new UploadService.Request(
                                                tender,
                                                null,
                                                DocType.PROPOSAL,
                                                baseName(name),
                                                null,
                                                null),
                                            name,
                                            part.map(PartEvent::content),
                                            -1)
                                        .doOnNext(d -> stored.add(d.getCurrentVersion().getId()))
                                        .flux()
                                        .cast(Object.class);
                                  }
                                  if (first instanceof FormPartEvent) {
                                    return part.map(
                                            e -> {
                                              FormPartEvent f = (FormPartEvent) e;
                                              switch (f.name()) {
                                                case "file_roles" -> {
                                                  String role = f.value().trim();
                                                  try {
                                                    FileRole.fromValue(role);
                                                  } catch (IllegalArgumentException x) {
                                                    // refused before any file is stored
                                                    throw ApiException.invalid(
                                                        "file_roles",
                                                        "invalid",
                                                        "A role is main or annex");
                                                  }
                                                  roles.add(role);
                                                }
                                                case "reuse_document_version_ids" ->
                                                    reuse.add(uuid(f.value().trim()));
                                                case "change_reason" -> reason[0] = f.value();
                                                default -> {}
                                              }
                                              return (Object) f;
                                            })
                                        .then(Mono.empty())
                                        .flux();
                                  }
                                  return part.doOnNext(e -> DataBufferUtils.release(e.content()))
                                      .then(Mono.empty())
                                      .flux();
                                }))
                    .then()
                    .then(
                        Mono.defer(
                            () -> {
                              List<FileRole> parsed = new ArrayList<>();
                              try {
                                for (String r : roles) {
                                  parsed.add(FileRole.fromValue(r));
                                }
                              } catch (IllegalArgumentException e) {
                                return Mono.error(
                                    ApiException.invalid(
                                        "file_roles", "invalid", "A role is main or annex"));
                              }
                              return tx.asUser(
                                  (db, user) ->
                                      proposals.create(
                                          db,
                                          user,
                                          tenderVendorId,
                                          stored,
                                          parsed,
                                          reuse,
                                          reason[0]));
                            }))
                    // Whatever failed, files stored so far are not left behind.
                    .onErrorResume(
                        e ->
                            tx.asUser((db, user) -> proposals.discard(db, user, stored))
                                .onErrorResume(x -> Mono.empty())
                                .then(Mono.<ProposalVersion>error(e))))
        .map(
            v ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .location(
                        URI.create(
                            "/api/v1/tender-vendors/" + tenderVendorId + "/proposal-versions"))
                    .body(v));
  }

  private static UUID uuid(String v) {
    try {
      return UUID.fromString(v);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid("reuse_document_version_ids", "invalid", "Not a valid identifier");
    }
  }

  private static String baseName(String fileName) {
    if (fileName == null) {
      return "Proposal file";
    }
    int dot = fileName.lastIndexOf('.');
    String base = dot > 0 ? fileName.substring(0, dot) : fileName;
    return base.isBlank() ? "Proposal file" : base;
  }
}
