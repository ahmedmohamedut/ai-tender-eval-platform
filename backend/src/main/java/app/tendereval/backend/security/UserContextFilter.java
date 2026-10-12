package app.tendereval.backend.security;

import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Runs after authentication and authorization. Resolves the token's subject to a platform user and
 * puts it in the Reactor context, where {@code RequestTransaction} reads it to set {@code
 * app.user_id} and {@code app.role} (rules 1 and 2).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class UserContextFilter implements WebFilter {

  private final UserDirectory directory;

  public UserContextFilter(UserDirectory directory) {
    this.directory = directory;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    return ReactiveSecurityContextHolder.getContext()
        .map(c -> c.getAuthentication())
        .filter(a -> a instanceof AppAuthentication)
        .cast(AppAuthentication.class)
        .flatMap(
            a ->
                directory.resolve(
                    a.userId(),
                    a.getToken().getClaimAsString("email"),
                    a.getToken().getClaimAsString("name")))
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .flatMap(
            user ->
                user.isPresent()
                    ? chain
                        .filter(exchange)
                        .contextWrite(ctx -> ctx.put(CurrentUser.class, user.get()))
                    : chain.filter(exchange));
  }
}
