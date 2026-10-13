package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import app.tendereval.backend.events.UiEventHub;
import app.tendereval.backend.events.UiEventHub.UiEvent;
import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** PI 5.10, UX-GEN-06: the push stream, its filtering, its replay and its resync. */
class EventsIT extends AbstractApiIT {

  @MockitoBean UiEventHub hub;

  private Sinks.Many<UiEvent> live;

  @BeforeEach
  void hubs() throws Exception {
    live = Sinks.many().multicast().directBestEffort();
    when(hub.live()).thenReturn(live.asFlux());
    when(hub.lastSequence()).thenReturn(10L);
    when(hub.replayAfter(anyLong(), anyLong())).thenReturn(List.of());
  }

  private Flux<ServerSentEvent<String>> stream(TestUser user, String lastId, String query) {
    var req =
        web.get()
            .uri("/events" + query)
            .header("Authorization", "Bearer " + tokenFor(user))
            .accept(MediaType.TEXT_EVENT_STREAM);
    if (lastId != null) {
      req = req.header("Last-Event-ID", lastId);
    }
    return req.exchange()
        .expectStatus()
        .isOk()
        .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
        .getResponseBody();
  }

  private static UiEvent event(long seq, UUID tender, String type) {
    return new UiEvent(
        seq, tender, type, "{\"type\":\"" + type + "\",\"tender_id\":\"" + tender + "\"}");
  }

  @Test
  void aMemberGetsTheEventsOfTheirTenderWithTheSequenceAsId() {
    TestUser admin = TestUsers.create("admin");
    TestUser member = TestUsers.create("member");
    UUID mine = Fixtures.tender(admin, member);
    UUID other = Fixtures.tender(admin, TestUsers.create("member"));

    var received = new java.util.concurrent.CopyOnWriteArrayList<ServerSentEvent<String>>();
    var sub = stream(member, null, "").subscribe(received::add);
    try {
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> live.currentSubscriberCount() > 0);
      live.tryEmitNext(event(11, other, "job.updated")); // not theirs
      live.tryEmitNext(event(12, mine, "job.updated"));
      live.tryEmitNext(event(13, mine, "document.updated"));
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> received.stream().filter(e -> e.id() != null).count() == 2);
    } finally {
      sub.dispose();
    }
    var data = received.stream().filter(e -> e.id() != null).toList();
    assertThat(data.get(0).id()).isEqualTo("12");
    assertThat(data.get(0).event()).isEqualTo("job.updated");
    assertThat(data.get(0).data()).contains(mine.toString());
    assertThat(data.get(1).id()).isEqualTo("13");
    assertThat(data.stream().map(ServerSentEvent::data))
        .noneMatch(d -> d.contains(other.toString()));
  }

  @Test
  void aReconnectReceivesWhatItMissedInsideTheWindow() throws Exception {
    TestUser admin = TestUsers.create("admin");
    TestUser member = TestUsers.create("member");
    UUID mine = Fixtures.tender(admin, member);
    when(hub.replayAfter(8L, 10L))
        .thenReturn(List.of(event(9, mine, "job.updated"), event(10, mine, "document.updated")));

    var first =
        stream(member, "8", "")
            .filter(e -> e.comment() == null)
            .take(2)
            .collectList()
            .block(Duration.ofSeconds(10));
    assertThat(first).extracting(ServerSentEvent::id).containsExactly("9", "10");
  }

  @Test
  void outsideTheWindowTheServerSaysResync() throws Exception {
    TestUser member = TestUsers.create("member");
    when(hub.replayAfter(anyLong(), anyLong())).thenReturn(null);
    var first =
        stream(member, "1", "")
            .filter(e -> e.comment() == null)
            .next()
            .block(Duration.ofSeconds(10));
    assertThat(first.event()).isEqualTo("resync");
  }

  @Test
  void aTenderFilterNarrowsTheStream() {
    TestUser admin = TestUsers.create("admin");
    TestUser member = TestUsers.create("member");
    UUID a = Fixtures.tender(admin, member);
    UUID b = Fixtures.tender(admin, member);
    var received = new java.util.concurrent.CopyOnWriteArrayList<ServerSentEvent<String>>();
    var sub = stream(member, null, "?tender_id=" + b).subscribe(received::add);
    try {
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> live.currentSubscriberCount() > 0);
      live.tryEmitNext(event(11, a, "job.updated"));
      live.tryEmitNext(event(12, b, "job.updated"));
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> received.stream().anyMatch(e -> "12".equals(e.id())));
    } finally {
      sub.dispose();
    }
    assertThat(received.stream().map(ServerSentEvent::id)).doesNotContain("11");
  }

  @Test
  void aStreamNeedsASignedInPerson() {
    web.get()
        .uri("/events")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }
}
