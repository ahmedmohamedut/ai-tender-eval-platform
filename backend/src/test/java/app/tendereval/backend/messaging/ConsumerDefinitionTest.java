package app.tendereval.backend.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The code's consumer settings must equal the provisioning file (PI 5.3). */
class ConsumerDefinitionTest {

  @Test
  void backendStatusMatchesInfraDefinition() throws Exception {
    Path file = Path.of("..", "infra", "nats", "consumers", "ANALYSIS_EVENTS.backend-status.json");
    JsonNode def = JsonMapper.builder().build().readTree(Files.readString(file));
    var config = StatusConsumer.configuration();

    assertThat(config.getDurable()).isEqualTo(def.get("durable_name").asString());
    assertThat(config.getFilterSubject()).isEqualTo(def.get("filter_subject").asString());
    assertThat(config.getAckWait().toNanos()).isEqualTo(def.get("ack_wait").asLong());
    assertThat(config.getMaxAckPending()).isEqualTo(def.get("max_ack_pending").asLong());
    assertThat(config.getMaxDeliver()).isEqualTo(def.get("max_deliver").asLong());
    assertThat(config.getAckPolicy().name().toLowerCase())
        .isEqualTo(def.get("ack_policy").asString());
  }
}
