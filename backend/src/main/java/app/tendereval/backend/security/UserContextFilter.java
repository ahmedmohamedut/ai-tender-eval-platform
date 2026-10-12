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
  private final OperationRoles roles;

  public UserContextFilter(UserDirectory directory, OperationRoles roles) {
    this.directory = directory;
    this.roles = roles;
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
            user -> {
              if (user.isEmpty()) {
                return chain.filter(exchange);
              }
              // The operation's x-roles: a role that may never call it is refused here, before
              // any work (the database policies decide again).
              String path = exchange.getRequest().getPath().pathWithinApplication().value();
              var allowed = roles.allowed(exchange.getRequest().getMethod(), path);
              if (allowed.isPresent() && !allowed.get().contains(user.get().role())) {
                return Mono.error(
                    app.tendereval.backend.web.ApiException.forbidden(
                        "forbidden", "This action is not allowed for your role"));
              }
              return chain
                  .filter(exchange)
                  .contextWrite(ctx -> ctx.put(CurrentUser.class, user.get()));
            });
  }
}
