package app.tendereval.backend.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/** Turns every unhandled error into problem+json. Messages of unexpected errors are never sent. */
@Component
@Order(-2)
public class ProblemExceptionHandler implements WebExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

  private final Problems problems;

  public ProblemExceptionHandler(Problems problems) {
    this.problems = problems;
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
    if (ex instanceof ResponseStatusException rse) {
      HttpStatusCode status = rse.getStatusCode();
      return problems.write(exchange, status, codeFor(status), titleFor(status));
    }
    log.error(
        "unhandled error trace_id={} type={}",
        TraceFilter.traceId(exchange),
        ex.getClass().getName());
    return problems.write(
        exchange, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Something went wrong");
  }

  private static String codeFor(HttpStatusCode status) {
    HttpStatus s = HttpStatus.resolve(status.value());
    return s == null ? "error" : s.name().toLowerCase();
  }

  private static String titleFor(HttpStatusCode status) {
    HttpStatus s = HttpStatus.resolve(status.value());
    return s == null ? "Error" : s.getReasonPhrase();
  }
}
