package app.tendereval.backend.jobs;

import java.time.Duration;

/** What a message handler asks its consumer to do with the message (PI 5.7, 5.8). */
public sealed interface Outcome {

  /** Done, or a duplicate that changed nothing: acknowledge. */
  record Ack(String note) implements Outcome {}

  /** Not ready yet (a result batch has not been stored): return it after a delay. */
  record Retry(Duration delay, String note) implements Outcome {}

  /** Unreadable or for an unknown job: terminate and copy to the dead-letter stream. */
  record DeadLetter(String reason) implements Outcome {}

  static Outcome ack(String note) {
    return new Ack(note);
  }

  static Outcome retry(Duration delay, String note) {
    return new Retry(delay, note);
  }

  static Outcome deadLetter(String reason) {
    return new DeadLetter(reason);
  }
}
