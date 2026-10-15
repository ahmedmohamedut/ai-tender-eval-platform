package app.tendereval.backend.api;

import app.tendereval.backend.api.model.NotificationPage;
import app.tendereval.backend.api.model.NotificationPreferences;
import app.tendereval.backend.api.model.UnreadCount;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.notifications.NotificationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** The Notifications group (UX-NOT-01..03): a person's own notifications and email preferences. */
@RestController
public class NotificationsController implements NotificationsApi {

  private final RequestTransaction tx;
  private final NotificationService service;

  public NotificationsController(RequestTransaction tx, NotificationService service) {
    this.tx = tx;
    this.service = service;
  }

  @Override
  public Mono<ResponseEntity<NotificationPage>> listNotifications(
      Integer limit, String cursor, Boolean unread, UUID tenderId, ServerWebExchange exchange) {
    return tx.asUser((db, u) -> service.list(db, u, limit, cursor, unread, tenderId))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<UnreadCount>> getUnreadCount(ServerWebExchange exchange) {
    return tx.asUser(service::unread).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Void>> markNotificationRead(
      UUID notificationId, ServerWebExchange exchange) {
    return tx.asUser((db, u) -> service.markRead(db, u, notificationId))
        .then(Mono.just(ResponseEntity.noContent().<Void>build()));
  }

  @Override
  public Mono<ResponseEntity<Void>> markAllNotificationsRead(
      UUID tenderId, ServerWebExchange exchange) {
    return tx.asUser((db, u) -> service.markAllRead(db, u, tenderId))
        .then(Mono.just(ResponseEntity.noContent().<Void>build()));
  }

  @Override
  public Mono<ResponseEntity<NotificationPreferences>> getNotificationPreferences(
      ServerWebExchange exchange) {
    return tx.asUser(service::preferences).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<NotificationPreferences>> putNotificationPreferences(
      Mono<NotificationPreferences> notificationPreferences, ServerWebExchange exchange) {
    return notificationPreferences
        .flatMap(in -> tx.asUser((db, u) -> service.putPreferences(db, u, in)))
        .map(ResponseEntity::ok);
  }
}
