package app.tendereval.backend.testsupport;

import app.tendereval.backend.security.TokenValidators;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/** Signs test tokens with a throwaway key and builds a decoder with the production validators. */
public final class TestTokens {

  public static final String ISSUER = "http://keycloak.test/realms/tender-eval";

  private final KeyPair keys;

  public TestTokens() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      keys = gen.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public ReactiveJwtDecoder decoder() {
    NimbusReactiveJwtDecoder d =
        NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
    d.setJwtValidator(TokenValidators.create(ISSUER, "tender-api", "tender-spa"));
    return d;
  }

  public String token(String subject, List<String> roles) {
    return token(subject, roles, Map.of());
  }

  public String token(String subject, List<String> roles, Map<String, Object> overrides) {
    try {
      JWTClaimsSet.Builder c =
          new JWTClaimsSet.Builder()
              .issuer(ISSUER)
              .subject(subject)
              .audience("tender-api")
              .claim("azp", "tender-spa")
              .claim("roles", roles)
              .issueTime(new Date())
              .expirationTime(Date.from(Instant.now().plusSeconds(300)));
      overrides.forEach(c::claim);
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), c.build());
      jwt.sign(new RSASSASigner(keys.getPrivate()));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
