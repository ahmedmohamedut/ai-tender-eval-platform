package app.tendereval.backend.events;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.events.UiEventHub.UiEvent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UiEventHubTest {

  private final UiEventHub hub = new UiEventHub(null, null, null);

  @Test
  void aUserSubjectBecomesAnEventOfThatPersonOnly() {
    UUID user = UUID.randomUUID();
    UiEvent e =
        hub.toEvent(
            7,
            "ui.push.user." + user,
            "{\"type\":\"notification.created\"}".getBytes(StandardCharsets.UTF_8));
    assertThat(e.userId()).isEqualTo(user);
    assertThat(e.tenderId()).isNull();
    assertThat(e.type()).isEqualTo("notification.created");
  }

  @Test
  void aTenderSubjectStaysATenderEvent() {
    UUID tender = UUID.randomUUID();
    UiEvent e =
        hub.toEvent(
            8,
            "ui.push.tender." + tender,
            "{\"type\":\"job.updated\"}".getBytes(StandardCharsets.UTF_8));
    assertThat(e.tenderId()).isEqualTo(tender);
    assertThat(e.userId()).isNull();
  }
}
