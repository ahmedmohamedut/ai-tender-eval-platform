package app.tendereval.backend.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

/** The backend reads its secrets through its own AppRole, from nothing but the paths it names. */
class OpenBaoEnvironmentPostProcessorTest {

  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private volatile boolean denyLogin;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/auth/approle/login",
        ex -> {
          requests.add("login");
          String in = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          if (denyLogin
              || !in.contains("\"role_id\":\"role-1\"")
              || !in.contains("\"secret_id\":\"secret-1\"")) {
            reply(ex, 403, "{\"errors\":[\"permission denied\"]}");
            return;
          }
          reply(ex, 200, "{\"auth\":{\"client_token\":\"tok-abc\"}}");
        });
    server.createContext(
        "/v1/kv/data/tender-eval/backend/",
        ex -> {
          requests.add(
              ex.getRequestURI().getPath()
                  + "|"
                  + ex.getRequestHeaders().getFirst("X-Vault-Token"));
          String path = ex.getRequestURI().getPath();
          if (path.endsWith("/db-app")) {
            reply(
                ex,
                200,
                "{\"data\":{\"data\":{\"username\":\"tender_app\",\"password\":\"p-from-vault\"}}}");
          } else if (path.endsWith("/keycloak-admin")) {
            reply(ex, 200, "{\"data\":{\"data\":{\"client_secret\":\"kc-from-vault\"}}}");
          } else {
            reply(ex, 403, "{\"errors\":[\"permission denied\"]}");
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body)
      throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(status, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  private StandardEnvironment env(String roleId, String secretId) {
    StandardEnvironment env = new StandardEnvironment();
    MockPropertySource src = new MockPropertySource("test");
    src.setProperty("OPENBAO_ADDR", "http://127.0.0.1:" + server.getAddress().getPort());
    if (roleId != null) {
      src.setProperty("OPENBAO_ROLE_ID", roleId);
    }
    if (secretId != null) {
      src.setProperty("OPENBAO_SECRET_ID", secretId);
    }
    // A stale value in the environment must lose against the vault.
    src.setProperty("DB_APP_PASSWORD", "stale-from-env");
    env.getPropertySources().addFirst(src);
    return env;
  }

  @Test
  void secretsComeFromTheVaultAndWinOverTheEnvironment() {
    StandardEnvironment env = env("role-1", "secret-1");
    new OpenBaoEnvironmentPostProcessor().postProcessEnvironment(env, null);
    assertThat(env.getProperty("DB_APP_USERNAME")).isEqualTo("tender_app");
    assertThat(env.getProperty("DB_APP_PASSWORD")).isEqualTo("p-from-vault");
    assertThat(env.getProperty("KEYCLOAK_ADMIN_CLIENT_SECRET")).isEqualTo("kc-from-vault");
    // Only its own two paths, with the token the login returned.
    assertThat(requests)
        .containsExactly(
            "login",
            "/v1/kv/data/tender-eval/backend/db-app|tok-abc",
            "/v1/kv/data/tender-eval/backend/keycloak-admin|tok-abc");
  }

  @Test
  void withoutAnAddressNothingIsRead() {
    StandardEnvironment env = new StandardEnvironment();
    new OpenBaoEnvironmentPostProcessor().postProcessEnvironment(env, null);
    assertThat(requests).isEmpty();
    assertThat(env.getProperty("DB_APP_USERNAME")).isNull();
  }

  @Test
  void aRefusedLoginStopsTheStartWithoutEchoingTheSecret() {
    denyLogin = true;
    assertThatThrownBy(
            () ->
                new OpenBaoEnvironmentPostProcessor()
                    .postProcessEnvironment(env("role-1", "secret-1"), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("status 403")
        .hasMessageNotContaining("secret-1");
  }

  @Test
  void aWrongSecretIdIsRefused() {
    assertThatThrownBy(
            () ->
                new OpenBaoEnvironmentPostProcessor()
                    .postProcessEnvironment(env("role-1", "wrong"), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("wrong");
  }

  @Test
  void aMissingRoleIdIsNamed() {
    assertThatThrownBy(
            () ->
                new OpenBaoEnvironmentPostProcessor()
                    .postProcessEnvironment(env(null, "secret-1"), null))
        .hasMessageContaining("OPENBAO_ROLE_ID");
  }
}
