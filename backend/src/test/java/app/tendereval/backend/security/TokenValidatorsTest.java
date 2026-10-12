package app.tendereval.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class TokenValidatorsTest {

  private static final String ISSUER = "http://localhost:18081/realms/tender-eval";
  private final OAuth2TokenValidator<Jwt> validator =
      TokenValidators.create(ISSUER, "tender-api", "tender-spa");

  private static Jwt jwt(Map<String, Object> claims) {
    Jwt.Builder b =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .issuedAt(Instant.now().minusSeconds(10))
            .expiresAt(Instant.now().plusSeconds(300))
            .subject("user-1");
    claims.forEach(b::claim);
    return b.build();
  }

  @Test
  void acceptsStringAudience() {
    assertThat(
            validator
                .validate(jwt(Map.of("iss", ISSUER, "aud", "tender-api", "azp", "tender-spa")))
                .hasErrors())
        .isFalse();
  }

  @Test
  void acceptsArrayAudience() {
    assertThat(
            validator
                .validate(
                    jwt(
                        Map.of(
                            "iss",
                            ISSUER,
                            "aud",
                            List.of("account", "tender-api"),
                            "azp",
                            "tender-spa")))
                .hasErrors())
        .isFalse();
  }

  @Test
  void refusesOtherAudience() {
    assertThat(
            validator
                .validate(jwt(Map.of("iss", ISSUER, "aud", "account", "azp", "tender-spa")))
                .hasErrors())
        .isTrue();
  }

  @Test
  void refusesOtherIssuer() {
    assertThat(
            validator
                .validate(
                    jwt(
                        Map.of(
                            "iss",
                            "http://evil/realms/x",
                            "aud",
                            "tender-api",
                            "azp",
                            "tender-spa")))
                .hasErrors())
        .isTrue();
  }

  @Test
  void refusesOtherAuthorizedParty() {
    assertThat(
            validator
                .validate(
                    jwt(Map.of("iss", ISSUER, "aud", "tender-api", "azp", "tender-backend-admin")))
                .hasErrors())
        .isTrue();
  }

  @Test
  void refusesExpiredToken() {
    Jwt expired =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .issuedAt(Instant.now().minusSeconds(900))
            .expiresAt(Instant.now().minusSeconds(600))
            .claim("iss", ISSUER)
            .claim("aud", "tender-api")
            .claim("azp", "tender-spa")
            .build();
    assertThat(validator.validate(expired).hasErrors()).isTrue();
  }
}
