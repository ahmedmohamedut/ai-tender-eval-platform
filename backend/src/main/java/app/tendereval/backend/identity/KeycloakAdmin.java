package app.tendereval.backend.identity;

import reactor.core.publisher.Mono;

/**
 * The backend's side of the identity provider (PI 3.5). Keycloak is changed first; the platform
 * writes its own row only when the call succeeded, and removes the Keycloak user again when its own
 * write fails. Implemented over the admin REST API with the {@code tender-backend-admin} service
 * account.
 */
public interface KeycloakAdmin {

  /** Creates the account (email as username) and returns the Keycloak user id. */
  Mono<String> createUser(String email, String firstName, String lastName);

  Mono<Void> mapRealmRole(String keycloakId, String realmRole);

  Mono<Void> replaceRealmRole(String keycloakId, String oldRealmRole, String newRealmRole);

  /** VERIFY_EMAIL and UPDATE_PASSWORD, link valid 3 days (UX-AUTH-05, PI 3.5). */
  Mono<Void> sendInvitation(String keycloakId);

  Mono<Void> updateName(String keycloakId, String firstName, String lastName);

  Mono<Void> setEnabled(String keycloakId, boolean enabled);

  /** Ends live sessions. */
  Mono<Void> logout(String keycloakId);

  /** Compensation for a failed platform write. */
  Mono<Void> deleteUser(String keycloakId);

  /** The realm's SSO session idle timeout, which the browser needs for its warning. */
  Mono<Integer> sessionIdleTimeoutSeconds();
}
