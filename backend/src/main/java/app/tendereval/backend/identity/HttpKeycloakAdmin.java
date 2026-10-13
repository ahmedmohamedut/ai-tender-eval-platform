package app.tendereval.backend.identity;

import app.tendereval.backend.config.AppProperties;
import app.tendereval.backend.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/** Keycloak admin REST API with a client-credentials token that is cached until it expires. */
@Component
public class HttpKeycloakAdmin implements KeycloakAdmin {

  private record Token(String value, Instant expiresAt) {}

  private final WebClient http;
  private final AppProperties props;
  private final String adminBase;
  private final String tokenUrl;
  private final AtomicReference<Token> token = new AtomicReference<>();

  public HttpKeycloakAdmin(AppProperties props) {
    this.props = props;
    this.http = WebClient.builder().build();
    this.adminBase = props.keycloakAdminBaseUrl().replaceAll("/+$", "");
    // https://host/admin/realms/tender-eval -> https://host/realms/tender-eval/protocol/...
    this.tokenUrl =
        adminBase.replace("/admin/realms/", "/realms/") + "/protocol/openid-connect/token";
  }

  private Mono<String> accessToken() {
    Token t = token.get();
    if (t != null && t.expiresAt().isAfter(Instant.now().plusSeconds(20))) {
      return Mono.just(t.value());
    }
    return http.post()
        .uri(tokenUrl)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(
            BodyInserters.fromFormData("grant_type", "client_credentials")
                .with("client_id", props.keycloakAdminClientId())
                .with("client_secret", props.keycloakAdminClientSecret()))
        .retrieve()
        .bodyToMono(Map.class)
        .map(
            m -> {
              String value = String.valueOf(m.get("access_token"));
              long seconds = ((Number) m.getOrDefault("expires_in", 60)).longValue();
              Token fresh = new Token(value, Instant.now().plusSeconds(seconds));
              token.set(fresh);
              return value;
            })
        .onErrorMap(this::translate);
  }

  private WebClient.RequestBodySpec request(
      org.springframework.http.HttpMethod method, String path, String bearer) {
    // A URI, not a string: a string is a template and would encode %3A a second time.
    return http.method(method)
        .uri(java.net.URI.create(adminBase + path))
        .headers(h -> h.setBearerAuth(bearer))
        .contentType(MediaType.APPLICATION_JSON);
  }

  private <T> Mono<T> call(
      org.springframework.http.HttpMethod method,
      String path,
      Object body,
      java.util.function.Function<WebClient.ResponseSpec, Mono<T>> read) {
    return accessToken()
        .flatMap(
            bearer -> {
              WebClient.RequestBodySpec spec = request(method, path, bearer);
              WebClient.RequestHeadersSpec<?> headers = body == null ? spec : spec.bodyValue(body);
              return read.apply(headers.retrieve());
            })
        .onErrorMap(e -> !(e instanceof ApiException), this::translate)
        .timeout(Duration.ofSeconds(20))
        .onErrorMap(
            java.util.concurrent.TimeoutException.class,
            e -> unavailable("The identity provider did not answer in time"));
  }

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(HttpKeycloakAdmin.class);

  private Throwable translate(Throwable e) {
    if (e instanceof ApiException) {
      return e;
    }
    // Type and status only: a response body or a message could carry an address or a name.
    log.warn(
        "identity provider call failed: {}{}",
        e.getClass().getSimpleName(),
        e instanceof WebClientResponseException r ? " status=" + r.getStatusCode().value() : "");
    if (e instanceof WebClientResponseException r) {
      HttpStatusCode s = r.getStatusCode();
      if (s.value() == 409) {
        return ApiException.conflict("email_exists", "A user with this email already exists");
      }
      if (s.value() == 404) {
        return unavailable("The account was not found in the identity provider");
      }
    }
    return unavailable("The identity provider could not be reached");
  }

  private static ApiException unavailable(String title) {
    return new ApiException(HttpStatus.BAD_GATEWAY, "identity_provider_error", title);
  }

