package app.tendereval.backend.common;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/** Business settings read inside the caller's transaction (PI 7.7). */
public record Settings(Map<String, String> values) {

  public static Mono<Settings> load(DatabaseClient db) {
    return db.sql("SELECT key, value FROM settings")
        .map((row, meta) -> Map.entry(row.get("key", String.class), row.get("value", String.class)))
        .all()
        .collectMap(Map.Entry::getKey, Map.Entry::getValue, HashMap::new)
        .map(Settings::new);
  }

  public int minNoteLength() {
    return Integer.parseInt(values.getOrDefault("note.min_length", "10"));
  }

  public long uploadMaxBytes() {
    return Long.parseLong(values.getOrDefault("upload.max_bytes", "104857600"));
  }

  public int sessionWarningSeconds() {
    return Integer.parseInt(values.getOrDefault("session.warning_seconds", "120"));
  }

  public BigDecimal lowConfidenceThreshold() {
    return new BigDecimal(values.getOrDefault("ai.low_confidence_threshold", "0.6"));
  }
}
