package app.tendereval.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** PI 5.5: the sentence for a failure cause is written here, from a closed cause and numbers. */
class FailureDetailTest {

  @Test
  void aDimensionMismatchNamesBothSizesAndTheSettingsToChange() {
    assertThat(
            FailureDetail.render(
                "embedding_dimension_mismatch", Map.of("returned", 1024, "configured", 2560)))
        .isEqualTo(
            "The embedding model returned vectors of 1024 values but 2560 are configured."
                + " Set EMBEDDING_DIMENSIONS to 1024 and use a new QDRANT_COLLECTION.");
  }

  @Test
  void anHttpErrorNamesTheStatus() {
    assertThat(FailureDetail.render("embedding_http_error", Map.of("status", 503)))
        .isEqualTo("The embedding service answered with HTTP 503.");
  }

  @Test
  void aCauseWithoutItsNumbersAnUnknownCauseAndNoCauseGiveNoText() {
    assertThat(FailureDetail.render("embedding_dimension_mismatch", Map.of("returned", 1)))
        .isNull();
    assertThat(FailureDetail.render("anything the worker invents", Map.of())).isNull();
    assertThat(FailureDetail.render(null, null)).isNull();
  }
}
