package app.tendereval.backend.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.tendereval.backend.config.AppProperties;
import app.tendereval.backend.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class CommonUnitTest {

  // ---- cursor ---------------------------------------------------------------------------------

  @Test
  void aCursorRoundTripsAndKeepsSeparatorsInTheSortValue() {
    UUID id = UUID.randomUUID();
    Cursor c = new Cursor("Tender | A \\ B", id);
    Cursor back = Cursor.decode(c.encode());
    assertThat(back).isEqualTo(c);
    assertThat(c.encode()).matches("[A-Za-z0-9_-]+");
  }

  @Test
  void anInvalidCursorIsABadRequest() {
    assertThatThrownBy(() -> Cursor.decode("not base64 !!"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_cursor"));
    assertThatThrownBy(
            () -> Cursor.decode(java.util.Base64.getUrlEncoder().encodeToString("x|y".getBytes())))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void limitsDefaultAndAreCapped() {
    assertThat(Cursor.limit(null)).isEqualTo(50);
    assertThat(Cursor.limit(10)).isEqualTo(10);
    assertThat(Cursor.limit(100000)).isEqualTo(500);
    assertThatThrownBy(() -> Cursor.limit(0)).isInstanceOf(ApiException.class);
  }

  // ---- etag ---------------------------------------------------------------------------------

  @Test
  void ifMatchIsParsedFromAQuotedVersion() {
    assertThat(
            ETags.requireIfMatch(
                MockServerHttpRequest.patch("/x").header("If-Match", "\"7\"").build()))
        .isEqualTo(7);
    assertThat(
            ETags.requireIfMatch(
                MockServerHttpRequest.patch("/x").header("If-Match", "W/\"3\"").build()))
        .isEqualTo(3);
    assertThat(ETags.of(12)).isEqualTo("\"12\"");
  }

  @Test
  void missingIfMatchIs428AndGarbageIs400() {
    assertThatThrownBy(() -> ETags.requireIfMatch(MockServerHttpRequest.patch("/x").build()))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
              assertThat(e.code()).isEqualTo("precondition_required");
            });
    assertThatThrownBy(
            () ->
                ETags.requireIfMatch(
                    MockServerHttpRequest.patch("/x").header("If-Match", "abc").build()))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    assertThat(ETags.stale().status()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
  }

  // ---- notes ----------------------------------------------------------------------------------

  @Test
  void aNoteMustReachTheMinimumLengthAfterTrimming() {
    assertThat(Notes.require("  a proper reason  ", 10, "note")).isEqualTo("a proper reason");
    assertThatThrownBy(() -> Notes.require("too short   ", 10, "note"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(e.code()).isEqualTo("note_too_short");
              assertThat(e.errors().get(0).field()).isEqualTo("note");
            });
    assertThatThrownBy(() -> Notes.require(null, 10, "note")).isInstanceOf(ApiException.class);
  }

  // ---- idempotency key ----------------------------------------------------------------------

  @Test
  void anIdempotencyKeyIsRequiredAndBounded() {
    assertThat(Idempotency.requireKey("  0123456789  ")).isEqualTo("0123456789");
    assertThatThrownBy(() -> Idempotency.requireKey(null))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("idempotency_key_required"));
    assertThatThrownBy(() -> Idempotency.requireKey("short")).isInstanceOf(ApiException.class);
    assertThat(Idempotency.hash("a", "b")).isNotEqualTo(Idempotency.hash("ab", ""));
  }

  // ---- rate limit ---------------------------------------------------------------------------

  private static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-12T10:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static AppProperties props(int uploadsPerMinute) {
    return new AppProperties(
        "http://x",
        "http://i",
        "http://j",
        "tender-api",
        "tender-spa",
        "nats://n",
        false,
        "",
        30,
        1800,
        uploadsPerMinute,
        120,
        20,
        10,
        "http://kc/admin/realms/r",
        "c",
        "s",
        259200);
  }

  @Test
  void aBucketRefusesWithRetryAfterAndRefills() {
    MutableClock clock = new MutableClock();
    RateLimiter limiter = new RateLimiter(props(3), clock);
    UUID user = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      limiter.check(user, RateLimiter.Bucket.UPLOADS);
    }
    assertThatThrownBy(() -> limiter.check(user, RateLimiter.Bucket.UPLOADS))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
              assertThat(e.headers()).containsKey("Retry-After");
              assertThat(Integer.parseInt(e.headers().get("Retry-After"))).isBetween(1, 60);
            });
    // another user is unaffected
    limiter.check(UUID.randomUUID(), RateLimiter.Bucket.UPLOADS);
    clock.now = clock.now.plus(Duration.ofSeconds(21)); // 3 per minute: one token per 20 s
    limiter.check(user, RateLimiter.Bucket.UPLOADS);
  }

  @Test
  void aZeroLimitMeansUnlimited() {
    RateLimiter limiter = new RateLimiter(props(0), new MutableClock());
    UUID user = UUID.randomUUID();
    for (int i = 0; i < 100; i++) {
      limiter.check(user, RateLimiter.Bucket.UPLOADS);
    }
  }
}
