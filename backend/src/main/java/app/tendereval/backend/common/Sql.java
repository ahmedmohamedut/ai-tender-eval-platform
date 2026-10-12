package app.tendereval.backend.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;

/** A WHERE clause and its binds, built from whitelisted fragments only (never from input). */
public final class Sql {

  private final StringBuilder where = new StringBuilder();
  private final Map<String, Object> binds = new LinkedHashMap<>();

  public Sql and(String fragment) {
    where.append(where.length() == 0 ? " WHERE " : " AND ").append(fragment);
    return this;
  }

  public Sql and(String fragment, String name, Object value) {
    binds.put(name, value);
    return and(fragment);
  }

  public Sql bind(String name, Object value) {
    binds.put(name, value);
    return this;
  }

  public String where() {
    return where.toString();
  }

  public Map<String, Object> binds() {
    return binds;
  }

  public DatabaseClient.GenericExecuteSpec apply(DatabaseClient.GenericExecuteSpec spec) {
    for (Map.Entry<String, Object> e : binds.entrySet()) {
      spec = spec.bind(e.getKey(), e.getValue());
    }
    return spec;
  }

  /** Makes a user's text safe inside ILIKE, then wraps it in wildcards. */
  public static String contains(String q) {
    String escaped = q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }
}
