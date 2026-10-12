package app.tendereval.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exit criterion of Phase 1: every write in the API produces an audit event.
 *
 * <p>The test finds every operation of {@code openapi.yaml} that is not a GET and that a controller
 * actually implements (overrides the generated default), and requires a scenario for it in {@link
 * AuditScenarios}: one successful call that adds exactly one audit event and one refused call that
 * adds none. An implemented write without a scenario fails the build, so a new write operation
 * cannot ship without its audit event being exercised.
 */
class AuditWriteCoverageIT extends AbstractApiIT {

  /** One operation's scenarios. */
  interface Scenario {
    /** Makes a successful call and returns the expected number of new audit events. */
    int success() throws Exception;

    /** Makes a refused call (wrong role, bad input, stale version): no audit event. */
    void refused() throws Exception;
  }

  /** Operations that write nothing the data model asks to audit, with the reason. */
  static final Map<String, String> NOT_AUDITED = Map.of();

  private static long auditCount() {
    return ((Number) TestUsers.scalar("SELECT count(*) FROM audit_events")).longValue();
  }

  /** operationId -> scenario; extended by each milestone that adds a write. */
  private Map<String, Scenario> scenarios() {
    Map<String, Scenario> m = new LinkedHashMap<>();
    m.put(
        "updateMe",
        new Scenario() {
          @Override
          public int success() {
            TestUser user = TestUsers.create("member");
            patch("/me", user)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("time_zone", "Asia/Tokyo"))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            TestUser user = TestUsers.create("member");
            patch("/me", user)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("time_zone", "Nowhere/None"))
                .exchange()
                .expectStatus()
                .isEqualTo(422);
          }
        });
    return m;
  }

  @Test
  void everyImplementedWriteHasAScenarioAndAuditsOnlyOnSuccess() throws Exception {
    Map<String, Scenario> scenarios = scenarios();
    Set<String> implemented = implementedWriteOperations();
    assertThat(implemented).as("implemented write operations").isNotEmpty();

    Set<String> missing = new LinkedHashSet<>(implemented);
    missing.removeAll(scenarios.keySet());
    missing.removeAll(NOT_AUDITED.keySet());
    assertThat(missing)
        .as("implemented write operations without an audit scenario (add one to scenarios())")
        .isEmpty();

    for (String op : implemented) {
      Scenario s = scenarios.get(op);
      if (s == null) {
        continue;
      }
      long before = auditCount();
      int expected = s.success();
      assertThat(auditCount() - before)
          .as("%s: audit events after success", op)
          .isEqualTo(expected);

      before = auditCount();
      s.refused();
      assertThat(auditCount() - before).as("%s: audit events after a refused call", op).isZero();
    }
  }

  @Test
  void aSuccessfulWriteEventNamesTheActorTheObjectAndTheChange() {
    TestUser user = TestUsers.create("member");
    patch("/me", user)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("time_zone", "Europe/Paris"))
        .exchange()
        .expectStatus()
        .isOk();
    Object row =
        TestUsers.scalar(
            "SELECT actor_user_id::text || '|' || actor_role || '|' || source || '|' || object_type || '|'"
                + " || action || '|' || (before ->> 'time_zone') || '|' || (after ->> 'time_zone')"
                + " || '|' || (request_id IS NOT NULL)"
                + " FROM audit_events WHERE object_id = ? ORDER BY occurred_at DESC LIMIT 1",
            user.id());
    assertThat(row).isEqualTo(user.id() + "|member|person|user|user_updated|UTC|Europe/Paris|true");
  }

  @Test
  void aNoOpChangeWritesNothingAndNoEvent() {
    TestUser user = TestUsers.create("member");
    long before = auditCount();
    patch("/me", user)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("time_zone", "UTC"))
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(auditCount() - before).isZero();
  }

  // ---- reflection over the contract and the controllers ----------------------------------------

  private static final Pattern OPERATION = Pattern.compile("operationId: (\\w+)");

  private Set<String> implementedWriteOperations() throws Exception {
    Set<String> writes = new LinkedHashSet<>();
    List<Class<?>> controllers = controllers();
    for (Class<?> controller : controllers) {
      for (Class<?> api : controller.getInterfaces()) {
        for (Method iface : api.getMethods()) {
          RequestMapping mapping = iface.getAnnotation(RequestMapping.class);
          if (mapping == null
              || mapping.method().length == 0
              || mapping.method()[0] == RequestMethod.GET) {
            continue;
          }
          Method impl;
          try {
            impl = controller.getMethod(iface.getName(), iface.getParameterTypes());
          } catch (NoSuchMethodException e) {
            continue;
          }
          if (impl.getDeclaringClass() == controller) {
            writes.add(iface.getName());
          }
        }
      }
    }
    // Each must be an operation of the contract.
    String spec = Files.readString(Path.of("..", "api", "openapi.yaml"));
    Set<String> known = new LinkedHashSet<>();
    Matcher m = OPERATION.matcher(spec);
    while (m.find()) {
      known.add(m.group(1));
    }
    assertThat(known).containsAll(writes);
    return writes;
  }

  private static List<Class<?>> controllers() throws Exception {
    List<Class<?>> found = new ArrayList<>();
    Path root = Path.of("build", "classes", "java", "main");
    try (var s = Files.walk(root)) {
      for (Path p :
          (Iterable<Path>) s.filter(f -> f.toString().endsWith("Controller.class"))::iterator) {
        String name = root.relativize(p).toString().replace('/', '.').replaceAll("\\.class$", "");
        Class<?> c = Class.forName(name);
        if (c.isAnnotationPresent(RestController.class)) {
          found.add(c);
        }
      }
    }
    return found;
  }
}
