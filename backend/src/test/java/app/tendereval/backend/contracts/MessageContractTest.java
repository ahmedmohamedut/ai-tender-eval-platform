package app.tendereval.backend.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The backend is tested against the JSON Schemas exported from the Python message models, so Java
 * and Python cannot drift (implementation guide 4.2). The same sample files are checked by the
 * Python tests against the Pydantic models.
 */
class MessageContractTest {

  private static final Path MESSAGES = Path.of("..", "contracts", "messages");
  // "format" (uuid, date-time) is an annotation in draft 2020-12 unless assertions are enabled.
  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder ->
              builder.schemaRegistryConfig(
                  SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));

  private static Schema schemaFor(Path sample) throws IOException {
    String name = sample.getFileName().toString().split("\\.")[0];
    Path schemaFile = MESSAGES.resolve(name + ".schema.json");
    assertThat(schemaFile).as("schema for sample %s", sample.getFileName()).exists();
    return REGISTRY.getSchema(Files.readString(schemaFile));
  }

  private static List<Path> samples(String kind) throws IOException {
    try (Stream<Path> s = Files.list(MESSAGES.resolve("samples").resolve(kind))) {
      return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
    }
  }

  @Test
  void everyValidSampleIsAcceptedBySchema() throws IOException {
    List<Path> valid = samples("valid");
    assertThat(valid).hasSizeGreaterThanOrEqualTo(8);
    for (Path sample : valid) {
      var errors = schemaFor(sample).validate(Files.readString(sample), InputFormat.JSON);
      assertThat(errors).as("errors for %s", sample.getFileName()).isEmpty();
    }
  }

  @Test
  void everyInvalidSampleIsRefusedBySchema() throws IOException {
    List<Path> invalid = samples("invalid");
    assertThat(invalid).hasSizeGreaterThanOrEqualTo(8);
    for (Path sample : invalid) {
      var errors = schemaFor(sample).validate(Files.readString(sample), InputFormat.JSON);
      assertThat(errors).as("%s must be refused", sample.getFileName()).isNotEmpty();
    }
  }

  @Test
  void headerValuesNameTheMajorVersion() throws IOException {
    for (String name : List.of("command", "status", "result_batch", "push_event")) {
      String text = Files.readString(MESSAGES.resolve(name + ".schema.json"));
      assertThat(text).containsPattern("\"x-tender-schema\": \"tender\\.[a-z.]+/1\"");
    }
  }
}
