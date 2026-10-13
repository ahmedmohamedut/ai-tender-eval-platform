package app.tendereval.backend.jobs;

import app.tendereval.backend.config.AppProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Runs {@link JobTimers#sweep()} on an interval, and keeps the audit partitions ahead. */
@Component
@ConditionalOnProperty(name = "app.watchdog-enabled", havingValue = "true", matchIfMissing = true)
public class JobWatchdog implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(JobWatchdog.class);

  private final JobTimers timers;
  private final DatabaseClient requestDb;
  private final AppProperties props;
  private Disposable sweeps;
  private Disposable partitions;

  public JobWatchdog(JobTimers timers, DatabaseClient requestDb, AppProperties props) {
    this.timers = timers;
    this.requestDb = requestDb;
    this.props = props;
  }

  @Override
  public void start() {
    Duration every = Duration.ofSeconds(Math.max(5, props.watchdogIntervalSeconds()));
    sweeps =
        Flux.interval(every, every)
            .onBackpressureDrop()
            .concatMap(
                tick ->
                    timers
                        .sweep()
                        .onErrorResume(
                            e -> {
                              log.warn("timer pass failed: {}", e.getClass().getSimpleName());
                              return Mono.empty();
                            }))
            .subscribe();
    partitions =
        Flux.interval(Duration.ZERO, Duration.ofHours(24))
            .concatMap(
                t ->
                    JobTimers.partitions(requestDb)
                        .fetch()
                        .rowsUpdated()
                        .onErrorResume(
                            e -> {
                              log.warn("partitions not ensured: {}", e.getClass().getSimpleName());
                              return Mono.empty();
                            }))
            .subscribe();
  }

  @Override
  public void stop() {
    if (sweeps != null) {
      sweeps.dispose();
    }
    if (partitions != null) {
      partitions.dispose();
    }
  }

  @Override
  public boolean isRunning() {
    return sweeps != null && !sweeps.isDisposed();
  }
}
