package app.tendereval.backend.testsupport;

import app.tendereval.backend.messaging.StatusConsumer;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The real application against a real PostgreSQL (provisioned and migrated as in `make up`). Only
 * NATS is replaced: the walking slice covers messaging end to end.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestAuthConfig.class)
public abstract class AbstractApiBase {

  public static final TestTokens TOKENS = new TestTokens();
  public static final String BOOTSTRAP_EMAIL = "bootstrap.admin@example.test";

  /** Registers every property of the application for a database at {@code url}. */
  public static void register(DynamicPropertyRegistry r, String url) {
    String hostPort = url.substring("jdbc:postgresql://".length());
    r.add("DB_URL", () -> "r2dbc:postgresql://" + hostPort);
    r.add("DB_APP_USERNAME", () -> "tender_app");
    r.add("DB_APP_PASSWORD", () -> PostgresFixture.APP_PASSWORD);
    r.add("DB_POOL_MAX_SIZE", () -> "5");
    r.add("DB_MIGRATION_URL", () -> url);
    r.add("DB_MIGRATION_USERNAME", () -> "tender_owner");
    r.add("DB_MIGRATION_PASSWORD", () -> PostgresFixture.OWNER_PASSWORD);
    r.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/test-migration");
    r.add("PUBLIC_BASE_URL", () -> "http://localhost:18080");
    r.add("KEYCLOAK_ISSUER_URI", () -> TestTokens.ISSUER);
    r.add("KEYCLOAK_JWK_SET_URI", () -> "http://unused.test/jwks");
    r.add("NATS_URL", () -> "nats://unused.test:4222");
    r.add("MANAGEMENT_PORT", () -> "0");
    r.add("BOOTSTRAP_ADMIN_EMAIL", () -> BOOTSTRAP_EMAIL);
    // No stale answers in tests: a deactivation applies on the next request.
    r.add("USER_STATUS_CACHE_SECONDS", () -> "0");
    r.add("RATE_LIMIT_UPLOADS_PER_MINUTE", () -> "1000");
  }

  @MockitoBean Connection connection;
  @MockitoBean JetStream jetStream;
  @MockitoBean JetStreamManagement jetStreamManagement;
  @MockitoBean StatusConsumer statusConsumer;

  @Autowired protected WebTestClient web;

  protected static String tokenFor(TestUser user) {
    return TOKENS.token(user.sub(), TestUsers.roles(user.role()), Map.of("email", user.email()));
  }

  protected static String tokenFor(String sub, String platformRole, String email) {
    return TOKENS.token(
        sub, TestUsers.roles(platformRole), email == null ? Map.of() : Map.of("email", email));
  }

  protected WebTestClient.RequestHeadersSpec<?> get(String path, TestUser user) {
    return web.get().uri(path).header("Authorization", "Bearer " + tokenFor(user));
  }

  protected WebTestClient.RequestBodySpec post(String path, TestUser user) {
    return web.post().uri(path).header("Authorization", "Bearer " + tokenFor(user));
  }

  protected WebTestClient.RequestBodySpec patch(String path, TestUser user) {
    return web.patch().uri(path).header("Authorization", "Bearer " + tokenFor(user));
  }

  protected static List<String> none() {
    return List.of();
  }
}
