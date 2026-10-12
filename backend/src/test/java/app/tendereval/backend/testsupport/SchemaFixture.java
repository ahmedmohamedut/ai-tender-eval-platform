package app.tendereval.backend.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL 18 container per test JVM, provisioned by the real script and migrated by the real
 * migrations. Tests connect as the owner (to arrange data), the application role or the AI-ingest
 * role, exactly as the running system does.
 */
public final class SchemaFixture {

  private static PostgreSQLContainer container;
  private static String jdbcUrl;

  private SchemaFixture() {}

  public static synchronized void ensureStarted() {
    if (container != null) {
      return;
    }
    container = PostgresFixture.start();
    jdbcUrl =
        "jdbc:postgresql://%s:%d/%s"
            .formatted(container.getHost(), container.getMappedPort(5432), PostgresFixture.DB);
    Flyway.configure()
        .dataSource(jdbcUrl, "tender_owner", PostgresFixture.OWNER_PASSWORD)
        .locations("classpath:db/migration", "classpath:db/test-migration")
        .load()
        .migrate();
  }

  /** A fresh, provisioned and migrated database on the shared server. Returns its JDBC URL. */
  public static synchronized String createDatabase(String name) {
    ensureStarted();
    PostgresFixture.provision(container, name);
    String url =
        "jdbc:postgresql://%s:%d/%s"
            .formatted(container.getHost(), container.getMappedPort(5432), name);
    Flyway.configure()
        .dataSource(url, "tender_owner", PostgresFixture.OWNER_PASSWORD)
        .locations("classpath:db/migration", "classpath:db/test-migration")
        .load()
        .migrate();
    return url;
  }

  public static String jdbcUrl() {
    ensureStarted();
    return jdbcUrl;
  }

  public static Connection owner() {
    return open("tender_owner", PostgresFixture.OWNER_PASSWORD);
  }

  public static Connection app() {
    return open("tender_app", PostgresFixture.APP_PASSWORD);
  }

  public static Connection ingest() {
    return open("tender_ai_ingest", PostgresFixture.INGEST_PASSWORD);
  }

  private static Connection open(String user, String password) {
    ensureStarted();
    try {
      Connection c = DriverManager.getConnection(jdbcUrl, user, password);
      c.setAutoCommit(true);
      return c;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