  @Override
  public Mono<String> createUser(String email, String firstName, String lastName) {
    Map<String, Object> rep =
        Map.of(
            "username", email,
            "email", email,
            "firstName", firstName,
            "lastName", lastName,
            "enabled", true,
            "emailVerified", false);
    return accessToken()
        .flatMap(
            bearer ->
                request(org.springframework.http.HttpMethod.POST, "/users", bearer)
                    .bodyValue(rep)
                    .exchangeToMono(
                        resp -> {
                          if (resp.statusCode().value() == 201) {
                            String location = resp.headers().asHttpHeaders().getFirst("Location");
                            if (location == null) {
                              return Mono.error(
                                  unavailable("The account was created without an id"));
                            }
                            return resp.releaseBody()
                                .thenReturn(location.substring(location.lastIndexOf('/') + 1));
                          }
                          if (resp.statusCode().value() == 409) {
                            return resp.releaseBody()
                                .then(
                                    Mono.error(
                                        ApiException.conflict(
                                            "email_exists",
                                            "A user with this email already exists")));
                          }
                          return resp.releaseBody()
                              .then(Mono.error(unavailable("The account could not be created")));
                        }))
        .onErrorMap(e -> !(e instanceof ApiException), this::translate);
  }

  @SuppressWarnings("unchecked")
  private Mono<Map<String, Object>> role(String name) {
    return call(
        org.springframework.http.HttpMethod.GET,
        "/roles/" + name,
        null,
        r -> r.bodyToMono(Map.class).map(m -> (Map<String, Object>) m));
  }

  @Override
  public Mono<Void> mapRealmRole(String id, String realmRole) {
    return role(realmRole)
        .flatMap(
            rep ->
                call(
                    org.springframework.http.HttpMethod.POST,
                    "/users/" + id + "/role-mappings/realm",
                    List.of(rep),
                    r -> r.toBodilessEntity().then()));
  }

  @Override
  public Mono<Void> replaceRealmRole(String id, String oldRole, String newRole) {
    if (oldRole.equals(newRole)) {
      return Mono.empty();
    }
    return role(oldRole)
        .flatMap(
            old ->
                call(
                    org.springframework.http.HttpMethod.DELETE,
                    "/users/" + id + "/role-mappings/realm",
                    List.of(old),
                    r -> r.toBodilessEntity().then()))
        .then(mapRealmRole(id, newRole));
  }

  @Override
  public Mono<Void> sendInvitation(String id) {
    String path =
        "/users/%s/execute-actions-email?client_id=%s&lifespan=%d&redirect_uri=%s"
            .formatted(
                id,
                "tender-spa",
                props.invitationLifespanSeconds(),
                java.net.URLEncoder.encode(
                    props.publicBaseUrl().replaceAll("/+$", "") + "/",
                    java.nio.charset.StandardCharsets.UTF_8));
    return call(
        org.springframework.http.HttpMethod.PUT,
        path,
        List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"),
        r -> r.toBodilessEntity().then());
  }

  @SuppressWarnings("unchecked")
  @Override
  public Mono<Void> updateName(String id, String first, String last) {
    return call(
            org.springframework.http.HttpMethod.GET,
            "/users/" + id,
            null,
            r -> r.bodyToMono(Map.class).map(m -> (Map<String, Object>) m))
        .flatMap(
            rep -> {
              Map<String, Object> copy = new java.util.LinkedHashMap<>(rep);
              copy.put("firstName", first);
              copy.put("lastName", last);
              return call(
                  org.springframework.http.HttpMethod.PUT,
                  "/users/" + id,
                  copy,
                  r -> r.toBodilessEntity().then());
            });
  }

  @SuppressWarnings("unchecked")
  @Override
  public Mono<Void> setEnabled(String id, boolean enabled) {
    return call(
            org.springframework.http.HttpMethod.GET,
            "/users/" + id,
            null,
            r -> r.bodyToMono(Map.class).map(m -> (Map<String, Object>) m))
        .flatMap(
            rep -> {
              Map<String, Object> copy = new java.util.LinkedHashMap<>(rep);
              copy.put("enabled", enabled);
              return call(
                  org.springframework.http.HttpMethod.PUT,
                  "/users/" + id,
                  copy,
                  r -> r.toBodilessEntity().then());
            });
  }

  @Override
  public Mono<Void> logout(String id) {
    return call(
        org.springframework.http.HttpMethod.POST,
        "/users/" + id + "/logout",
        null,
        r -> r.toBodilessEntity().then());
  }

  @Override
  public Mono<Void> deleteUser(String id) {
    return call(
        org.springframework.http.HttpMethod.DELETE,
        "/users/" + id,
        null,
        r -> r.toBodilessEntity().then());
  }

  @SuppressWarnings("unchecked")
  @Override
  public Mono<Integer> sessionIdleTimeoutSeconds() {
    return call(
            org.springframework.http.HttpMethod.GET,
            "",
            null,
            r -> r.bodyToMono(Map.class).map(m -> (Map<String, Object>) m))
        .map(m -> ((Number) m.get("ssoSessionIdleTimeout")).intValue());
  }
}
