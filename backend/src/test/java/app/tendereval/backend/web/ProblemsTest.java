package app.tendereval.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class ProblemsTest {

  private final Problems problems = new Problems();

  @Test
  void writesProblemJsonWithStableCodeAndTraceId() {
    MockServerWebExchange ex =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/x")
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
    ex.getAttributes().put(TraceFilter.ATTRIBUTE, "4bf92f3577b34da6a3ce929d0e0e4736");

    problems.write(ex, HttpStatus.FORBIDDEN, "forbidden", "Not \"allowed\"").block();

    assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(ex.getResponse().getHeaders().getContentType()).isEqualTo(Problems.PROBLEM_JSON);
    String body = ex.getResponse().getBodyAsString().block();
    assertThat(body)
        .contains("\"code\":\"forbidden\"")
        .contains("\"trace_id\":\"4bf92f3577b34da6a3ce929d0e0e4736\"")
        .contains("\"status\":403")
        .contains("\"title\":\"Not \\\"allowed\\\"\"");
  }

  @Test
  void bodyNeverEchoesRequestData() {
    MockServerWebExchange ex =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/secret-name?q=Vendor%20Ltd")
                .header("Authorization", "Bearer abc.def.ghi"));
    problems
        .write(ex, HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required")
        .block();
    String body = ex.getResponse().getBodyAsString().block();
    assertThat(body)
        .doesNotContain("secret-name")
        .doesNotContain("Vendor")
        .doesNotContain("abc.def");
  }
}
