package app.tendereval.backend.secrets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A minimal OpenBao client: AppRole login and KV v2 reads (PI 7.2). It uses the JDK's HTTP client
 * so that reading secrets needs no framework to be up yet: it runs before the application context
 * exists. Nothing it reads is ever logged, and an error names the path and the status, never a
 * value.
 */
public final class OpenBaoClient {

  private final String address;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final JsonMapper json = JsonMapper.builder().build();
  private String token;

  public OpenBaoClient(String address) {
    this.address = address.replaceAll("/+$", "");
  }

  public void loginWithAppRole(String roleId, String secretId) {
    JsonNode body =
        post(
            "/v1/auth/approle/login",
            json.writeValueAsString(Map.of("role_id", roleId, "secret_id", secretId)),
            null);
    JsonNode t = body.path("auth").path("client_token");
    if (t.isMissingNode() || t.asString().isBlank()) {
      throw new IllegalStateException("OpenBao AppRole login returned no token");
    }
    this.token = t.asString();
  }

  /** Reads one KV v2 secret ({@code kv/tender-eval/backend/rustfs} is mount kv, path below). */
  public Map<String, String> readKv(String mount, String path) {
    JsonNode body = get("/v1/" + mount + "/data/" + path);
    Map<String, String> out = new LinkedHashMap<>();
    body.path("data")
        .path("data")
        .properties()
        .forEach(e -> out.put(e.getKey(), e.getValue().asString()));
    return out;
  }

  private JsonNode get(String uri) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(address + uri)).GET().timeout(Duration.ofSeconds(10));
    if (token != null) {
      b.header("X-Vault-Token", token);
    }
    return send(b.build(), uri);
  }

  private JsonNode post(String uri, String payload, String tokenOrNull) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(address + uri))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .timeout(Duration.ofSeconds(10));
    if (tokenOrNull != null) {
      b.header("X-Vault-Token", tokenOrNull);
    }
    return send(b.build(), uri);
  }

  private JsonNode send(HttpRequest request, String what) {
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException(
            "OpenBao refused " + what + " with status " + response.statusCode());
      }
      return json.readTree(response.body());
    } catch (IOException e) {
      throw new IllegalStateException("OpenBao could not be reached for " + what, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while reading " + what, e);
    }
  }
}
