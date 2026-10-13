package app.tendereval.backend.api;

import app.tendereval.backend.api.model.BulkRequirements;
import app.tendereval.backend.api.model.BulkResult;
import app.tendereval.backend.api.model.Category;
import app.tendereval.backend.api.model.CategoryInput;
import app.tendereval.backend.api.model.DeleteRequirement;
import app.tendereval.backend.api.model.History;
import app.tendereval.backend.api.model.Importance;
import app.tendereval.backend.api.model.MergeRequirements;
import app.tendereval.backend.api.model.NoteBody;
import app.tendereval.backend.api.model.Requirement;
import app.tendereval.backend.api.model.RequirementCreate;
import app.tendereval.backend.api.model.RequirementOrigin;
import app.tendereval.backend.api.model.RequirementPage;
import app.tendereval.backend.api.model.RequirementPatch;
import app.tendereval.backend.api.model.RequirementReviewState;
import app.tendereval.backend.api.model.RequirementsSummary;
import app.tendereval.backend.api.model.SplitRequirement;
import app.tendereval.backend.common.ETags;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.requirements.RequirementService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The Requirements group (UX-REQ-01..14). The export (UX-REQ-15) answers 501 until the generic
 * export machinery exists.
 */
@RestController
public class RequirementsController implements RequirementsApi {

  private final RequestTransaction tx;
  private final RequirementService service;

  public RequirementsController(RequestTransaction tx, RequirementService service) {
    this.tx = tx;
    this.service = service;
  }

  private static ResponseEntity<Requirement> tagged(Requirement r) {
    return ResponseEntity.ok().eTag(ETags.of(r.getRowVersion())).body(r);
  }

  @Override
  public Mono<ResponseEntity<RequirementPage>> listRequirements(
      UUID tenderId,
      Integer limit,
      String cursor,
      String q,
      String sort,
      UUID categoryId,
      Importance importance,
      RequirementOrigin origin,
      RequirementReviewState reviewState,
      Boolean flagged,
      Boolean deleted,
      UUID documentVersionId,
      String groupBy,
      ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db ->
                service.list(
                    db,
                    tenderId,
                    limit,
                    cursor,
                    q,
                    categoryId,
                    importance,
                    origin,
                    reviewState,
                    flagged,
                    deleted,
                    documentVersionId))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<RequirementsSummary>> getRequirementsSummary(
      UUID tenderId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.summary(db, tenderId)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Requirement>> getRequirement(
      UUID requirementId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.get(db, requirementId))
        .map(RequirementsController::tagged);
  }

  @Override
  public Mono<ResponseEntity<History>> getRequirementHistory(
      UUID requirementId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.history(db, requirementId))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Requirement>> createRequirement(
      UUID tenderId, Mono<RequirementCreate> requirementCreate, ServerWebExchange exchange) {
    return requirementCreate
        .flatMap(in -> tx.asUser((db, user) -> service.create(db, user, tenderId, in)))
        .map(
            r ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .location(URI.create("/api/v1/requirements/" + r.getId()))
                    .eTag(ETags.of(r.getRowVersion()))
                    .body(r));
  }

  @Override
  public Mono<ResponseEntity<Requirement>> updateRequirement(
      UUID requirementId,
      String ifMatch,
      Mono<RequirementPatch> requirementPatch,
      ServerWebExchange exchange) {
    int version = ETags.requireIfMatch(exchange.getRequest());
    return requirementPatch
        .flatMap(in -> tx.asUser((db, user) -> service.patch(db, user, requirementId, version, in)))
        .map(RequirementsController::tagged);
  }

  @Override
  public Mono<ResponseEntity<Requirement>> deleteRequirement(
      UUID requirementId, Mono<DeleteRequirement> deleteRequirement, ServerWebExchange exchange) {
    return deleteRequirement
        .flatMap(in -> tx.asUser((db, user) -> service.delete(db, user, requirementId, in)))
        .map(RequirementsController::tagged);
  }

  @Override
  public Mono<ResponseEntity<Requirement>> restoreRequirement(
      UUID requirementId, Mono<NoteBody> noteBody, ServerWebExchange exchange) {
    return noteBody
        .flatMap(
            in -> tx.asUser((db, user) -> service.restore(db, user, requirementId, in.getNote())))
        .map(RequirementsController::tagged);
  }

  @Override
  public Mono<ResponseEntity<Requirement>> markRequirementReviewed(
      UUID requirementId, ServerWebExchange exchange) {
    return tx.asUser((db, user) -> service.markReviewed(db, user, requirementId))
        .map(RequirementsController::tagged);
  }

  @Override
  public Mono<ResponseEntity<Requirement>> resolveRequirementFlag(
      UUID requirementId, UUID flagId, Mono<NoteBody> noteBody, ServerWebExchange exchange) {
    return noteBody
        .map(NoteBody::getNote)
        .defaultIfEmpty("")
        .flatMap(
            note ->
                tx.asUser(
                    (db, user) ->
                        service.resolveFlag(
                            db, user, requirementId, flagId, note.isEmpty() ? null : note)))
        .map(RequirementsController::tagged);
  }

  @Override
  public Mono<ResponseEntity<BulkResult>> bulkUpdateRequirements(
      UUID tenderId, Mono<BulkRequirements> bulkRequirements, ServerWebExchange exchange) {
    return bulkRequirements
        .flatMap(in -> tx.asUser((db, user) -> service.bulk(db, user, tenderId, in)))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Requirement>> mergeRequirements(
      UUID tenderId, Mono<MergeRequirements> mergeRequirements, ServerWebExchange exchange) {
    return mergeRequirements
        .flatMap(in -> tx.asUser((db, user) -> service.merge(db, user, tenderId, in)))
        .map(
            r ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .eTag(ETags.of(r.getRowVersion()))
                    .body(r));
  }

  @Override
  public Mono<ResponseEntity<Flux<Requirement>>> splitRequirement(
      UUID requirementId, Mono<SplitRequirement> splitRequirement, ServerWebExchange exchange) {
    return splitRequirement
        .flatMap(in -> tx.asUser((db, user) -> service.split(db, user, requirementId, in)))
        .map(l -> ResponseEntity.status(HttpStatus.CREATED).body(Flux.fromIterable(l)));
  }

  @Override
  public Mono<ResponseEntity<Flux<Category>>> listCategories(
      Boolean includeInactive, ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db -> service.categories(db, Boolean.TRUE.equals(includeInactive)))
        .map(l -> ResponseEntity.ok(Flux.fromIterable(l)));
  }

  @Override
  public Mono<ResponseEntity<Category>> createCategory(
      Mono<CategoryInput> categoryInput, ServerWebExchange exchange) {
    return categoryInput
        .flatMap(in -> tx.asUser((db, user) -> service.createCategory(db, user, in)))
        .map(c -> ResponseEntity.status(HttpStatus.CREATED).body(c));
  }

  @Override
  public Mono<ResponseEntity<Category>> updateCategory(
      UUID categoryId, Mono<CategoryInput> categoryInput, ServerWebExchange exchange) {
    return categoryInput
        .flatMap(in -> tx.asUser((db, user) -> service.updateCategory(db, user, categoryId, in)))
        .map(ResponseEntity::ok);
  }
}
