package app.tendereval.backend.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Tiny JDBC helpers so schema tests read as SQL. */
final class Sql {

  private Sql() {}

  static void exec(Connection c, String sql, Object... args) {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, args);
      ps.execute();
    } catch (SQLException e) {
      throw new IllegalStateException(sql + " -> " + e.getMessage(), e);
    }
  }

  /** Returns the SQLSTATE when the statement fails, or null when it succeeds. */
  static String failure(Connection c, String sql, Object... args) {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, args);
      ps.execute();
      return null;
    } catch (SQLException e) {
      return e.getSQLState();
    }
  }

  static List<List<Object>> rows(Connection c, String sql, Object... args) {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, args);
      List<List<Object>> out = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        int n = rs.getMetaData().getColumnCount();
        while (rs.next()) {
          List<Object> row = new ArrayList<>(n);
          for (int i = 1; i <= n; i++) {
            row.add(rs.getObject(i));
          }
          out.add(row);
        }
      }
      return out;
    } catch (SQLException e) {
      throw new IllegalStateException(sql + " -> " + e.getMessage(), e);
    }
  }

  static Object one(Connection c, String sql, Object... args) {
    List<List<Object>> r = rows(c, sql, args);
    return r.isEmpty() ? null : r.get(0).get(0);
  }

  private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      ps.setObject(i + 1, args[i]);
    }
  }
}
