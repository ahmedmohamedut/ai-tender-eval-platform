package app.tendereval.backend.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/** Maps the {@code roles} claim (a string or an array, PI 3.4) to platform roles. */
public class AppAuthenticationConverter
    implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

  @Override
  public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
    Object claim = jwt.getClaims().get("roles");
    List<String> names = new ArrayList<>();
    if (claim instanceof String s) {
      names.add(s);
    } else if (claim instanceof Collection<?> c) {
      c.forEach(o -> names.add(String.valueOf(o)));
    }
    List<Role> roles =
        names.stream()
            .map(Role::fromRealmRole)
            .flatMap(java.util.Optional::stream)
            .distinct()
            .toList();
    return Mono.just(new AppAuthentication(jwt, roles));
  }
}
