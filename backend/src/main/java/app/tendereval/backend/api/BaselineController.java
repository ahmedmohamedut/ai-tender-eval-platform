package app.tendereval.backend.api;

import app.tendereval.backend.api.model.Baseline;
import app.tendereval.backend.api.model.BaselineImpact;
import app.tendereval.backend.api.model.BaselinePrecheck;
import app.tendereval.backend.api.model.BaselineRequirementPage;
import app.tendereval.backend.api.model.ReasonBody;
import app.tendereval.backend.api.model.ScoringModel;
import app.tendereval.backend.api.model.ScoringModelInput;
import app.tendereval.backend.baselines.BaselineService;
import app.tendereval.backend.common.ETags;
import app.tendereval.backend.common.Idempotency;
import app.tendereval.backend.db.RequestTransaction;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Baseline and scoring (UX-REQ-13, UX-REQ-14, UX-SCO-01..04). Confirm and reopen are the Chair's.
 */
@RestController
public class BaselineController implements BaselineApi {

  private final RequestTransaction tx;
  private final BaselineService service;
  private final Idempotency idempotency;
  private final app.tendereval.backend.jobs.JobDispatcher dispatcher;

  public BaselineController(
      RequestTransaction tx,
      BaselineService service,
      Idempotency idempotency,
      app.tendereval.backend.jobs.JobDispatcher dispatcher) {
    this.tx = tx;
    this.service = service;
    this.idempotency = idempotency;
    this.dispatcher = dispatcher;
  }

  @Override
  public Mono<ResponseEntity<Flux<Baseline>>> listBaselines(
      UUID tenderId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.list(db, tenderId))
        .map(l -> ResponseEntity.ok(Flux.fromIterable(l)));
  }

  @Override
  public Mono<ResponseEntity<BaselinePrecheck>> precheckBaseline(
      UUID tenderId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.precheck(db, tenderId)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<BaselineImpact>> getBaselineImpact(
      UUID tenderId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.impact(db, tenderId)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Baseline>> confirmBaseline(
      UUID tenderId, String idempotencyKey, ServerWebExchange exchange) {
    String key = Idempotency.requireKey(idempotencyKey);
    return tx.asUser(
            (db, user) ->
                idempotency.run(
                    db,
                    user.id(),
                    key,
                    "POST /tenders/{id}/baselines:" + tenderId,
                    Idempotency.hash("confirmBaseline", tenderId.toString()),
                    Baseline.class,
                    () ->
                        service
                            .confirm(db, user, tenderId)
                            .map(b -> ResponseEntity.status(HttpStatus.CREATED).body(b))))
        // After the commit (rule 4): evaluation jobs this confirmation queued are published.
        .flatMap(
            r ->
                !"true".equals(r.getHeaders().getFirst("Idempotent-Replay"))
                    ? dispatcher.publishPendingEvaluations(tenderId).thenReturn(r)
                    : Mono.just(r));
  }

  @Override
  public Mono<ResponseEntity<Baseline>> reopenBaseline(
      UUID tenderId, Mono<ReasonBody> reasonBody, ServerWebExchange exchange) {
    return reasonBody
        .flatMap(in -> tx.asUser((db, user) -> service.reopen(db, user, tenderId, in.getReason())))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<BaselineRequirementPage>> listBaselineRequirements(
      UUID tenderId, Integer versionNo, Integer limit, String cursor, ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db -> service.requirements(db, tenderId, versionNo, limit, cursor))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<ScoringModel>> getScoringModel(
      UUID tenderId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.scoring(db, tenderId))
        .map(m -> ResponseEntity.ok().eTag(ETags.of(m.getRowVersion())).body(m));
  }

  @Override
  public Mono<ResponseEntity<ScoringModel>> putScoringModel(
      UUID tenderId,
      String ifMatch,
      Mono<ScoringModelInput> scoringModelInput,
      ServerWebExchange exchange) {
    int version = parseVersion(exchange);
    return scoringModelInput
        .flatMap(in -> tx.asUser((db, user) -> service.putScoring(db, user, tenderId, version, in)))
        .map(m -> ResponseEntity.ok().eTag(ETags.of(m.getRowVersion())).body(m));
  }

  /** Version 0 is allowed here: a tender that has no model of its own yet. */
  private static int parseVersion(ServerWebExchange exchange) {
    String h = exchange.getRequest().getHeaders().getFirst("If-Match");
    if (h != null && h.replace("\"", "").replace("W/", "").trim().equals("0")) {
      return 0;
    }
    return ETags.requireIfMatch(exchange.getRequest());
  }
}
