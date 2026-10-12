package app.tendereval.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.tendereval.backend.messaging.StatusConsumer;
import app.tendereval.backend.testsupport.PostgresFixture;
import app.tendereval.backend.testsupport.TestTokens;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

/**
 * Rule 1 and implementation guide 5.3, against a real PostgreSQL: the identity of a request is
 * transaction-local, a pooled connection never carries it into the next request, and the
 * application role cannot switch the policies off.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class RequestTransactionIT {

  static final PostgreSQLContainer PG = PostgresFixture.start();
  static final TestTokens TOKENS = new TestTokens();

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    String host = PG.getHost() + ":" + PG.getMappedPort(5432);
    r.add("DB_URL", () -> "r2dbc:postgresql://" + host + "/" + PostgresFixture.DB);
    r.add("DB_APP_USERNAME", () -> "tender_app");
    r.add("DB_APP_PASSWORD", () -> PostgresFixture.APP_PASSWORD);
    // One connection in the pool: every request is forced onto the same session.
    r.add("DB_POOL_MAX_SIZE", () -> "1");
    r.add("DB_MIGRATION_URL", () -> "jdbc:postgresql://" + host + "/" + PostgresFixture.DB);
    r.add("DB_MIGRATION_USERNAME", () -> "tender_owner");
    r.add("DB_MIGRATION_PASSWORD", () -> PostgresFixture.OWNER_PASSWORD);
    r.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/test-migration");
    r.add("PUBLIC_BASE_URL", () -> "http://localhost:18080");
    r.add("KEYCLOAK_ISSUER_URI", () -> TestTokens.ISSUER);
    r.add("KEYCLOAK_JWK_SET_URI", () -> "http://unused.test/jwks");
    r.add("NATS_URL", () -> "nats://unused.test:4222");
    r.add("MANAGEMENT_PORT", () -> "0");
  }

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    ReactiveJwtDecoder testDecoder() {
      return TOKENS.decoder();
    }
  }

  // Messaging is covered by the walking slice; here NATS is not part of what is tested.
  @MockitoBean Connection connection;
  @MockitoBean JetStream jetStream;
  @MockitoBean JetStreamManagement jetStreamManagement;
  @MockitoBean StatusConsumer statusConsumer;

  @Autowired WebTestClient web;
  @Autowired DatabaseClient db;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private WebTestClient.RequestHeadersSpec<?> get(String path, String token) {
    return web.get().uri(path).header("Authorization", "Bearer " + token);
  }

  private Map<String, Object> context(String token) {
    return get("/_test/context", token)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  @Test
  void requestRunsAsTheSignedInUserWithMappedRole() {
    String t = TOKENS.token("11111111-1111-4111-8111-111111111111", List.of("committee_member"));
    Map<String, Object> ctx = context(t);
    assertThat(ctx.get("user_id")).isEqualTo("11111111-1111-4111-8111-111111111111");
    assertThat(ctx.get("role")).isEqualTo("member");
    assertThat(ctx.get("db_user")).isEqualTo("tender_app");
  }

  @Test
  void identityDoesNotLeakToTheNextUseOfTheSameConnection() {
    String t = TOKENS.token("22222222-2222-4222-8222-222222222222", List.of("procurement_admin"));
    Map<String, Object> inside = context(t);
    Object pid = inside.get("pid");
    assertThat(inside.get("user_id")).isEqualTo("22222222-2222-4222-8222-222222222222");

    // Same physical connection (pool size 1), outside any request transaction.
    Map<String, Object> after =
        db.sql(
                "SELECT current_setting('app.user_id', true) AS user_id,"
                    + " current_setting('app.role', true) AS role,"
                    + " pg_backend_pid() AS pid")
            .fetch()
            .one()
            .block();
    assertThat(after.get("pid")).as("same session").isEqualTo(pid);
    assertThat(String.valueOf(after.get("user_id"))).isIn("", "null");
    assertThat(String.valueOf(after.get("role"))).isIn("", "null");
  }

  @Test
  void concurrentRequestsNeverSeeEachOthersIdentityOrRows() throws Exception {
    List<String> users =
        IntStream.range(0, 6).mapToObj(i -> "00000000-0000-4000-8000-00000000000" + i).toList();
    ExecutorService pool = Executors.newFixedThreadPool(12);
    List<CompletableFuture<Void>> work = new ArrayList<>();
    for (int round = 0; round < 8; round++) {
      for (String user : users) {
        work.add(
            CompletableFuture.runAsync(
                () -> {
                  String t = TOKENS.token(user, List.of("committee_member"));
                  assertThat(context(t).get("user_id")).isEqualTo(user);
                },
                pool));
      }
    }
    CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).get();
    pool.shutdown();
  }

  @Test
  void rowLevelSecurityFollowsTheRequestIdentity() {
    String a = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    String b = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    String ta = TOKENS.token(a, List.of("committee_member"));
    String tb = TOKENS.token(b, List.of("committee_member"));
    String admin =
        TOKENS.token("cccccccc-cccc-4ccc-8ccc-cccccccccccc", List.of("procurement_admin"));

    web.post()
        .uri("/_test/notes?owner=" + a + "&note=mine-a")
        .header("Authorization", "Bearer " + ta)
        .exchange()
        .expectStatus()
        .isOk();
    web.post()
        .uri("/_test/notes?owner=" + b + "&note=mine-b")
        .header("Authorization", "Bearer " + tb)
        .exchange()
        .expectStatus()
        .isOk();

    // Negative: a member cannot write a row for somebody else (policy WITH CHECK).
    web.post()
        .uri("/_test/notes?owner=" + b + "&note=forged")
        .header("Authorization", "Bearer " + ta)
        .exchange()
        .expectStatus()
        .is5xxServerError();

    assertThat(notes(ta)).containsExactly("mine-a");
    assertThat(notes(tb)).containsExactly("mine-b");
    assertThat(notes(admin)).contains("mine-a", "mine-b").doesNotContain("forged");
  }

  private List<String> notes(String token) {
    List<Map<String, Object>> rows =
        get("/_test/notes", token)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
            .returnResult()
            .getResponseBody();
    return rows.stream().map(r -> (String) r.get("note")).toList();
  }

  // ---- the application role cannot weaken the policies -------------------------------------

  private void assertRefused(String sql) {
    assertThatThrownBy(() -> db.sql(sql).fetch().rowsUpdated().block())
        .as(sql)
        .isInstanceOf(Exception.class);
  }

  @Test
  void applicationRoleIsNeitherSuperuserNorBypassRlsNorOwner() {
    Map<String, Object> row =
        db.sql(
                "SELECT r.rolsuper, r.rolbypassrls, r.rolcreaterole, r.rolcreatedb,"
                    + " (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = 'rls_probe')"
                    + " AS table_owner FROM pg_roles r WHERE r.rolname = current_user")
            .fetch()
            .one()
            .block();
    assertThat(row.get("rolsuper")).isEqualTo(false);
    assertThat(row.get("rolbypassrls")).isEqualTo(false);
    assertThat(row.get("rolcreaterole")).isEqualTo(false);
    assertThat(row.get("rolcreatedb")).isEqualTo(false);
    assertThat(row.get("table_owner")).isEqualTo("tender_owner");
  }

  @Test
  void applicationRoleCannotDisableOrChangePolicies() {
    assertRefused("ALTER TABLE rls_probe DISABLE ROW LEVEL SECURITY");
    assertRefused("ALTER TABLE rls_probe NO FORCE ROW LEVEL SECURITY");
    assertRefused("DROP POLICY rls_probe_read ON rls_probe");
    assertRefused("CREATE POLICY allow_all ON rls_probe USING (true)");
    assertRefused("ALTER POLICY rls_probe_read ON rls_probe USING (true)");
    assertRefused("DROP TABLE rls_probe");
  }

  @Test
  void applicationRoleCannotBecomeAnotherRoleOrGainBypass() {
    assertRefused("SET ROLE tender_owner");
    assertRefused("SET ROLE postgres");
    assertRefused("ALTER ROLE tender_app BYPASSRLS");
    assertRefused("ALTER ROLE tender_app SUPERUSER");
    assertRefused("GRANT tender_owner TO tender_app");
  }

  @Test
  void turningRowSecurityOffMakesTheQueryFailInsteadOfBypassingIt() {
    // For a role without BYPASSRLS, row_security = off raises an error when a policy applies.
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

    // The session is back to normal for the next user of the connection.
    String setting =
        db.sql("SHOW row_security").fetch().one().block().values().iterator().next().toString();
    assertThat(setting).isEqualTo("on");
  }

  @Test
  void requestsWithoutExactlyOneRoleAreForbiddenWithProblemJson() {
    String two =
        TOKENS.token(
            "dddddddd-dddd-4ddd-8ddd-dddddddddddd", List.of("procurement_admin", "observer"));
    String none = TOKENS.token("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", List.of("offline_access"));
    for (String token : Set.of(two, none)) {
      get("/_test/context", token)
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

    String wrongAudience =
        TOKENS.token(
            "ffffffff-ffff-4fff-8fff-ffffffffffff", List.of("observer"), Map.of("aud", "account"));
    get("/_test/context", wrongAudience).exchange().expectStatus().isUnauthorized();

    String wrongParty =
        TOKENS.token(
            "ffffffff-ffff-4fff-8fff-ffffffffffff",
            List.of("observer"),
            Map.of("azp", "tender-backend-admin"));
    get("/_test/context", wrongParty).exchange().expectStatus().isUnauthorized();
  }
}
