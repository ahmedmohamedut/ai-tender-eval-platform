package app.tendereval.backend.testsupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Creates platform users (through the owner connection) whose tokens the tests can sign. */
public final class TestUsers {

  public record TestUser(UUID id, String sub, String email, String role) {}

  private TestUsers() {}

  public static TestUser create(String role) {
    return create(role, "active");
  }

  public static TestUser create(String role, String status) {
    String sub = UUID.randomUUID().toString();
    String email = sub + "@example.test";
    try (Connection c = SchemaFixture.owner();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO users (email, display_name, role, status, sso_subject)"
                    + " VALUES (?, ?, ?, ?, ?) RETURNING id")) {
      ps.setString(1, email);
      ps.setString(2, "User " + sub.substring(0, 8));
      ps.setString(3, role);
      ps.setString(4, status);
      ps.setString(5, sub);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return new TestUser(UUID.fromString(rs.getString(1)), sub, email, role);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void sql(String sql, Object... args) {
    try (Connection c = SchemaFixture.owner();
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      ps.execute();
    } catch (SQLException e) {
      throw new IllegalStateException(sql + " -> " + e.getMessage(), e);
    }
  }

  public static Object scalar(String sql, Object... args) {
    try (Connection c = SchemaFixture.owner();
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject(1) : null;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(sql + " -> " + e.getMessage(), e);
    }
  }

  public static String realmRole(String platformRole) {
    return switch (platformRole) {
      case "admin" -> "procurement_admin";
      case "member" -> "committee_member";
      default -> "observer";
    };
  }

  public static List<String> roles(String platformRole) {
    return List.of(realmRole(platformRole));
  }
}
