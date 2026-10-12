package app.tendereval.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TraceIdsTest {

  @Test
  void takesTraceIdFromValidTraceparent() {
    assertThat(TraceIds.traceIdOf("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
        .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
  }

  @Test
  void generatesAnIdWhenMissingOrInvalid() {
    assertThat(TraceIds.traceIdOf(null)).hasSize(32).matches("[0-9a-f]{32}");
    assertThat(TraceIds.traceIdOf("garbage")).hasSize(32);
    assertThat(TraceIds.traceIdOf("00-" + "0".repeat(32) + "-00f067aa0ba902b7-01"))
        .isNotEqualTo("0".repeat(32));
  }

  @Test
  void newTraceparentKeepsTheTraceId() {
    String id = "4bf92f3577b34da6a3ce929d0e0e4736";
    assertThat(TraceIds.newTraceparent(id))
        .startsWith("00-" + id + "-")
        .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
  }
}
