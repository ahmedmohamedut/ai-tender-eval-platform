package app.tendereval.backend.common;

import app.tendereval.backend.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Opaque list cursor (PI 2.1): the sort value of the last row and its id, base64url encoded.
 * Clients never build one; a cursor that does not decode is a 400.
 */
public record Cursor(String sortValue, UUID id) {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 500;

  public String encode() {
    String raw = sortValue.replace("\\", "\\\\").replace("|", "\\|") + "|" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static Cursor decode(String token) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
      int sep = raw.lastIndexOf('|');
      if (sep < 0) {
        throw new IllegalArgumentException();
      }
      String sort = raw.substring(0, sep).replace("\\|", "|").replace("\\\\", "\\");
      return new Cursor(sort, UUID.fromString(raw.substring(sep + 1)));
    } catch (IllegalArgumentException e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor", "The cursor is not valid");
    }
  }

  public static Cursor decodeOrNull(String token) {
    return token == null || token.isBlank() ? null : decode(token);
  }

  /** Applies the default and the ceiling of PI 2.1. */
  public static int limit(Integer requested) {
    if (requested == null) {
      return DEFAULT_LIMIT;
    }
    if (requested < 1) {
      throw ApiException.invalid("limit", "min", "The limit must be at least 1");
    }
    return Math.min(requested, MAX_LIMIT);
  }
}
