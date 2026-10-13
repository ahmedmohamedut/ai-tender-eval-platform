package app.tendereval.backend.jobs;

import app.tendereval.backend.config.AppProperties;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.util.function.Function;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * The second database pool, signed in as {@code tender_ai_ingest} (rule 3, plan decision 1). Only
 * the message handlers and the timers use it: it can write passages and update named columns of
 * jobs, steps and document versions, and nothing a member decides. It is not a Spring {@code
 * ConnectionFactory} bean, so the request pool stays the primary one.
 */
@Component
public class IngestDb implements DisposableBean {

  private final ConnectionPool pool;
  private final DatabaseClient client;
  private final TransactionalOperator tx;

  public IngestDb(@Value("${spring.r2dbc.url}") String url, AppProperties props) {
    ConnectionFactoryOptions options =
        ConnectionFactoryOptions.parse(url)
            .mutate()
            .option(ConnectionFactoryOptions.USER, props.dbIngestUsername())
            .option(ConnectionFactoryOptions.PASSWORD, props.dbIngestPassword())
            .build();
    ConnectionFactory factory = ConnectionFactories.get(options);
    this.pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory)
                .initialSize(1)
                .maxSize(Math.max(2, props.dbIngestPoolMaxSize()))
                .build());
    this.client = DatabaseClient.create(pool);
    this.tx = TransactionalOperator.create(new R2dbcTransactionManager(pool));
  }

  /** Runs {@code work} in one transaction; commits when the returned Mono completes. */
  public <T> Mono<T> inTransaction(Function<DatabaseClient, Mono<T>> work) {
    return tx.transactional(Mono.defer(() -> work.apply(client)));
  }

  @Override
  public void destroy() {
    pool.dispose();
  }
}
