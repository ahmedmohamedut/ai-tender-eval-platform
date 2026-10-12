package app.tendereval.backend.security;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Which platform roles may call which operation, read from {@code x-roles} in the contract itself
 * (UX-ROL-01, PI 2.1). This is a first line of defence; the database policies decide again. {@code
 * chair} in the contract is a designation of a member, so it maps to the member role here and the
 * per-tender check is the service's and the database's.
 */
@Component
public class OperationRoles {

  private record Rule(HttpMethod method, Pattern path, String operationId, Set<Role> roles) {}

  private final List<Rule> rules = new ArrayList<>();

  @SuppressWarnings("unchecked")
  public OperationRoles() throws Exception {
    LoaderOptions options = new LoaderOptions();
    options.setCodePointLimit(20 * 1024 * 1024);
    try (InputStream in = new ClassPathResource("openapi/openapi.yaml").getInputStream()) {
      Map<String, Object> spec = new Yaml(options).load(in);
      Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
      for (var path : paths.entrySet()) {
        for (var op : path.getValue().entrySet()) {
          HttpMethod method = httpMethod(op.getKey());
          if (method == null || !(op.getValue() instanceof Map<?, ?> o)) {
            continue;
          }
          Object roles = o.get("x-roles");
          if (!(roles instanceof List<?> list)) {
            continue;
          }
          Set<Role> allowed = EnumSet.noneOf(Role.class);
          for (Object r : list) {
            switch (String.valueOf(r)) {
              case "admin" -> allowed.add(Role.ADMIN);
              case "member", "chair" -> allowed.add(Role.MEMBER);
              case "observer" -> allowed.add(Role.OBSERVER);
              case "any" -> allowed.addAll(EnumSet.allOf(Role.class));
              default -> throw new IllegalStateException("unknown role in x-roles: " + r);
            }
          }
          rules.add(
              new Rule(
                  method, template(path.getKey()), String.valueOf(o.get("operationId")), allowed));
        }
      }
    }
  }

  private static HttpMethod httpMethod(String key) {
    return switch (key) {
      case "get" -> HttpMethod.GET;
      case "post" -> HttpMethod.POST;
      case "put" -> HttpMethod.PUT;
      case "patch" -> HttpMethod.PATCH;
      case "delete" -> HttpMethod.DELETE;
      default -> null;
    };
  }

  private static Pattern template(String path) {
    return Pattern.compile("^" + path.replaceAll("\\{[^/}]+}", "[^/]+") + "/?$");
  }

  /** Empty when the request matches no operation of the contract. */
  public Optional<Set<Role>> allowed(HttpMethod method, String pathWithinApi) {
    for (Rule r : rules) {
      if (r.method().equals(method) && r.path().matcher(pathWithinApi).matches()) {
        return Optional.of(r.roles());
      }
    }
    return Optional.empty();
  }

  public int operationCount() {
    return rules.size();
  }
}
