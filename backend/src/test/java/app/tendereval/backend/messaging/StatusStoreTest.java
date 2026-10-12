package app.tendereval.backend.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StatusStoreTest {

  private final StatusStore store = new StatusStore();

  @Test
  void terminalStateIsNeverReplaced() {
    assertThat(store.record("j", "completed", 1, "s")).isTrue();
    assertThat(store.record("j", "running", 5, "s")).isFalse();
    assertThat(store.get("j").orElseThrow().state()).isEqualTo("completed");
  }

  @Test
  void aLowerSequenceDoesNotReplaceAHigherOne() {
    store.record("j", "running", 3, "b");
    assertThat(store.record("j", "running", 2, "a")).isFalse();
    assertThat(store.get("j").orElseThrow().step()).isEqualTo("b");
  }

  @Test
  void duplicateDeliveryOfATerminalStatusChangesNothing() {
    assertThat(store.record("j", "failed", 1, "s")).isTrue();
    assertThat(store.record("j", "failed", 1, "s")).isFalse();
  }
}
