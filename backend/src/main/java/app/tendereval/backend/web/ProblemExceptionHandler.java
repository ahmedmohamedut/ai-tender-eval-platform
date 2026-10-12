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
    if (ex instanceof ApiException api) {
      return problems.write(exchange, api);
    }
    if (ex instanceof org.springframework.security.access.AccessDeniedException) {
      return problems.write(
          exchange, HttpStatus.FORBIDDEN, "forbidden", "This action is not allowed");
    }
    if (ex instanceof org.springframework.web.bind.support.WebExchangeBindException bind) {
      java.util.List<ApiException.FieldError> errors =
          bind.getFieldErrors().stream()
              .map(
                  f ->
                      new ApiException.FieldError(
                          f.getField(), "invalid", String.valueOf(f.getDefaultMessage())))
              .toList();
      return problems.write(
          exchange,
          new ApiException(
              HttpStatus.UNPROCESSABLE_ENTITY,
              "validation_failed",
              "The request has invalid values",
              errors));
    }
    if (ex instanceof org.springframework.web.server.MissingRequestValueException missing
        && "If-Match".equalsIgnoreCase(missing.getName())) {
      return problems.write(
          exchange,
          HttpStatus.PRECONDITION_REQUIRED,
          "precondition_required",
          "If-Match is required");
    }
    if (ex instanceof org.springframework.web.server.ServerWebInputException) {
      return problems.write(
          exchange, HttpStatus.BAD_REQUEST, "bad_request", "The request could not be read");
    }
    ApiException mapped = SqlErrors.map(ex);
    if (mapped != null) {
      return problems.write(exchange, mapped);
    }
    if (ex instanceof ResponseStatusException rse) {
      HttpStatusCode status = rse.getStatusCode();
      return problems.write(exchange, status, codeFor(status), titleFor(status));
    }
    // Type and the first frames only: a message may hold data (rule 10), a frame never does.
    StackTraceElement[] frames = ex.getStackTrace();
    StringBuilder where = new StringBuilder();
    for (int k = 0; k < Math.min(6, frames.length); k++) {
      where.append(frames[k]).append(k < 5 ? " <- " : "");
    }
    log.error(
        "unhandled error trace_id={} type={} at={}",
        TraceFilter.traceId(exchange),
        ex.getClass().getName(),
        where);
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
