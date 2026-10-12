package app.tendereval.backend.common;

import app.tendereval.backend.config.AppProperties;
import app.tendereval.backend.web.ApiException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Per-user token buckets (PI 2.1, 7.3). The state is per backend instance, so with N instances a
 * user can reach N times the limit; the limits are assumptions to tune, and a shared limiter is a
 * later step (docs/decisions.md).
 */
@Component
public class RateLimiter {

  public enum Bucket {
    UPLOADS,
    SEARCH,
    JOB_STARTS,
    EXPORTS
  }

  private static final class State {
    double tokens;
    long lastNanos;
  }

  private final Map<String, State> states = new ConcurrentHashMap<>();
  private final AppProperties props;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public RateLimiter(AppProperties props) {
    this(props, Clock.systemUTC());
  }

  RateLimiter(AppProperties props, Clock clock) {
    this.props = props;
    this.clock = clock;
  }

  int perMinute(Bucket bucket) {
    return switch (bucket) {
      case UPLOADS -> props.rateLimitUploadsPerMinute();
      case SEARCH -> props.rateLimitSearchPerMinute();
      case JOB_STARTS -> props.rateLimitJobStartsPerMinute();
      case EXPORTS -> props.rateLimitExportsPerMinute();
    };
  }

  /** Takes one token or throws 429 with {@code Retry-After}. */
  public void check(UUID user, Bucket bucket) {
    int limit = perMinute(bucket);
    if (limit <= 0) {
      return;
    }
    State s =
        states.computeIfAbsent(
            user + ":" + bucket,
            k -> {
              State n = new State();
              n.tokens = limit;
              n.lastNanos = nanos();
              return n;
            });
    synchronized (s) {
      long now = nanos();
      double refill = (now - s.lastNanos) / 1_000_000_000.0 * (limit / 60.0);
      s.tokens = Math.min(limit, s.tokens + refill);
      s.lastNanos = now;
      if (s.tokens >= 1) {
        s.tokens -= 1;
        return;
      }
      long wait = (long) Math.ceil((1 - s.tokens) / (limit / 60.0));
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests")
          .header("Retry-After", String.valueOf(Math.max(1, wait)));
    }
  }

  private long nanos() {
    return clock.instant().getEpochSecond() * 1_000_000_000L + clock.instant().getNano();
  }
}
