package app.tendereval.backend.api;

import app.tendereval.backend.api.model.Export;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.exports.ExportService;
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
import reactor.core.publisher.Mono;

/**
 * The Exports group (P84): follow an export and download its file. The file is streamed through the
 * API with {@code Cache-Control: no-store}; there are no signed storage URLs (rule 9).
 */
@RestController
public class ExportsController implements ExportsApi {

  private final RequestTransaction tx;
  private final ExportService exports;

  public ExportsController(RequestTransaction tx, ExportService exports) {
    this.tx = tx;
    this.exports = exports;
  }

  @Override
  public Mono<ResponseEntity<Export>> getExport(UUID exportId, ServerWebExchange exchange) {
    return tx.asUser((db, user) -> exports.get(db, user, exportId)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Resource>> downloadExport(UUID exportId, ServerWebExchange exchange) {
    return tx.asUser((db, user) -> exports.download(db, user, exportId))
        .map(
            d ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff")
                    .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(d.fileName()).build().toString())
                    .contentType(MediaType.parseMediaType(d.contentType()))
                    .contentLength(d.bytes().length)
                    .body((Resource) new ByteArrayResource(d.bytes())));
  }
}
