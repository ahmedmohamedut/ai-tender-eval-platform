package app.tendereval.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AppAuthenticationConverterTest {

  private final AppAuthenticationConverter converter = new AppAuthenticationConverter();

  private AppAuthentication convert(Object roles) {
    Jwt.Builder b =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("11111111-1111-4111-8111-111111111111")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60));
    if (roles != null) {
      b.claim("roles", roles);
    }
    return (AppAuthentication) converter.convert(b.build()).block();
  }

  @Test
  void mapsRealmRolesToPlatformRoles() {
    assertThat(convert(List.of("procurement_admin")).singleRole()).contains(Role.ADMIN);
    assertThat(convert(List.of("committee_member")).singleRole()).contains(Role.MEMBER);
    assertThat(convert("observer").singleRole()).contains(Role.OBSERVER);
  }

  @Test
  void noKnownRoleMeansNoSingleRole() {
    assertThat(convert(null).singleRole()).isEmpty();
    assertThat(convert(List.of("offline_access", "uma_authorization")).singleRole()).isEmpty();
  }

  @Test
  void severalRolesMeanNoSingleRole() {
    assertThat(convert(List.of("procurement_admin", "observer")).singleRole()).isEmpty();
  }

  @Test
  void aChairClaimIsNeverARole() {
    // The Chair is read from tender_members, never from the token (rule 2).
    assertThat(convert(List.of("chair")).singleRole()).isEmpty();
    assertThat(Role.fromRealmRole("chair")).isEmpty();
  }

  @Test
  void userIdIsTheSubject() {
    assertThat(convert("observer").userId()).isEqualTo("11111111-1111-4111-8111-111111111111");
  }
}
