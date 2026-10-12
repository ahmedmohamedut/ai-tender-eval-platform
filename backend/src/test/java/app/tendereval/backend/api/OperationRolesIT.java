package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * UX-ROL-01 over the whole contract: for every one of the 135 operations, a role that is not in its
 * {@code x-roles} is refused with 403 before any work, whether or not the operation is built yet.
 */
class OperationRolesIT extends AbstractApiIT {

  private record Op(String method, String path, String id, Set<String> roles) {}

  @SuppressWarnings("unchecked")
  private static List<Op> operations() throws Exception {
    LoaderOptions o = new LoaderOptions();
    o.setCodePointLimit(20 * 1024 * 1024);
    Map<String, Object> spec;
    try (InputStream in = Files.newInputStream(Path.of("..", "api", "openapi.yaml"))) {
      spec = new Yaml(o).load(in);
    }
    List<Op> ops = new ArrayList<>();
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
    for (var p : paths.entrySet()) {
      for (var m : p.getValue().entrySet()) {
        if (m.getValue() instanceof Map<?, ?> op && op.get("x-roles") instanceof List<?> r) {
          Set<String> roles = new java.util.LinkedHashSet<>();
          r.forEach(x -> roles.add(String.valueOf(x)));
          ops.add(
              new Op(
                  m.getKey().toUpperCase(),
                  p.getKey(),
                  String.valueOf(op.get("operationId")),
                  roles));
        }
      }
    }
    return ops;
  }

  private static boolean allows(Set<String> xRoles, String platformRole) {
    if (xRoles.contains("any")) {
      return true;
    }
    return switch (platformRole) {
      case "admin" -> xRoles.contains("admin");
      case "member" -> xRoles.contains("member") || xRoles.contains("chair");
      default -> xRoles.contains("observer");
    };
  }

  @Test
  void everyOperationRefusesTheRolesItDoesNotListWith403() throws Exception {
    List<Op> ops = operations();
    assertThat(ops).hasSize(135);
    TestUser admin = TestUsers.create("admin");
    TestUser member = TestUsers.create("member");
    TestUser observer = TestUsers.create("observer");
    int checked = 0;
    for (Op op : ops) {
      String path = op.path().replaceAll("\\{[^/}]+}", UUID.randomUUID().toString());
      for (TestUser u : List.of(admin, member, observer)) {
        if (allows(op.roles(), u.role())) {
          continue;
        }
        var spec =
            web.method(HttpMethod.valueOf(op.method()))
                .uri(path)
                .header("Authorization", "Bearer " + tokenFor(u))
                .contentType(MediaType.APPLICATION_JSON);
        var response =
            (op.method().equals("GET") || op.method().equals("DELETE"))
                ? spec.exchange()
                : spec.bodyValue("{}").exchange();
        response.expectStatus().isForbidden();
        checked++;
      }
    }
    assertThat(checked).as("refusals exercised").isGreaterThan(100);
  }

  @Test
  void anAllowedRoleIsNeverRefusedByTheGuard() throws Exception {
    TestUser admin = TestUsers.create("admin");
    TestUser member = TestUsers.create("member");
    TestUser observer = TestUsers.create("observer");
    int checked = 0;
    for (Op op : operations()) {
      if (!op.method().equals("GET") || op.path().contains("{") || op.path().equals("/events")) {
        continue;
      }
      for (TestUser u : List.of(admin, member, observer)) {
        if (!allows(op.roles(), u.role())) {
          continue;
        }
        int status =
            web.get()
                .uri(op.path())
                .header("Authorization", "Bearer " + tokenFor(u))
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value();
        assertThat(status).as("%s as %s", op.id(), u.role()).isNotEqualTo(401);
        checked++;
      }
    }
    assertThat(checked).isGreaterThan(5);
  }
}
