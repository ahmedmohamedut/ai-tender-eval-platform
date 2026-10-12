package app.tendereval.backend.common;

import app.tendereval.backend.web.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;

/** The justification rule of UX-GEN-04: at least {@code min_note_length} characters, trimmed. */
public final class Notes {

  private Notes() {}

  public static String require(String note, int minLength, String field) {
    String trimmed = note == null ? "" : note.trim();
    if (trimmed.codePointCount(0, trimmed.length()) < minLength) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "note_too_short",
          "Add a reason of at least " + minLength + " characters",
          List.of(
              new ApiException.FieldError(
                  field, "note_too_short", "At least " + minLength + " characters")));
    }
    return trimmed;
  }
}
