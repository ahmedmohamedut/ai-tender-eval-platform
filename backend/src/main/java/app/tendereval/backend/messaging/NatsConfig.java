package app.tendereval.backend.messaging;

import app.tendereval.backend.config.AppProperties;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NatsConfig {

  @Bean(destroyMethod = "close")
  Connection natsConnection(AppProperties props) throws IOException, InterruptedException {
    Options options =
        new Options.Builder()
            .server(props.natsUrl())
            .connectionName("tender-backend")
            .connectionTimeout(Duration.ofSeconds(10))
            .maxReconnects(-1)
            .build();
    return Nats.connect(options);
  }

  @Bean
  JetStream jetStream(Connection connection) throws IOException {
    return connection.jetStream();
  }

  @Bean
  JetStreamManagement jetStreamManagement(Connection connection) throws IOException {
    return connection.jetStreamManagement();
  }
}
