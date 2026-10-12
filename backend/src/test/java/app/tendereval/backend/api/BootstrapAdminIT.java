package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiBase;
import app.tendereval.backend.testsupport.SchemaFixture;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * PI 3.7 on an empty database: the first administrator is created on their own first sign-in, and
 * only while no administrator exists.
 */
class BootstrapAdminIT extends AbstractApiBase {

  static final String URL = SchemaFixture.createDatabase("bootstrap_it");

  @DynamicPropertySource
  static void emptyDatabase(DynamicPropertyRegistry r) {
    register(r, URL);
  }

  private static long adminCount() throws Exception {
    try (Connection c = DriverManager.getConnection(URL, "tender_owner", "owner-test");
        ResultSet rs =
            c.createStatement().executeQuery("SELECT count(*) FROM users WHERE role = 'admin'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void firstSignInOfTheConfiguredEmailCreatesTheAdministratorOnce() throws Exception {
    assertThat(adminCount()).isZero();

    String stranger = tokenFor(UUID.randomUUID().toString(), "admin", "someone.else@example.test");
    web.get()
        .uri("/me")
        .header("Authorization", "Bearer " + stranger)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("not_invited");
    assertThat(adminCount()).isZero();

    String sub = UUID.randomUUID().toString();
    String token = tokenFor(sub, "admin", BOOTSTRAP_EMAIL);
    web.get()
        .uri("/me")
        .header("Authorization", "Bearer " + token)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.user.role")
        .isEqualTo("admin")
        .jsonPath("$.user.status")
        .isEqualTo("active")
        .jsonPath("$.user.email")
        .isEqualTo(BOOTSTRAP_EMAIL);
    assertThat(adminCount()).isEqualTo(1);

    // The same person again finds their row.
    web.get()
        .uri("/me")
        .header("Authorization", "Bearer " + token)
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(adminCount()).isEqualTo(1);

    // A second subject with the configured email is not a second bootstrap.
    String again = tokenFor(UUID.randomUUID().toString(), "admin", BOOTSTRAP_EMAIL);
    web.get()
        .uri("/me")
        .header("Authorization", "Bearer " + again)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("not_invited");
    assertThat(adminCount()).isEqualTo(1);
  }
}
