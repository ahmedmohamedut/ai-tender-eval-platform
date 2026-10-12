package app.tendereval.backend.identity;

import app.tendereval.backend.config.AppProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The idle timeout the browser warns against comes from the realm (PI 3.1, UX-AUTH-04), read
 * through the admin API and kept for five minutes. When Keycloak cannot be asked, the configured
 * value is used so that sign-in never depends on the admin API being up.
 */
@Component
public class SessionPolicy {

  private static final Logger log = LoggerFactory.getLogger(SessionPolicy.class);

  private final Mono<Integer> idleTimeoutSeconds;

  public SessionPolicy(KeycloakAdmin keycloak, AppProperties props) {
    this.idleTimeoutSeconds =
        Mono.defer(keycloak::sessionIdleTimeoutSeconds)
            .timeout(Duration.ofSeconds(3))
            .onErrorResume(
                e -> {
                  log.warn("realm idle timeout unavailable: {}", e.getClass().getSimpleName());
                  return Mono.just(props.sessionIdleTimeoutSeconds());
                })
            .cache(
                v -> Duration.ofMinutes(5),
                e -> Duration.ofSeconds(30),
                () -> Duration.ofSeconds(30));
  }

  public Mono<Integer> idleTimeoutSeconds() {
    return idleTimeoutSeconds;
  }
}
