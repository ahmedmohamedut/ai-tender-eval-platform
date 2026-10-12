package app.tendereval.backend.settings;

import app.tendereval.backend.api.SettingsApi;
import app.tendereval.backend.api.model.Setting;
import app.tendereval.backend.api.model.SettingUpdate;
import app.tendereval.backend.api.model.UserRef;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.web.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Business settings an administrator may change without a release (PI 7.7, UX-GEN-04). */
@RestController
public class SettingsController implements SettingsApi {

  /** Allowed range per key. The upload limit stays below the gateway's body limit (PI Q13). */
  private record Rule(BigDecimal min, BigDecimal max, boolean integer) {}

  private static final Map<String, Rule> RULES =
      Map.of(
          "note.min_length", new Rule(BigDecimal.valueOf(1), BigDecimal.valueOf(1000), true),
          "upload.max_bytes",
              new Rule(
                  BigDecimal.valueOf(1_048_576),
                  BigDecimal.valueOf(120L * 1024 * 1024 - 1_048_576),
                  true),
          "session.warning_seconds",
              new Rule(BigDecimal.valueOf(30), BigDecimal.valueOf(900), true),
          "ai.low_confidence_threshold", new Rule(BigDecimal.ZERO, BigDecimal.ONE, false));

  private final RequestTransaction tx;
  private final AuditService audit;

  public SettingsController(RequestTransaction tx, AuditService audit) {
    this.tx = tx;
    this.audit = audit;
  }

  @Override
  public Mono<ResponseEntity<Flux<Setting>>> listSettings(ServerWebExchange exchange) {
    return tx.inRequestTransaction(
            db ->
                db.sql(
                        "SELECT s.key, s.value, s.description, s.updated_at, s.changed_by, u.display_name"
                            + " FROM settings s LEFT JOIN users u ON u.id = s.changed_by ORDER BY s.key")
                    .map((row, meta) -> toSetting(row))
                    .all()
                    .collectList())
        .map(list -> ResponseEntity.ok(Flux.fromIterable(list)));
  }

  @Override
  public Mono<ResponseEntity<Setting>> updateSetting(
      String key, Mono<SettingUpdate> settingUpdate, ServerWebExchange exchange) {
    return settingUpdate
        .flatMap(
            body ->
                tx.asUser(
                    (db, actor) -> {
                      Rule rule = RULES.get(key);
                      if (rule == null) {
                        return Mono.error(ApiException.notFound("The setting"));
                      }
                      String value = validate(key, rule, body.getValue());
                      return db.sql("SELECT value FROM settings WHERE key = :k")
                          .bind("k", key)
                          .map((row, meta) -> row.get("value", String.class))
                          .one()
                          .switchIfEmpty(Mono.error(ApiException.notFound("The setting")))
                          .flatMap(
                              old ->
                                  old.equals(value)
                                      ? Mono.<Void>empty()
                                      : db.sql(
                                              "UPDATE settings SET value = :v, changed_by = :u WHERE key = :k")
                                          .bind("v", value)
                                          .bind("u", actor.id())
                                          .bind("k", key)
                                          .fetch()
                                          .rowsUpdated()
                                          .then(
                                              audit.record(
                                                  db,
                                                  actor,
                                                  AuditEntry.of("setting_changed", "setting", null)
                                                      .changed(Map.of(key, old), Map.of(key, value))
                                                      .build())))
                          .then(
                              db.sql(
                                      "SELECT s.key, s.value, s.description, s.updated_at, s.changed_by,"
                                          + " u.display_name FROM settings s LEFT JOIN users u"
                                          + " ON u.id = s.changed_by WHERE s.key = :k")
                                  .bind("k", key)
                                  .map((row, meta) -> toSetting(row))
                                  .one());
                    }))
        .map(ResponseEntity::ok);
  }

  private static String validate(String key, Rule rule, Object raw) {
    BigDecimal number;
    try {
      number = new BigDecimal(String.valueOf(raw).trim());
    } catch (NumberFormatException e) {
      throw ApiException.invalid("value", "not_a_number", "Enter a number");
    }
    if (rule.integer() && number.stripTrailingZeros().scale() > 0) {
      throw ApiException.invalid("value", "not_an_integer", "Enter a whole number");
    }
    if (number.compareTo(rule.min()) < 0 || number.compareTo(rule.max()) > 0) {
      throw ApiException.invalid(
          "value",
          "out_of_range",
          "Enter a value from " + rule.min().toPlainString() + " to " + rule.max().toPlainString());
    }
    return rule.integer() ? String.valueOf(number.longValueExact()) : number.toPlainString();
  }

  private static Setting toSetting(io.r2dbc.spi.Row row) {
    String raw = row.get("value", String.class);
    Object value;
    try {
      value = raw.contains(".") ? new BigDecimal(raw) : (Object) Long.parseLong(raw);
    } catch (NumberFormatException e) {
      value = raw;
    }
    UUID changedBy = row.get("changed_by", UUID.class);
    return new Setting()
        .key(row.get("key", String.class))
        .value(value)
        .description(row.get("description", String.class))
        .changedAt(row.get("updated_at", OffsetDateTime.class))
        .changedBy(
            changedBy == null
                ? null
                : new UserRef().id(changedBy).displayName(row.get("display_name", String.class)));
  }
}
