package app.tendereval.backend.common;

import app.tendereval.backend.web.ApiException;
import java.util.Map;

/**
 * Keyset pagination over one sort field plus the id (PI 2.1: `sort=name,-last_activity_at` names
 * fields, a leading minus is descending). Only the first field is used for ordering; the id breaks
 * ties, so a page boundary is stable. Fields come from a whitelist, never from input.
 */
public final class Keyset {

  public enum Type {
    TEXT,
    TIMESTAMP
  }

  public record Field(String expression, Type type) {}

  public record Order(String name, Field field, boolean desc) {

    public String orderBy(String idColumn) {
      String dir = desc ? "DESC" : "ASC";
      return field.expression() + " " + dir + ", " + idColumn + " " + dir;
    }

    /** SQL fragment for "rows after the cursor"; binds :sort_value and :sort_id. */
    public String after(String idColumn) {
      String op = desc ? "<" : ">";
      String value =
          field.type() == Type.TIMESTAMP ? "CAST(:sort_value AS timestamptz)" : ":sort_value";
      return "(%1$s %2$s %3$s OR (%1$s = %3$s AND %4$s %2$s :sort_id))"
          .formatted(field.expression(), op, value, idColumn);
    }
  }

  private Keyset() {}

  public static Order parse(String sort, Map<String, Field> allowed, String defaultSort) {
    String spec = sort == null || sort.isBlank() ? defaultSort : sort;
    String first = spec.split(",")[0].trim();
    boolean desc = first.startsWith("-");
    String name = desc ? first.substring(1) : first;
    Field field = allowed.get(name);
    if (field == null) {
      throw new ApiException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "invalid_sort",
          "Sort by one of: " + String.join(", ", allowed.keySet()));
    }
    return new Order(name, field, desc);
  }
}
