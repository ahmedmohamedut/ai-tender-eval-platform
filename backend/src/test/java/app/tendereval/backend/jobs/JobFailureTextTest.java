package app.tendereval.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.api.model.JobFailure;
import java.util.List;
import org.junit.jupiter.api.Test;

/** AI 7.2: an unusable model answer is named as such, not as an outage, and is not retried. */
class JobFailureTextTest {

  @Test
  void anUnusableModelAnswerIsNamedAndSendsTheUserToAnAdministrator() {
    assertThat(JobsService.message("model_output_unusable", null))
        .contains("cut off or unreadable");
    assertThat(JobsService.actions("model_output_unusable"))
        .isEqualTo(List.of(JobFailure.SuggestedActionsEnum.CONTACT_ADMIN));
  }

  @Test
  void anOutageIsStillRetryable() {
    assertThat(JobsService.actions("llm_unavailable"))
        .isEqualTo(List.of(JobFailure.SuggestedActionsEnum.RETRY));
  }
}
