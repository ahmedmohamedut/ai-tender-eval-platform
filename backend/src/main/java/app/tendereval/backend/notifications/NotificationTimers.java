package app.tendereval.backend.notifications;

import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The two background jobs of notifications (P85): due-date reminders and email delivery. Both run
 * as the system, with no signed-in person, through the V24 and V27 functions that accept only that
 * context. Email is sent only when {@code SMTP_HOST} is set; otherwise pending rows wait (Q12: the
 * default is in-app only, so nothing is pending unless a person asked for email).
 */
@Component
@ConditionalOnProperty(name = "app.watchdog-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationTimers implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(NotificationTimers.class);
  private static final int BATCH = 20;
  private static final int ATTEMPTS = 3;

  private final DatabaseClient db;
  private final TransactionalOperator tx;
  private final NotificationService notifications;
  private final JavaMailSenderImpl mail;
  private final String from;
  private final String baseUrl;
  private final boolean mailEnabled;
  private Disposable loop;

  public NotificationTimers(
      DatabaseClient db,
      TransactionalOperator tx,
      NotificationService notifications,
      @Value("${SMTP_HOST:}") String host,
      @Value("${SMTP_PORT:587}") int port,
      @Value("${SMTP_FROM:}") String from,
      @Value("${SMTP_USER:}") String user,
      @Value("${SMTP_PASSWORD:}") String password,
      @Value("${PUBLIC_BASE_URL:}") String baseUrl) {
    this.db = db;
    this.tx = tx;
    this.notifications = notifications;
    this.from = from;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.mailEnabled = !host.isBlank() && !from.isBlank();
    this.mail = new JavaMailSenderImpl();
    mail.setHost(host);
    mail.setPort(port);
    if (!user.isBlank()) {
      mail.setUsername(user);
      mail.setPassword(password);
    }
    Properties p = mail.getJavaMailProperties();
    p.put("mail.smtp.auth", String.valueOf(!user.isBlank()));
    p.put("mail.smtp.starttls.enable", String.valueOf(port == 587));
    p.put("mail.smtp.connectiontimeout", "5000");
    p.put("mail.smtp.timeout", "10000");
  }

  @Override
  public void start() {
    loop =
        Flux.interval(Duration.ofSeconds(20), Duration.ofSeconds(30))
            .onBackpressureDrop()
            .concatMap(
                t ->
                    pass()
                        .onErrorResume(
                            e -> {
                              log.warn(
                                  "notification pass failed: {}", e.getClass().getSimpleName());
                              return Mono.empty();
                            }))
            .subscribe();
  }

  /** One pass: the due-date reminders, then the pending email. Public for the tests. */
  public Mono<Void> pass() {
    return dueReminders().then(mailEnabled ? deliver() : Mono.empty());
  }

  Mono<Void> dueReminders() {
    return tx.transactional(
            db.sql("SELECT notification_id, user_id FROM app_run_due_reminders()")
                .map(
                    (r, m) ->
                        Map.entry(
                            r.get("user_id", UUID.class), r.get("notification_id", UUID.class)))
                .all()
                .collectList())
        .flatMap(
            created -> {
              if (!created.isEmpty()) {
                log.info("due-date reminders created count={}", created.size());
              }
              return notifications.pushCreated(created, "due_date_reminder");
            });
  }

  private record Pending(UUID id, UUID tender, String type) {}

  Mono<Void> deliver() {
    return db.sql("SELECT id, tender_id, notification_type FROM app_notifications_email_next(:n)")
        .bind("n", BATCH)
        .map(
            (r, m) ->
                new Pending(
                    r.get("id", UUID.class),
                    r.get("tender_id", UUID.class),
                    r.get("notification_type", String.class)))
        .all()
        .collectList()
        .flatMapMany(Flux::fromIterable)
        .concatMap(this::sendOne)
        .then();
  }

  private Mono<Void> sendOne(Pending p) {
    return db.sql("SELECT app_notification_recipient(:id) AS email")
        .bind("id", p.id())
        .map((r, m) -> java.util.Optional.ofNullable(r.get("email", String.class)))
        .one()
        .flatMap(
            email ->
                email.isEmpty()
                    ? result(p.id(), "failed")
                    : Mono.fromCallable(() -> send(email.get(), p))
                        .subscribeOn(Schedulers.boundedElastic())
                        .retryWhen(
                            reactor.util.retry.Retry.backoff(ATTEMPTS - 1, Duration.ofSeconds(1)))
                        .then(result(p.id(), "sent"))
                        .onErrorResume(
                            e -> {
                              log.warn(
                                  "email not delivered id={} error={}",
                                  p.id(),
                                  e.getClass().getSimpleName());
                              return result(p.id(), "failed");
                            }));
  }

  private Boolean send(String to, Pending p) throws Exception {
    MimeMessage message = mail.createMimeMessage();
    MimeMessageHelper h = new MimeMessageHelper(message, "UTF-8");
    h.setFrom(from);
    h.setTo(to);
    String title = NotificationService.titleOf(p.type());
    h.setSubject("[Tender evaluation] " + title);
    String link = NotificationService.link(p.type(), p.tender());
    h.setText(title + (link == null ? "" : "\n\nOpen it here: " + baseUrl + link) + "\n", false);
    mail.send(message);
    return true;
  }

  private Mono<Void> result(UUID id, String status) {
    return db.sql("SELECT app_notification_email_result(:id, :s)")
        .bind("id", id)
        .bind("s", status)
        .fetch()
        .rowsUpdated()
        .then();
  }

  @Override
  public void stop() {
    if (loop != null) {
      loop.dispose();
    }
  }

  @Override
  public boolean isRunning() {
    return loop != null && !loop.isDisposed();
  }
}
