package app.tendereval.backend.testsupport;

import java.io.IOException;
import java.nio.file.Path;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * A PostgreSQL 18 container provisioned by the very script `make up` uses
 * (infra/postgres/provision.sh), so the roles under test are the real ones.
 */
public final class PostgresFixture {

  public static final String DB = "tender_eval";
  public static final String OWNER_PASSWORD = "owner-test";
  public static final String APP_PASSWORD = "app-test";
  public static final String INGEST_PASSWORD = "ingest-test";

  private PostgresFixture() {}

  public static PostgreSQLContainer start() {
    PostgreSQLContainer pg =
        new PostgreSQLContainer("postgres:18.6")
            .withUsername("postgres")
            .withPassword("postgres-test")
            .withDatabaseName("postgres");
    pg.withCopyFileToContainer(
        MountableFile.forHostPath(Path.of("..", "infra", "postgres", "provision.sh")),
        "/provision.sh");
    pg.start();
    provision(pg);
    return pg;
  }

  /** Runs the provisioning script twice: it must be safe to repeat. */
  private static void provision(PostgreSQLContainer pg) {
    provision(pg, DB);
  }

  /** Creates and provisions a further database on the same server (for isolated scenarios). */
  public static void provision(PostgreSQLContainer pg, String database) {
    for (int i = 0; i < 2; i++) {
      try {
        ExecResult r =
            pg.execInContainer(
                "env",
                "PGUSER=postgres",
                "POSTGRES_DB=" + database,
                "TENDER_OWNER_PASSWORD=" + OWNER_PASSWORD,
                "TENDER_APP_PASSWORD=" + APP_PASSWORD,
                "TENDER_AI_INGEST_PASSWORD=" + INGEST_PASSWORD,
                "KEYCLOAK_DB_PASSWORD=kc-test",
                "sh",
                "/provision.sh");
        if (r.getExitCode() != 0) {
          throw new IllegalStateException("provisioning failed: " + r.getStderr());
        }
      } catch (IOException | InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
