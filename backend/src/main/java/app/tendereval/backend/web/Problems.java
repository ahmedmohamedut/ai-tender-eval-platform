package app.tendereval.backend.web;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * RFC 9457 problem responses with a stable {@code code} and a {@code trace_id} (PI 2.1). The body
 * is written by hand so that no request data, token or stack trace can reach it.
 */
@Component
public class Problems {

  public static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

  public Mono<Void> write(
      ServerWebExchange exchange, HttpStatusCode status, String code, String title) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.empty();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", title);
    body.put("status", status.value());
    body.put("code", code);
    body.put("trace_id", TraceFilter.traceId(exchange));
    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(PROBLEM_JSON);
    exchange.getResponse().getHeaders().setCacheControl("no-store");
    DataBuffer buffer =
        exchange.getResponse().bufferFactory().wrap(toJson(body).getBytes(StandardCharsets.UTF_8));
    return exchange.getResponse().writeWith(Mono.just(buffer));
  }

  public ServerAuthenticationEntryPoint entryPoint() {
    return (exchange, e) ->
        write(exchange, HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required");
  }

  public ServerAccessDeniedHandler accessDeniedHandler() {
    return (exchange, e) ->
        write(exchange, HttpStatus.FORBIDDEN, "forbidden", "This action is not allowed");
  }

  static String toJson(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> e : map.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append('"').append(escape(e.getKey())).append("\":");
      Object v = e.getValue();
      if (v instanceof Number) {
        sb.append(v);
      } else {
        sb.append('"').append(escape(String.valueOf(v))).append('"');
      }
    }
    return sb.append('}').toString();
  }

  private static String escape(String s) {
    StringBuilder out = new StringBuilder();
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }
}
