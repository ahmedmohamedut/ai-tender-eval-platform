package app.tendereval.backend.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * An error the caller can act on: a status, a stable {@code code} (never reworded), a short title
 * and optional field errors or extra members of the problem body (PI 2.1).
 */
public class ApiException extends RuntimeException {

  public record FieldError(String field, String code, String message) {}

  private final HttpStatus status;
  private final String code;
  private final List<FieldError> errors;
  private final Map<String, Object> extra = new LinkedHashMap<>();
  private final Map<String, String> headers = new LinkedHashMap<>();

  public ApiException(HttpStatus status, String code, String title) {
    this(status, code, title, List.of());
  }

  public ApiException(HttpStatus status, String code, String title, List<FieldError> errors) {
    super(title);
    this.status = status;
    this.code = code;
    this.errors = List.copyOf(errors);
  }

  public static ApiException notFound(String what) {
    return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " was not found");
  }

  public static ApiException forbidden(String code, String title) {
    return new ApiException(HttpStatus.FORBIDDEN, code, title);
  }

  public static ApiException conflict(String code, String title) {
    return new ApiException(HttpStatus.CONFLICT, code, title);
  }

  public static ApiException invalid(String field, String code, String message) {
    return new ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "validation_failed",
        "The request has invalid values",
        List.of(new FieldError(field, code, message)));
  }

  public ApiException with(String key, Object value) {
    extra.put(key, value);
    return this;
  }

  public ApiException header(String name, String value) {
    headers.put(name, value);
    return this;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public List<FieldError> errors() {
    return errors;
  }

  public Map<String, Object> extra() {
    return extra;
  }

  public Map<String, String> headers() {
    return headers;
  }
}
