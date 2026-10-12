package app.tendereval.backend.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/** Validates test tokens with the production validators and a throwaway public key. */
@TestConfiguration
public class TestAuthConfig {

  @Bean
  @Primary
  ReactiveJwtDecoder testDecoder() {
    return AbstractApiBase.TOKENS.decoder();
  }
}
