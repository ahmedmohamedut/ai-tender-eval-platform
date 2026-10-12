package app.tendereval.backend.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Assigns every request a trace id, taken from {@code traceparent} when it is valid. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter implements WebFilter {

  public static final String ATTRIBUTE = "app.trace_id";
  public static final String CONTEXT_KEY = "app.trace_id";

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String id = TraceIds.traceIdOf(exchange.getRequest().getHeaders().getFirst("traceparent"));
    exchange.getAttributes().put(ATTRIBUTE, id);
    exchange.getResponse().getHeaders().set("X-Trace-Id", id);
    return chain.filter(exchange).contextWrite(ctx -> ctx.put(CONTEXT_KEY, id));
  }

  public static String traceId(ServerWebExchange exchange) {
    Object id = exchange.getAttribute(ATTRIBUTE);
    return id == null ? TraceIds.traceIdOf(null) : id.toString();
  }
}
