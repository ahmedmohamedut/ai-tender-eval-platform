package app.tendereval.backend.messaging;

import io.nats.client.Connection;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("nats")
public class NatsHealthIndicator implements HealthIndicator {

  private final Connection connection;

  public NatsHealthIndicator(Connection connection) {
    this.connection = connection;
  }

  @Override
  public Health health() {
    return connection.getStatus() == Connection.Status.CONNECTED
        ? Health.up().build()
        : Health.down().withDetail("status", connection.getStatus().name()).build();
  }
}
