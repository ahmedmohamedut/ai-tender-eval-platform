package app.tendereval.backend.api;

import app.tendereval.backend.api.model.AddTenderVendor;
import app.tendereval.backend.api.model.ConflictDeclaration;
import app.tendereval.backend.api.model.ConflictDeclarationInput;
import app.tendereval.backend.api.model.DuplicateCheck;
import app.tendereval.backend.api.model.ProposalImpact;
import app.tendereval.backend.api.model.ProposalVersion;
import app.tendereval.backend.api.model.TenderVendor;
import app.tendereval.backend.api.model.TenderVendorPage;
import app.tendereval.backend.api.model.Vendor;
import app.tendereval.backend.api.model.VendorInput;
import app.tendereval.backend.api.model.VendorPage;
import app.tendereval.backend.api.model.VendorUpdate;
import app.tendereval.backend.common.ETags;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.messaging.PushPublisher;
import app.tendereval.backend.vendors.ConflictService;
import app.tendereval.backend.vendors.ProposalService;
import app.tendereval.backend.vendors.VendorService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The Vendors group (UX-VND-01..05). The proposal upload is hand-written (streaming) in {@code
 * ProposalUploadController}; the conflict declaration (UX-TND-07) is here.
 */
@RestController
public class VendorsController implements VendorsApi {

  private final RequestTransaction tx;
  private final VendorService vendors;
  private final ProposalService proposals;
  private final ConflictService conflicts;
  private final PushPublisher push;

  public VendorsController(
      RequestTransaction tx,
      VendorService vendors,
      ProposalService proposals,
      ConflictService conflicts,
      PushPublisher push) {
    this.tx = tx;
    this.vendors = vendors;
    this.proposals = proposals;
    this.conflicts = conflicts;
    this.push = push;
  }

  @Override
  public Mono<ResponseEntity<ConflictDeclaration>> getMyDeclaration(
      UUID tenderVendorId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> conflicts.mine(db, tenderVendorId))
        .map(ResponseEntity::ok)
        // no declaration yet: a 200 with an empty body, which the client reads as null
        .defaultIfEmpty(ResponseEntity.ok().build());
  }

  @Override
  public Mono<ResponseEntity<ConflictDeclaration>> declareConflict(
      UUID tenderVendorId,
      Mono<ConflictDeclarationInput> conflictDeclarationInput,
      ServerWebExchange exchange) {
    return conflictDeclarationInput
        .flatMap(in -> tx.asUser((db, user) -> conflicts.declare(db, user, tenderVendorId, in)))
        // after the commit: the committee's open pages refresh (a hint without names)
        .flatMap(
            d ->
                (Boolean.TRUE.equals(d.declaration().getHasConflict())
                        ? push.tender(
                            d.tenderId(),
                            "tender.changed",
                            "tender_vendor",
                            tenderVendorId,
                            java.util.Map.of("reason", "conflict_declared"))
                        : Mono.<Void>empty())
                    .thenReturn(d))
        .map(d -> ResponseEntity.status(HttpStatus.CREATED).body(d.declaration()));
  }

  @Override
  public Mono<ResponseEntity<VendorPage>> listVendors(
      Integer limit, String cursor, String q, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> vendors.list(db, limit, cursor, q))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<DuplicateCheck>> checkVendorDuplicates(
      String name, URI website, ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db -> vendors.duplicates(db, name, website == null ? null : website.toString()))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Vendor>> createVendor(
      Mono<VendorInput> vendorInput, ServerWebExchange exchange) {
    return vendorInput
        .flatMap(in -> tx.asUser((db, user) -> vendors.create(db, user, in)))
        .map(
            v ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .location(URI.create("/api/v1/vendors/" + v.getId()))
                    .eTag(ETags.of(v.getRowVersion()))
                    .body(v));
  }

  @Override
  public Mono<ResponseEntity<Vendor>> getVendor(UUID vendorId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> vendors.get(db, vendorId))
        .map(v -> ResponseEntity.ok().eTag(ETags.of(v.getRowVersion())).body(v));
  }

  @Override
  public Mono<ResponseEntity<Vendor>> updateVendor(
      UUID vendorId, String ifMatch, Mono<VendorUpdate> vendorUpdate, ServerWebExchange exchange) {
    int version = ETags.requireIfMatch(exchange.getRequest());
    return vendorUpdate
        .flatMap(in -> tx.asUser((db, user) -> vendors.update(db, user, vendorId, version, in)))
        .map(v -> ResponseEntity.ok().eTag(ETags.of(v.getRowVersion())).body(v));
  }

  @Override
  public Mono<ResponseEntity<TenderVendorPage>> listTenderVendors(
      UUID tenderId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> vendors.listForTender(db, tenderId))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<TenderVendor>> addTenderVendor(
      UUID tenderId, Mono<AddTenderVendor> addTenderVendor, ServerWebExchange exchange) {
    return addTenderVendor
        .flatMap(in -> tx.asUser((db, user) -> vendors.addToTender(db, user, tenderId, in)))
        .map(
            tv ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .location(URI.create("/api/v1/tender-vendors/" + tv.getId()))
                    .body(tv));
  }

  @Override
  public Mono<ResponseEntity<TenderVendor>> getTenderVendor(
      UUID tenderVendorId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> vendors.getTenderVendor(db, tenderVendorId))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Flux<ProposalVersion>>> listProposalVersions(
      UUID tenderVendorId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> proposals.list(db, tenderVendorId))
        .map(l -> ResponseEntity.ok(Flux.fromIterable(l)));
  }

  @Override
  public Mono<ResponseEntity<ProposalImpact>> getProposalImpact(
      UUID tenderVendorId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> vendors.proposalImpact(db, tenderVendorId))
        .map(ResponseEntity::ok);
  }
}
