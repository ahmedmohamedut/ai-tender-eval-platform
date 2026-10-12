package app.tendereval.backend.security;

import app.tendereval.backend.config.AppProperties;
import app.tendereval.backend.web.Problems;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

  /**
   * Keys are fetched from the internal address; the issuer in the token is the public one. Both are
   * configuration, because inside the compose network Keycloak is not "localhost".
   */
  @Bean
  ReactiveJwtDecoder jwtDecoder(AppProperties props) {
    NimbusReactiveJwtDecoder decoder =
        NimbusReactiveJwtDecoder.withJwkSetUri(props.keycloakJwkSetUri()).build();
    decoder.setJwtValidator(
        TokenValidators.create(
            props.keycloakIssuerUri(), props.keycloakAudience(), props.spaClientId()));
    return decoder;
  }

  /**
   * Health probes on the management port. That port is not published to the gateway and serves only
   * the health endpoint; on the main port {@code /actuator} does not exist.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  SecurityWebFilterChain actuatorSecurityWebFilterChain(ServerHttpSecurity http) {
    return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/actuator/**"))
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(ex -> ex.anyExchange().permitAll())
        .build();
  }

  @Bean
  @Order(Ordered.LOWEST_PRECEDENCE)
  SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, Problems problems) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .cors(c -> {})
        .authorizeExchange(
            ex ->
                ex.anyExchange()
                    // Authenticated, and exactly one of the three roles, else 403 (PI 3.2).
                    .access(
                        (auth, ctx) ->
                            auth.map(
                                a ->
                                    new AuthorizationDecision(
                                        a instanceof AppAuthentication app
                                            && a.isAuthenticated()
                                            && app.singleRole().isPresent()))))
        .oauth2ResourceServer(
            rs ->
                rs.jwt(j -> j.jwtAuthenticationConverter(new AppAuthenticationConverter()))
                    .authenticationEntryPoint(problems.entryPoint()))
        .exceptionHandling(
            eh ->
                eh.authenticationEntryPoint(problems.entryPoint())
                    .accessDeniedHandler(problems.accessDeniedHandler()))
        .build();
  }

  /** Same origin through the gateway; the SPA origin is the only one allowed (PI 7.3). */
  @Bean
  CorsConfigurationSource corsConfigurationSource(AppProperties props) {
    CorsConfiguration cfg = new CorsConfiguration();
    cfg.setAllowedOrigins(List.of(props.publicBaseUrl()));
    cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    cfg.setAllowedHeaders(
        List.of("Authorization", "Content-Type", "If-Match", "Idempotency-Key", "Last-Event-ID"));
    cfg.setExposedHeaders(List.of("ETag", "Location"));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cfg);
    return source;
  }
}
