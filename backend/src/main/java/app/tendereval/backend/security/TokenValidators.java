package app.tendereval.backend.security;

import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/** Issuer, expiry, audience {@code tender-api} (string or array) and {@code azp} (PI 3.4). */
public final class TokenValidators {

  private TokenValidators() {}

  public static OAuth2TokenValidator<Jwt> create(
      String issuer, String audience, String authorizedParty) {
    OAuth2TokenValidator<Jwt> audienceValidator =
        jwt -> {
          List<String> aud = jwt.getAudience();
          return aud != null && aud.contains(audience)
              ? OAuth2TokenValidatorResult.success()
              : OAuth2TokenValidatorResult.failure(
                  new OAuth2Error("invalid_token", "audience is not " + audience, null));
        };
    OAuth2TokenValidator<Jwt> azpValidator =
        jwt ->
            authorizedParty.equals(jwt.getClaimAsString("azp"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "token was not issued to the SPA", null));
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, azpValidator);
  }
}
