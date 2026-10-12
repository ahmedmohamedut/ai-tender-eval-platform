package app.tendereval.backend.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestIdentityConfig {

  @Bean
  @Primary
  FakeKeycloakAdmin fakeKeycloakAdmin() {
    return new FakeKeycloakAdmin();
  }
}
