package app.tendereval.backend.api;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PI 2.1, UX-JOB-08: a repeat of a start with the same key returns the first answer. */
class IdempotencyIT extends AbstractApiIT {

  @Test
  void aRepeatWithTheSameKeyDoesTheWorkOnce() {
    TestUser user = TestUsers.create("member");
    String key = "key-" + UUID.randomUUID();
    var first =
        post("/_test/idem?value=a", user)
            .header("Idempotency-Key", key)
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody()
            .jsonPath("$.value")
            .isEqualTo("a")
            .returnResult();
    String body1 = new String(first.getResponseBody());
    var second =
        post("/_test/idem?value=a", user)
            .header("Idempotency-Key", key)
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectHeader()
            .valueEquals("Idempotent-Replay", "true")
            .expectBody(String.class)
            .returnResult();
    org.assertj.core.api.Assertions.assertThat(second.getResponseBody()).isEqualTo(body1);
  }

  @Test
  void theSameKeyForADifferentRequestIsRefused() {
    TestUser user = TestUsers.create("member");
    String key = "key-" + UUID.randomUUID();
    post("/_test/idem?value=a", user)
        .header("Idempotency-Key", key)
        .exchange()
        .expectStatus()
        .isAccepted();
    post("/_test/idem?value=b", user)
        .header("Idempotency-Key", key)
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("idempotency_key_reused");
  }

  @Test
  void keysAreScopedToTheUser() {
    TestUser one = TestUsers.create("member");
    TestUser two = TestUsers.create("member");
    String key = "key-" + UUID.randomUUID();
    post("/_test/idem?value=a", one)
        .header("Idempotency-Key", key)
        .exchange()
        .expectStatus()
        .isAccepted();
    post("/_test/idem?value=b", two)
        .header("Idempotency-Key", key)
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .doesNotExist("Idempotent-Replay");
  }

  @Test
  void aMissingKeyIsABadRequest() {
    TestUser user = TestUsers.create("member");
    post("/_test/idem?value=a", user)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("idempotency_key_required");
  }
}
