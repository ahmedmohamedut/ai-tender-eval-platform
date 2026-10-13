package app.tendereval.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.tendereval.backend.config.AppProperties;
import app.tendereval.backend.web.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real client against a fake Keycloak that records every request line, so a mistake in paths,
 * encoding or bodies shows here and not as a 502 in the browser.
 */
class HttpKeycloakAdminTest {

  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private volatile int createStatus = 201;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange ex) throws IOException {
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String line =
        ex.getRequestMethod()
            + " "
            + ex.getRequestURI().getRawPath()
            + (ex.getRequestURI().getRawQuery() == null
                ? ""
                : "?" + ex.getRequestURI().getRawQuery());
    requests.add(line);
    bodies.add(body);
    String path = ex.getRequestURI().getRawPath();
    if (path.endsWith("/protocol/openid-connect/token")) {
      reply(ex, 200, "{\"access_token\":\"svc-token\",\"expires_in\":300}");
    } else if (!"Bearer svc-token".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
      reply(ex, 401, "{}");
    } else if (ex.getRequestMethod().equals("POST") && path.endsWith("/users")) {
      if (createStatus == 201) {
        ex.getResponseHeaders()
            .add("Location", "http://localhost:18081/admin/realms/tender-eval/users/kc-user-1");
      }
      reply(ex, createStatus, "");
    } else if (path.contains("/roles/")) {
      reply(ex, 200, "{\"id\":\"role-id-9\",\"name\":\"committee_member\"}");
    } else if (path.endsWith("/tender-eval") && ex.getRequestMethod().equals("GET")) {
      reply(ex, 200, "{\"ssoSessionIdleTimeout\":1800}");
    } else {
      reply(ex, 204, "");
    }
  }

  private static void reply(HttpExchange ex, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    if (b.length > 0) {
      ex.getResponseHeaders().add("Content-Type", "application/json");
    }
    ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
    if (b.length > 0) {
      ex.getResponseBody().write(b);
    }
    ex.close();
  }

  private HttpKeycloakAdmin client() {
    String root = "http://127.0.0.1:" + server.getAddress().getPort();
    AppProperties props =
        new AppProperties(
            "http://localhost:18080",
            "http://i",
            "http://j",
            "tender-api",
            "tender-spa",
            "nats://n",
            false,
            "",
            30,
            1800,
            10,
            120,
            20,
            10,
            root + "/admin/realms/tender-eval",
            "tender-backend-admin",
            "s3cret",
            259200);
    return new HttpKeycloakAdmin(props);
  }

  @Test
  void theInvitationFlowSendsTheExactRequestsKeycloakNeeds() {
    HttpKeycloakAdmin kc = client();
    String id = kc.createUser("ada@example.test", "Ada", "Lovelace").block();
    kc.mapRealmRole(id, "committee_member").block();
    kc.sendInvitation(id).block();

    assertThat(id).isEqualTo("kc-user-1");
    assertThat(requests.get(0)).isEqualTo("POST /realms/tender-eval/protocol/openid-connect/token");
    assertThat(bodies.get(0))
        .contains("grant_type=client_credentials")
        .contains("client_id=tender-backend-admin");
    assertThat(requests)
        .contains(
            "POST /admin/realms/tender-eval/users",
            "GET /admin/realms/tender-eval/roles/committee_member",
            "POST /admin/realms/tender-eval/users/kc-user-1/role-mappings/realm");
    int roleMapping =
        requests.indexOf("POST /admin/realms/tender-eval/users/kc-user-1/role-mappings/realm");
    assertThat(bodies.get(roleMapping)).contains("\"id\":\"role-id-9\"").startsWith("[");
    // The redirect uri is encoded once, not twice (a %253A here makes Keycloak refuse the link).
    String mail =
        requests.stream()
            .filter(r -> r.contains("execute-actions-email"))
            .findFirst()
            .orElseThrow();
    assertThat(mail)
        .startsWith("PUT /admin/realms/tender-eval/users/kc-user-1/execute-actions-email?")
        .contains("client_id=tender-spa")
        .contains("lifespan=259200")
        .contains("redirect_uri=http%3A%2F%2Flocalhost%3A18080%2F")
        .doesNotContain("%253A");
    assertThat(bodies.get(requests.indexOf(mail)))
        .isEqualTo("[\"VERIFY_EMAIL\",\"UPDATE_PASSWORD\"]");
  }

  @Test
  void theTokenIsFetchedOnceAndReused() {
    HttpKeycloakAdmin kc = client();
    kc.setEnabled("kc-user-1", false).block();
    kc.logout("kc-user-1").block();
    kc.sessionIdleTimeoutSeconds().block();
    assertThat(requests.stream().filter(r -> r.contains("/token")).count()).isEqualTo(1);
    assertThat(requests).contains("POST /admin/realms/tender-eval/users/kc-user-1/logout");
  }

  @Test
  void aDuplicateAccountIsAConflictWithAStableCode() {
    createStatus = 409;
    assertThatThrownBy(() -> client().createUser("ada@example.test", "Ada", "L").block())
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status().value()).isEqualTo(409);
              assertThat(e.code()).isEqualTo("email_exists");
            });
  }

  @Test
  void anUnreachableProviderIsA502NotAnUnhandledError() {
    server.stop(0);
    assertThatThrownBy(() -> client().sendInvitation("kc-user-1").block())
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status().value()).isEqualTo(502);
              assertThat(e.code()).isEqualTo("identity_provider_error");
            });
  }
}
