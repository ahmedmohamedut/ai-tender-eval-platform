package app.tendereval.backend.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Last status seen per job, in memory. Phase 0 has no jobs table, so this only lets the walking
 * slice observe a status. Delivery is unordered and at least once: a terminal state is never
 * replaced and a lower sequence number never replaces a higher one (PI 5.7).
 */
@Component
public class StatusStore {

  private static final Set<String> TERMINAL =
      Set.of("completed", "completed_with_warnings", "failed", "cancelled");

  public record Seen(String key, String state, long seq, String step, Instant receivedAt) {}

  private final Map<String, Seen> byKey = new ConcurrentHashMap<>();

  /** Returns true when the message changed what is stored. */
  public boolean record(String key, String state, long seq, String step) {
    AtomicBoolean changed = new AtomicBoolean();
    byKey.compute(
        key,
        (k, old) -> {
          Seen incoming = new Seen(k, state, seq, step, Instant.now());
          if (old == null) {
            changed.set(true);
            return incoming;
          }
          if (TERMINAL.contains(old.state())) {
            return old;
          }
          if (!TERMINAL.contains(state) && seq < old.seq()) {
            return old;
          }
          changed.set(true);
          return incoming;
        });
    return changed.get();
  }

  private final java.util.Set<String> devJobs = ConcurrentHashMap.newKeySet();

  /** A job id of the walking slice: it has no database row and its statuses stay in memory. */
  public void registerDevJob(String jobId) {
    devJobs.add(jobId);
  }

  public boolean isDevJob(String jobId) {
    return devJobs.contains(jobId);
  }

  public Optional<Seen> get(String key) {
    return Optional.ofNullable(byKey.get(key));
  }
}
