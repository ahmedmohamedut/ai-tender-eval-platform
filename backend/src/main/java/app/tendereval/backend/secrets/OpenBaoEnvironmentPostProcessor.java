package app.tendereval.backend.secrets;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Secrets come from OpenBao when it is configured (PI 7.1, 7.2): the backend signs in with its own
 * AppRole and reads only the paths its policy allows. The values become environment properties
 * before any bean exists, under the names the configuration already uses, so a deployment without
 * OpenBao keeps working from environment variables (local development).
 *
 * <p>Read once at start. A rotated value is picked up by restarting the instance; re-reading after
 * an authentication failure and pool drain are later steps (docs/decisions.md).
 */
public class OpenBaoEnvironmentPostProcessor implements EnvironmentPostProcessor {

  static final String MOUNT = "kv";
  static final String BASE = "tender-eval/backend/";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    String address = env.getProperty("OPENBAO_ADDR");
    if (address == null || address.isBlank()) {
      return;
    }
    String roleId = required(env, "OPENBAO_ROLE_ID");
    String secretId = required(env, "OPENBAO_SECRET_ID");

    OpenBaoClient bao = new OpenBaoClient(address);
    bao.loginWithAppRole(roleId, secretId);

    Map<String, Object> values = new LinkedHashMap<>();
    Map<String, String> db = bao.readKv(MOUNT, BASE + "db-app");
    values.put("DB_APP_USERNAME", require(db, "username", "db-app"));
    values.put("DB_APP_PASSWORD", require(db, "password", "db-app"));
    Map<String, String> kc = bao.readKv(MOUNT, BASE + "keycloak-admin");
    values.put("KEYCLOAK_ADMIN_CLIENT_SECRET", require(kc, "client_secret", "keycloak-admin"));

    // Highest precedence: a stale value left in the environment must not win over the vault.
    env.getPropertySources().addFirst(new MapPropertySource("openbao", values));
  }

  private static String required(ConfigurableEnvironment env, String key) {
    String v = env.getProperty(key);
    if (v == null || v.isBlank()) {
      throw new IllegalStateException(key + " is required when OPENBAO_ADDR is set");
    }
    return v;
  }

  private static String require(Map<String, String> secret, String field, String path) {
    String v = secret.get(field);
    if (v == null || v.isBlank()) {
      throw new IllegalStateException("OpenBao secret " + path + " has no field " + field);
    }
    return v;
  }
}
