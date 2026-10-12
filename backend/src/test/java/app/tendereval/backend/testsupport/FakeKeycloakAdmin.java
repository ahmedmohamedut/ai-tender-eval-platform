package app.tendereval.backend.testsupport;

import app.tendereval.backend.identity.KeycloakAdmin;
import app.tendereval.backend.web.ApiException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

/** Records identity-provider calls and can fail a named step. The real client is used by e2e. */
public class FakeKeycloakAdmin implements KeycloakAdmin {

  public final List<String> calls = new CopyOnWriteArrayList<>();
  public volatile String failOn;

  public void reset() {
    calls.clear();
    failOn = null;
  }

  private Mono<Void> step(String call) {
    return Mono.defer(
        () -> {
          calls.add(call);
          if (failOn != null && call.startsWith(failOn)) {
            return Mono.error(
                new ApiException(
                    HttpStatus.BAD_GATEWAY, "identity_provider_error", "Identity provider failed"));
          }
          return Mono.empty();
        });
  }

  @Override
  public Mono<String> createUser(String email, String firstName, String lastName) {
    String id = UUID.randomUUID().toString();
    return step("createUser:" + email + ":" + firstName + "|" + lastName).thenReturn(id);
  }

  @Override
  public Mono<Void> mapRealmRole(String keycloakId, String realmRole) {
    return step("mapRealmRole:" + realmRole);
  }

  @Override
  public Mono<Void> replaceRealmRole(String keycloakId, String oldRealmRole, String newRealmRole) {
    return step("replaceRealmRole:" + oldRealmRole + "->" + newRealmRole);
  }

  @Override
  public Mono<Void> sendInvitation(String keycloakId) {
    return step("sendInvitation");
  }

  @Override
  public Mono<Void> updateName(String keycloakId, String firstName, String lastName) {
    return step("updateName:" + firstName + "|" + lastName);
  }

  @Override
  public Mono<Void> setEnabled(String keycloakId, boolean enabled) {
    return step("setEnabled:" + enabled);
  }

  @Override
  public Mono<Void> logout(String keycloakId) {
    return step("logout");
  }

  @Override
  public Mono<Void> deleteUser(String keycloakId) {
    calls.add("deleteUser");
    return Mono.empty();
  }

  @Override
  public Mono<Integer> sessionIdleTimeoutSeconds() {
    return Mono.just(1800);
  }
}
