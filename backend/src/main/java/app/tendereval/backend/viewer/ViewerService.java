package app.tendereval.backend.viewer;

import app.tendereval.backend.api.model.Outline;
import app.tendereval.backend.api.model.PageInfo;
import app.tendereval.backend.api.model.Passage;
import app.tendereval.backend.api.model.SearchHit;
import app.tendereval.backend.api.model.SearchResult;
import app.tendereval.backend.api.model.Section;
import app.tendereval.backend.config.AppProperties;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.storage.ObjectStorage;
import app.tendereval.backend.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The document viewer's reads (UX-DOC-06, UX-GEN-03, UX-NFR-07). Every query runs as the request
 * role, so row-level security decides what a person can see: a version they may not open simply
 * does not exist for them (404). Bytes are streamed by the backend, never by a storage URL (rule
 * 9).
 */
@Service
public class ViewerService {

  private final ObjectStorage storage;
  private final AppProperties props;
  private final JsonMapper json = JsonMapper.builder().build();

  public ViewerService(ObjectStorage storage, AppProperties props) {
    this.storage = storage;
    this.props = props;
  }

  private Mono<Void> requireVersion(DatabaseClient db, UUID versionId) {
    return db.sql("SELECT 1 AS one FROM document_versions WHERE id = :id AND deleted_at IS NULL")
        .bind("id", versionId)
        .map((r, m) -> 1)
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("document version")))
        .then();
  }

  public Mono<Outline> outline(DatabaseClient db, UUID versionId) {
    return requireVersion(db, versionId)
        .then(
            db.sql(
                    "SELECT id, parent_section_id, title, level, start_page, end_page"
                        + " FROM document_sections WHERE document_version_id = :v"
                        + " ORDER BY sort_order")
                .bind("v", versionId)
                .map(
                    (r, m) ->
                        new Section()
                            .id(r.get("id", UUID.class))
                            .parentId(r.get("parent_section_id", UUID.class))
                            .title(r.get("title", String.class))
                            .level(r.get("level", Integer.class))
                            .startPage(r.get("start_page", Integer.class))
                            .endPage(r.get("end_page", Integer.class)))
                .all()
                .collectList())
        .map(sections -> new Outline().sections(sections));
  }

  public Mono<List<PageInfo>> pages(DatabaseClient db, UUID versionId) {
    return requireVersion(db, versionId)
        .then(
            db.sql(
                    "SELECT page_no, width, height FROM document_pages"
                        + " WHERE document_version_id = :v ORDER BY page_no")
                .bind("v", versionId)
                .map(
                    (r, m) ->
                        new PageInfo()
                            .pageNo(r.get("page_no", Integer.class))
                            .width(r.get("width", BigDecimal.class))
                            .height(r.get("height", BigDecimal.class)))
                .all()
                .collectList());
  }

  public Mono<List<Passage>> passages(
      DatabaseClient db, UUID versionId, Integer from, Integer to, List<UUID> ids) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, document_version_id, page_no, passage_no, section_id, text, bbox::text AS bbox"
                + " FROM passages WHERE document_version_id = :v");
    if (from != null) {
      sql.append(" AND page_no >= :from");
    }
    if (to != null) {
      sql.append(" AND page_no <= :to");
    }
    if (ids != null && !ids.isEmpty()) {
      sql.append(" AND id IN (:ids)");
    }
    sql.append(" ORDER BY page_no, passage_no LIMIT 5000");
    DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString()).bind("v", versionId);
    if (from != null) {
      spec = spec.bind("from", from);
    }
    if (to != null) {
      spec = spec.bind("to", to);
    }
    if (ids != null && !ids.isEmpty()) {
      spec = spec.bind("ids", ids);
    }
    return requireVersion(db, versionId).then(spec.map((r, m) -> passage(r)).all().collectList());
  }

  public Mono<Passage> passage(DatabaseClient db, UUID passageId) {
    return db.sql(
            "SELECT id, document_version_id, page_no, passage_no, section_id, text,"
                + " bbox::text AS bbox FROM passages WHERE id = :id")
        .bind("id", passageId)
        .map((r, m) -> passage(r))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("passage")));
  }

  private Passage passage(io.r2dbc.spi.Readable r) {
    JsonNode box = json.readTree(r.get("bbox", String.class));
    List<List<BigDecimal>> rects = new java.util.ArrayList<>();
    for (JsonNode rect : box) {
      List<BigDecimal> one = new java.util.ArrayList<>();
      for (JsonNode n : rect) {
        one.add(n.decimalValue());
      }
      rects.add(one);
    }
    return new Passage()
        .id(r.get("id", UUID.class))
        .documentVersionId(r.get("document_version_id", UUID.class))
        .pageNo(r.get("page_no", Integer.class))
        .passageNo(r.get("passage_no", Integer.class))
        .sectionId(r.get("section_id", UUID.class))
        .text(r.get("text", String.class))
        .bbox(rects);
  }

  public Mono<SearchResult> search(DatabaseClient db, UUID versionId, String q) {
    String term = q == null ? "" : q.trim();
    if (term.isEmpty() || term.length() > 200) {
      return requireVersion(db, versionId).thenReturn(new SearchResult().hits(List.of()).total(0));
    }
    return requireVersion(db, versionId)
        .then(
            db.sql(
                    "SELECT id, page_no, ts_headline('english', text, q,"
                        + " 'MaxWords=30, MinWords=10, StartSel=\"«\", StopSel=\"»\"') AS snippet,"
                        + " count(*) OVER () AS total"
                        + " FROM passages, plainto_tsquery('english', :q) q"
                        + " WHERE document_version_id = :v AND to_tsvector('english', text) @@ q"
                        + " ORDER BY ts_rank(to_tsvector('english', text), q) DESC, page_no,"
                        + " passage_no LIMIT 50")
                .bind("q", term)
                .bind("v", versionId)
                .map(
                    (r, m) ->
                        new Object[] {
                          new SearchHit()
                              .passageId(r.get("id", UUID.class))
                              .pageNo(r.get("page_no", Integer.class))
                              .snippet(r.get("snippet", String.class)),
                          r.get("total", Long.class)
                        })
                .all()
                .collectList())
        .map(
            rows ->
                new SearchResult()
                    .hits(rows.stream().map(o -> (SearchHit) o[0]).toList())
                    .total(rows.isEmpty() ? 0 : ((Long) rows.get(0)[1]).intValue()));
  }

  /** The rendered PDF's bytes and a first-open record (UX-NFR-07). */
  public Mono<byte[]> render(
      DatabaseClient db, CurrentUser actor, UUID versionId, boolean firstBytes) {
    return db.sql(
            "SELECT p.render_key FROM document_versions v"
                + " LEFT JOIN document_pages p ON p.document_version_id = v.id AND p.page_no = 1"
                + " WHERE v.id = :v AND v.deleted_at IS NULL")
        .bind("v", versionId)
        .map((r, m) -> java.util.Optional.ofNullable(r.get("render_key", String.class)))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("document version")))
        .flatMap(
            key -> {
              if (key.isEmpty()) {
                return Mono.error(
                    ApiException.conflict(
                        "document_not_ready", "The document has not finished processing"));
              }
              Mono<Void> log =
                  firstBytes
                      ? db.sql(
                              "INSERT INTO document_access_events (user_id, document_version_id)"
                                  + " VALUES (:u, :v)")
                          .bind("u", actor.id())
                          .bind("v", versionId)
                          .fetch()
                          .rowsUpdated()
                          .then()
                      : Mono.empty();
              return log.then(storage.getBytes(props.s3BucketDerived(), key.get()));
            });
  }
}
