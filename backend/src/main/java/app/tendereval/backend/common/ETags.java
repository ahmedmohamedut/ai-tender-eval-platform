package app.tendereval.backend.common;

import app.tendereval.backend.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Optimistic concurrency (PI 2.1): the ETag of a resource is its {@code row_version}, quoted. PATCH
 * and PUT need {@code If-Match}: missing is 428, unreadable is 400, and a stale value is 412 raised
 * by the service together with the current row.
 */
public final class ETags {

  private ETags() {}

  public static String of(int rowVersion) {
    return "\"" + rowVersion + "\"";
  }

  public static int requireIfMatch(ServerHttpRequest request) {
    String header = request.getHeaders().getFirst("If-Match");
    if (header == null || header.isBlank()) {
      throw new ApiException(
          HttpStatus.PRECONDITION_REQUIRED, "precondition_required", "If-Match is required");
    }
    String v = header.trim();
    if (v.startsWith("W/")) {
      v = v.substring(2);
    }
    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
      v = v.substring(1, v.length() - 1);
    }
    try {
      int version = Integer.parseInt(v);
      if (version < 1) {
        throw new NumberFormatException();
      }
      return version;
    } catch (NumberFormatException e) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "invalid_if_match", "If-Match is not a version");
    }
  }

  public static ApiException stale() {
    return new ApiException(
        HttpStatus.PRECONDITION_FAILED,
        "precondition_failed",
        "Someone else changed this item. Reload it and try again");
  }
}
