package app.tendereval.backend.tenders;

import app.tendereval.backend.api.TendersApi;
import app.tendereval.backend.api.model.AddMember;
import app.tendereval.backend.api.model.DuplicateTender;
import app.tendereval.backend.api.model.Member;
import app.tendereval.backend.api.model.MemberPage;
import app.tendereval.backend.api.model.ProgressBoard;
import app.tendereval.backend.api.model.Readiness;
import app.tendereval.backend.api.model.RemoveMember;
import app.tendereval.backend.api.model.SetChair;
import app.tendereval.backend.api.model.StageDates;
import app.tendereval.backend.api.model.Tender;
import app.tendereval.backend.api.model.TenderCreate;
import app.tendereval.backend.api.model.TenderHome;
import app.tendereval.backend.api.model.TenderStatus;
import app.tendereval.backend.api.model.TenderSummaryPage;
import app.tendereval.backend.api.model.TenderUpdate;
import app.tendereval.backend.common.ETags;
import app.tendereval.backend.db.RequestTransaction;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class TendersController implements TendersApi {

  private final RequestTransaction tx;
  private final TenderService service;

  public TendersController(RequestTransaction tx, TenderService service) {
    this.tx = tx;
    this.service = service;
  }

  private static ResponseEntity<Tender> ok(Tender t) {
    return ResponseEntity.ok().eTag(ETags.of(t.getRowVersion())).body(t);
  }

  @Override
  public Mono<ResponseEntity<TenderSummaryPage>> listTenders(
      Integer limit,
      String cursor,
      String q,
      String sort,
      TenderStatus status,
      Boolean archived,
      Boolean mine,
      ServerWebExchange exchange) {
    return tx.asUser(
            (db, user) -> service.list(db, user, limit, cursor, q, sort, status, archived, mine))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Tender>> createTender(
      Mono<TenderCreate> tenderCreate, ServerWebExchange exchange) {
    return tenderCreate
        .flatMap(body -> tx.asUser((db, user) -> service.create(db, user, body)))
        .map(
            t ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .location(URI.create("/api/v1/tenders/" + t.getId()))
                    .eTag(ETags.of(t.getRowVersion()))
                    .body(t));
  }

  @Override
  public Mono<ResponseEntity<Tender>> getTender(UUID tenderId, ServerWebExchange exchange) {
    return tx.asUser((db, user) -> service.get(db, user, tenderId)).map(TendersController::ok);
  }

  @Override
  public Mono<ResponseEntity<Tender>> updateTender(
      UUID tenderId, String ifMatch, Mono<TenderUpdate> tenderUpdate, ServerWebExchange exchange) {
    int version = ETags.requireIfMatch(exchange.getRequest());
    return tenderUpdate
        .flatMap(body -> tx.asUser((db, user) -> service.update(db, user, tenderId, version, body)))
        .map(TendersController::ok);
  }

  @Override
  public Mono<ResponseEntity<Tender>> archiveTender(UUID tenderId, ServerWebExchange exchange) {
    return tx.asUser((db, user) -> service.archive(db, user, tenderId)).map(TendersController::ok);
  }

  @Override
  public Mono<ResponseEntity<Tender>> openTender(UUID tenderId, ServerWebExchange exchange) {
    return tx.asUser((db, user) -> service.open(db, user, tenderId)).map(TendersController::ok);
  }

  @Override
  public Mono<ResponseEntity<Tender>> duplicateTender(
      UUID tenderId, Mono<DuplicateTender> duplicateTender, ServerWebExchange exchange) {
    return duplicateTender
        .flatMap(
            body -> tx.asUser((db, user) -> service.duplicate(db, user, tenderId, body.getName())))
        .map(
            t ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .location(URI.create("/api/v1/tenders/" + t.getId()))
                    .eTag(ETags.of(t.getRowVersion()))
                    .body(t));
  }

  @Override
  public Mono<ResponseEntity<MemberPage>> listMembers(UUID tenderId, ServerWebExchange exchange) {
    return tx.asUser((db, user) -> service.listMembers(db, user, tenderId)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Member>> addMember(
      UUID tenderId, Mono<AddMember> addMember, ServerWebExchange exchange) {
    return addMember
        .flatMap(body -> tx.asUser((db, user) -> service.addMember(db, user, tenderId, body)))
        .map(m -> ResponseEntity.status(HttpStatus.CREATED).body(m));
  }

  @Override
  public Mono<ResponseEntity<Void>> removeMember(
      UUID tenderId, UUID userId, Mono<RemoveMember> removeMember, ServerWebExchange exchange) {
    return removeMember
        .flatMap(
            body -> tx.asUser((db, user) -> service.removeMember(db, user, tenderId, userId, body)))
        .thenReturn(ResponseEntity.noContent().<Void>build());
  }

  @Override
  public Mono<ResponseEntity<Member>> setChair(
      UUID tenderId, Mono<SetChair> setChair, ServerWebExchange exchange) {
    return setChair
        .flatMap(body -> tx.asUser((db, user) -> service.setChair(db, user, tenderId, body)))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<StageDates>> setStageDates(
      UUID tenderId, Mono<StageDates> stageDates, ServerWebExchange exchange) {
    return stageDates
        .flatMap(body -> tx.asUser((db, user) -> service.setStageDates(db, user, tenderId, body)))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Readiness>> getTenderReadiness(
      UUID tenderId, ServerWebExchange exchange) {
    return tx.asUser((db, user) -> service.load(db, tenderId).then(service.readiness(db, tenderId)))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<TenderHome>> getTenderHome(UUID tenderId, ServerWebExchange exchange) {
    return tx.asUser((db, user) -> service.home(db, user, tenderId)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<ProgressBoard>> getTenderProgress(
      UUID tenderId, ServerWebExchange exchange) {
    return tx.asUser((db, user) -> service.progress(db, user, tenderId)).map(ResponseEntity::ok);
  }
}
