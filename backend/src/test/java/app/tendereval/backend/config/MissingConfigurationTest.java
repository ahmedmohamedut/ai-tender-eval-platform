package app.tendereval.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.tendereval.backend.BackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class MissingConfigurationTest {

  @Test
  void applicationRefusesToStartWithoutRequiredKeys() {
    // The test process defines none of DB_URL, KEYCLOAK_ISSUER_URI, NATS_URL ...
    assertThatThrownBy(
            () ->
                new SpringApplicationBuilder(BackendApplication.class)
                    .web(WebApplicationType.NONE)
                    .run())
        .satisfies(
            e -> {
              Throwable root = e;
              StringBuilder chain = new StringBuilder();
              while (root != null) {
                chain.append(root.getMessage()).append(" | ");
                root = root.getCause();
              }
              assertThat(chain.toString()).contains("DB_URL");
            });
  }
}
