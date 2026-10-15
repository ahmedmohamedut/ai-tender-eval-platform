package app.tendereval.backend.events;

import app.tendereval.backend.db.RequestTransaction;
import app.tendereval.backend.events.UiEventHub.UiEvent;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@code GET /events} (PI 5.10, UX-GEN-06): one stream per tab. Each event carries its stream
 * sequence as the SSE id; a connection gets only events of tenders its person can see (the set is
 * read through row-level security and refreshed), a comment line every 15 seconds, and a replay
 * from {@code Last-Event-ID} inside the 15-minute window, else a {@code resync}.
 */
@RestController
public class EventsController {

  static final Duration HEARTBEAT = Duration.ofSeconds(15);
  static final Duration REFRESH = Duration.ofSeconds(30);

  private final RequestTransaction tx;
  private final UiEventHub hub;

  public EventsController(RequestTransaction tx, UiEventHub hub) {
    this.tx = tx;
    this.hub = hub;
  }

  @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<String>> events(
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
      @RequestParam(value = "tender_id", required = false) UUID tenderId) {
    Long after = parse(lastEventId);
    return RequestTransaction.currentUser().flatMapMany(u -> stream(after, tenderId, u.id()));
  }

  private Flux<ServerSentEvent<String>> stream(Long after, UUID tenderId, UUID me) {
    AtomicReference<Set<UUID>> allowed = new AtomicReference<>(Set.of());
    Mono<Set<UUID>> load =
        tx.inRequestTransaction(
            db ->
                db.sql("SELECT id FROM tenders WHERE deleted_at IS NULL")
                    .map((r, m) -> r.get("id", UUID.class))
                    .all()
                    .collectList()
                    .map(l -> (Set<UUID>) new HashSet<>(l)));

    return load.doOnNext(allowed::set)
        .flatMapMany(
            initial -> {
              Flux<UiEvent> live = hub.live();
              // Events that arrive while the replay is read are kept and de-duplicated by id.
              var buffered = live.replay(512);
              var connection = buffered.connect();
              Flux<UiEvent> replayed =
                  Mono.fromCallable(
                          () -> {
                            long last = hub.lastSequence();
                            if (after == null) {
                              return new Replay(last, java.util.List.of(), false);
                            }
                            var events = hub.replayAfter(after, last);
                            return events == null
                                ? new Replay(last, java.util.List.of(), true)
                                : new Replay(last, events, false);
                          })
                      .subscribeOn(Schedulers.boundedElastic())
                      .flatMapMany(
                          rp ->
                              rp.resync()
                                  ? Flux.just(new UiEvent(rp.last(), null, "resync", "{}"))
                                  : Flux.fromIterable(rp.events())
                                      .concatWith(buffered.filter(e -> e.seq() > rp.last())));
              Flux<ServerSentEvent<String>> events =
                  replayed
                      .filter(
                          e ->
                              e.userId() != null
                                  ? e.userId().equals(me)
                                  : e.tenderId() == null
                                      || (allowed.get().contains(e.tenderId())
                                          && (tenderId == null || tenderId.equals(e.tenderId()))))
                      .map(
                          e ->
                              ServerSentEvent.<String>builder()
                                  .id(Long.toString(e.seq()))
                                  .event(e.type())
                                  .data(e.json())
                                  .build());
              Flux<ServerSentEvent<String>> beat =
                  Flux.interval(HEARTBEAT)
                      .map(i -> ServerSentEvent.<String>builder().comment("keep-alive").build());
              Flux<Object> refresh =
                  Flux.interval(REFRESH)
                      .concatMap(
                          i ->
                              load.doOnNext(allowed::set)
                                  .onErrorResume(e -> Mono.empty())
                                  .cast(Object.class));
              // An immediate comment commits the headers, so proxies and clients see the stream
              // open.
              Flux<ServerSentEvent<String>> hello =
                  Flux.just(ServerSentEvent.<String>builder().comment("connected").build());
              return Flux.concat(hello, Flux.merge(events, beat))
                  .mergeWith(refresh.thenMany(Flux.<ServerSentEvent<String>>empty()))
                  .doFinally(s -> connection.dispose());
            });
  }

  private record Replay(long last, java.util.List<UiEvent> events, boolean resync) {}

  private static Long parse(String id) {
    if (id == null || id.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(id.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
