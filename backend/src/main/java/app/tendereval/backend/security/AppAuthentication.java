package app.tendereval.backend.security;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/** A validated access token with its platform role, when it carries exactly one. */
public class AppAuthentication extends AbstractOAuth2TokenAuthenticationToken<Jwt> {

  private final transient List<Role> roles;

  public AppAuthentication(Jwt jwt, List<Role> roles) {
    super(jwt, jwt.getSubject(), jwt, authorities(roles));
    this.roles = List.copyOf(roles);
    setAuthenticated(true);
  }

  private static Collection<GrantedAuthority> authorities(List<Role> roles) {
    return roles.stream()
        .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r.platformName()))
        .toList();
  }

  /** The one role of the token; empty when the token has none or several (refused with 403). */
  public Optional<Role> singleRole() {
    return roles.size() == 1 ? Optional.of(roles.get(0)) : Optional.empty();
  }

  public String userId() {
    return getToken().getSubject();
  }

  @Override
  public java.util.Map<String, Object> getTokenAttributes() {
    return getToken().getClaims();
  }
}
