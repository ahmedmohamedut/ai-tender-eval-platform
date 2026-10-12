package app.tendereval.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

/**
 * Rule 1 and implementation guide 5.3, against a real PostgreSQL: the identity of a request is
 * transaction-local and a pooled connection never carries it into the next request, with a pool of
 * exactly one connection so that every request reuses the same session.
 */
class RequestTransactionIT extends AbstractApiIT {

  @DynamicPropertySource
  static void onePooledConnection(DynamicPropertyRegistry r) {
    r.add("DB_POOL_MAX_SIZE", () -> "1");
  }

  @Autowired DatabaseClient db;

  private Map<String, Object> context(TestUser user) {
    return get("/_test/context", user)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  private List<String> notes(TestUser user) {
    List<Map<String, Object>> rows =
        get("/_test/notes", user)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
            .returnResult()
            .getResponseBody();
    return rows.stream().map(r -> (String) r.get("note")).toList();
  }

  @Test
  void requestRunsAsThePlatformUserWithTheirStoredRole() {
    TestUser member = TestUsers.create("member");
    Map<String, Object> ctx = context(member);
    assertThat(ctx.get("user_id")).isEqualTo(member.id().toString());
    assertThat(ctx.get("role")).isEqualTo("member");
    assertThat(ctx.get("db_user")).isEqualTo("tender_app");
  }

  @Test
  void identityDoesNotLeakToTheNextUseOfTheSameConnection() {
    TestUser admin = TestUsers.create("admin");
    Map<String, Object> inside = context(admin);
    assertThat(inside.get("user_id")).isEqualTo(admin.id().toString());

    // Same physical connection (pool size 1), outside any request transaction.
    Map<String, Object> after =
        db.sql(
                "SELECT current_setting('app.user_id', true) AS user_id,"
                    + " current_setting('app.role', true) AS role, pg_backend_pid() AS pid")
            .fetch()
            .one()
            .block();
    assertThat(after.get("pid")).as("same session").isEqualTo(inside.get("pid"));
    assertThat(String.valueOf(after.get("user_id"))).isIn("", "null");
    assertThat(String.valueOf(after.get("role"))).isIn("", "null");
  }

  @Test
  void concurrentRequestsNeverSeeEachOthersIdentity() throws Exception {
    List<TestUser> users = IntStream.range(0, 6).mapToObj(i -> TestUsers.create("member")).toList();
    ExecutorService pool = Executors.newFixedThreadPool(12);
    List<CompletableFuture<Void>> work = new ArrayList<>();
    for (int round = 0; round < 8; round++) {
      for (TestUser user : users) {
        work.add(
            CompletableFuture.runAsync(
                () -> assertThat(context(user).get("user_id")).isEqualTo(user.id().toString()),
                pool));
      }
    }
    CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).get();
    pool.shutdown();
  }

  @Test
  void rowLevelSecurityFollowsTheRequestIdentity() {
    TestUser a = TestUsers.create("member");
    TestUser b = TestUsers.create("member");
    TestUser admin = TestUsers.create("admin");

    post("/_test/notes?owner=" + a.id() + "&note=mine-a", a).exchange().expectStatus().isOk();
    post("/_test/notes?owner=" + b.id() + "&note=mine-b", b).exchange().expectStatus().isOk();
    // A member cannot write a row for somebody else (WITH CHECK).
    post("/_test/notes?owner=" + b.id() + "&note=forged", a)
        .exchange()
        .expectStatus()
        .is4xxClientError()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("forbidden");

    assertThat(notes(a)).containsExactly("mine-a");
    assertThat(notes(b)).containsExactly("mine-b");
    assertThat(notes(admin)).contains("mine-a", "mine-b").doesNotContain("forged");
  }

  // ---- the application role cannot weaken the policies -------------------------------------

  private void assertRefused(String sql) {
    assertThatThrownBy(() -> db.sql(sql).fetch().rowsUpdated().block())
        .as(sql)
        .isInstanceOf(Exception.class);
  }

  @Test
  void turningRowSecurityOffMakesTheQueryFailInsteadOfBypassingIt() {
    // The setting is session-level, so it is reset before the pooled connection is released.
    var result =
        db.inConnection(
            c -> {
              Mono<Void> reset =
                  Mono.from(c.createStatement("RESET row_security").execute())
                      .flatMap(r -> Mono.from(r.getRowsUpdated()))
                      .then();
              Mono<Void> attempt =
                  Mono.from(c.createStatement("SET row_security = off").execute())
                      .flatMap(r -> Mono.from(r.getRowsUpdated()))
                      .then(
                          Mono.from(c.createStatement("SELECT * FROM rls_probe").execute())
                              .flatMapMany(r -> r.map((row, meta) -> 1))
                              .then());
              return attempt.onErrorResume(e -> reset.then(Mono.error(e))).then(reset);
            });
    assertThatThrownBy(result::block).isInstanceOf(Exception.class);
    String setting =
        db.sql("SHOW row_security").fetch().one().block().values().iterator().next().toString();
    assertThat(setting).isEqualTo("on");
  }

  @Test
  void applicationRoleCannotDisableOrChangePolicies() {
    assertRefused("ALTER TABLE rls_probe DISABLE ROW LEVEL SECURITY");
    assertRefused("DROP POLICY rls_probe_read ON rls_probe");
    assertRefused("CREATE POLICY allow_all ON rls_probe USING (true)");
    assertRefused("DROP TABLE rls_probe");
    assertRefused("SET ROLE tender_owner");
    assertRefused("ALTER ROLE tender_app BYPASSRLS");
  }

  // ---- the token is not enough --------------------------------------------------------------

  @Test
  void requestsWithoutExactlyOneRoleAreForbiddenWithProblemJson() {
    TestUser who = TestUsers.create("admin");
    String two =
        TOKENS.token(
            who.sub(), List.of("procurement_admin", "observer"), Map.of("email", who.email()));
    String none = TOKENS.token(who.sub(), List.of("offline_access"), Map.of("email", who.email()));
    for (String token : List.of(two, none)) {
      web.get()
          .uri("/_test/context")
          .header("Authorization", "Bearer " + token)
          .exchange()
          .expectStatus()
          .isForbidden()
          .expectHeader()
          .contentTypeCompatibleWith("application/problem+json")
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("forbidden")
          .jsonPath("$.trace_id")
          .isNotEmpty();
    }
  }

  @Test
  void missingOrWrongTokensAreRefusedWith401ProblemJson() {
    web.get()
        .uri("/_test/context")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .contentTypeCompatibleWith("application/problem+json")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("unauthenticated");

    TestUser user = TestUsers.create("observer");
    String wrongAudience = TOKENS.token(user.sub(), List.of("observer"), Map.of("aud", "account"));
    web.get()
        .uri("/_test/context")
        .header("Authorization", "Bearer " + wrongAudience)
        .exchange()
        .expectStatus()
        .isUnauthorized();

    String wrongParty =
        TOKENS.token(user.sub(), List.of("observer"), Map.of("azp", "tender-backend-admin"));
    web.get()
        .uri("/_test/context")
        .header("Authorization", "Bearer " + wrongParty)
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }
}
