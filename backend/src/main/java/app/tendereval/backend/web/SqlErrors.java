package app.tendereval.backend.web;

import io.r2dbc.spi.R2dbcException;
import org.springframework.http.HttpStatus;

/**
 * Maps database errors to stable problem codes without echoing any database text. The services
 * check their rules first with messages a person can act on; this is the net underneath, so a rule
 * that only the database enforces still produces a clean answer.
 */
final class SqlErrors {

  private SqlErrors() {}

  static ApiException map(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof R2dbcException r) {
        String state = r.getSqlState();
        if (state == null) {
          return null;
        }
        return switch (state) {
          case "23505" ->
              ApiException.conflict("already_exists", "An item with these details already exists");
          case "23503" ->
              new ApiException(
                  HttpStatus.UNPROCESSABLE_ENTITY,
                  "invalid_reference",
                  "The request refers to something that does not exist");
          case "23514", "23502", "23000" ->
              new ApiException(
                  HttpStatus.UNPROCESSABLE_ENTITY,
                  "constraint_violation",
                  "The request breaks a rule of the data");
          case "42501" -> ApiException.forbidden("forbidden", "This action is not allowed");
          default -> null;
        };
      }
    }
    return null;
  }
}
