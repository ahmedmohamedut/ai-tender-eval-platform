package app.tendereval.backend.users;

import app.tendereval.backend.api.UsersApi;
import app.tendereval.backend.api.model.DeactivateUser;
import app.tendereval.backend.api.model.User;
import app.tendereval.backend.api.model.UserInvite;
import app.tendereval.backend.api.model.UserPage;
import app.tendereval.backend.api.model.UserRefPage;
import app.tendereval.backend.api.model.UserRole;
import app.tendereval.backend.api.model.UserStatus;
import app.tendereval.backend.api.model.UserUpdate;
import app.tendereval.backend.common.ETags;
import app.tendereval.backend.db.RequestTransaction;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Administrator-only user management (UX-ROL-05). The database policies enforce it again. */
@RestController
public class UsersController implements UsersApi {

  private final RequestTransaction tx;
  private final UserService service;

  public UsersController(RequestTransaction tx, UserService service) {
    this.tx = tx;
    this.service = service;
  }

  @Override
  public Mono<ResponseEntity<UserPage>> listUsers(
      Integer limit,
      String cursor,
      String q,
      String sort,
      UserRole role,
      UserStatus status,
      ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.list(db, limit, cursor, q, sort, role, status))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<UserRefPage>> lookupUsers(
      String q, Integer limit, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.lookup(db, q, limit)).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<User>> getUser(UUID userId, ServerWebExchange exchange) {
    return tx.inRequestTransaction(db -> service.get(db, userId))
        .map(u -> ResponseEntity.ok().eTag(ETags.of(u.getRowVersion())).body(u));
  }

  @Override
  public Mono<ResponseEntity<User>> inviteUser(
      Mono<UserInvite> userInvite, ServerWebExchange exchange) {
    return userInvite
        .flatMap(body -> tx.asUser((db, actor) -> service.invite(db, actor, body)))
        .map(
            u ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .location(URI.create("/api/v1/users/" + u.getId()))
                    .eTag(ETags.of(u.getRowVersion()))
                    .body(u));
  }

  @Override
  public Mono<ResponseEntity<User>> updateUser(
      UUID userId, String ifMatch, Mono<UserUpdate> userUpdate, ServerWebExchange exchange) {
    int version = ETags.requireIfMatch(exchange.getRequest());
    return userUpdate
        .flatMap(body -> tx.asUser((db, actor) -> service.update(db, actor, userId, version, body)))
        .map(u -> ResponseEntity.ok().eTag(ETags.of(u.getRowVersion())).body(u));
  }

  @Override
  public Mono<ResponseEntity<User>> deactivateUser(
      UUID userId, Mono<DeactivateUser> deactivateUser, ServerWebExchange exchange) {
    return deactivateUser
        .flatMap(body -> tx.asUser((db, actor) -> service.deactivate(db, actor, userId, body)))
        .map(u -> ResponseEntity.ok().eTag(ETags.of(u.getRowVersion())).body(u));
  }

  @Override
  public Mono<ResponseEntity<User>> reactivateUser(UUID userId, ServerWebExchange exchange) {
    return tx.asUser((db, actor) -> service.reactivate(db, actor, userId))
        .map(u -> ResponseEntity.ok().eTag(ETags.of(u.getRowVersion())).body(u));
  }

  @Override
  public Mono<ResponseEntity<Void>> resendInvitation(UUID userId, ServerWebExchange exchange) {
    return tx.asUser((db, actor) -> service.resendInvitation(db, actor, userId))
        .thenReturn(ResponseEntity.noContent().<Void>build());
  }
}
