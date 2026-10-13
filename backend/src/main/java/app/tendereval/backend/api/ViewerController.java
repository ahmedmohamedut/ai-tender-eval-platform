package app.tendereval.backend.api;

import app.tendereval.backend.api.model.Outline;
import app.tendereval.backend.api.model.PageInfo;
import app.tendereval.backend.api.model.Passage;
import app.tendereval.backend.api.model.SearchResult;
import app.tendereval.backend.common.RateLimiter;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.viewer.ViewerService;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The Viewer group (UX-DOC-06, UX-GEN-03, UX-NFR-07). There is no download operation and no storage
 * URL: the PDF is streamed here with {@code Cache-Control: no-store} and each first open is logged.
 */
@RestController
public class ViewerController implements ViewerApi {

  private final RequestTransaction tx;
  private final ViewerService service;
  private final RateLimiter limiter;

  public ViewerController(RequestTransaction tx, ViewerService service, RateLimiter limiter) {
    this.tx = tx;
    this.service = service;
    this.limiter = limiter;
  }

  @Override
  public Mono<ResponseEntity<Outline>> getDocumentOutline(
      UUID documentVersionId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.outline(db, documentVersionId))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Flux<PageInfo>>> listDocumentPages(
      UUID documentVersionId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.pages(db, documentVersionId))
        .map(l -> ResponseEntity.ok(Flux.fromIterable(l)));
  }

  @Override
  public Mono<ResponseEntity<Flux<Passage>>> listPassages(
      UUID documentVersionId,
      Integer pageFrom,
      Integer pageTo,
      List<UUID> ids,
      ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db -> service.passages(db, documentVersionId, pageFrom, pageTo, ids))
        .map(l -> ResponseEntity.ok(Flux.fromIterable(l)));
  }

  @Override
  public Mono<ResponseEntity<Passage>> getPassage(UUID passageId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.passage(db, passageId)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<SearchResult>> searchDocument(
      UUID documentVersionId, String q, ServerWebExchange exchange) {
    return tx.asUser(
            (db, user) -> {
              limiter.check(user.id(), RateLimiter.Bucket.SEARCH);
              return service.search(db, documentVersionId, q);
            })
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Resource>> streamDocument(
      UUID documentVersionId, String range, ServerWebExchange exchange) {
    // The first request of a viewing starts at byte 0 (or has no range); later ranges are the
    // same viewing and are not logged again.
    boolean first = range == null || range.startsWith("bytes=0-");
    return tx.asUser((db, user) -> service.render(db, user, documentVersionId, first))
        .map(
            bytes ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("document.pdf").build().toString())
                    .<Resource>body(new ByteArrayResource(bytes)));
  }
}
