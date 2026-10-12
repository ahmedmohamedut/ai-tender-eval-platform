package app.tendereval.backend.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The application against the shared, migrated test database. */
public abstract class AbstractApiIT extends AbstractApiBase {

  @DynamicPropertySource
  static void sharedDatabase(DynamicPropertyRegistry r) {
    register(r, SchemaFixture.jdbcUrl());
  }
}
