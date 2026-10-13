package app.tendereval.backend.common;

import app.tendereval.backend.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code Idempotency-Key} on job and export starts (PI 2.1, UX-JOB-08). The first request with a
 * key claims it inside the caller's transaction and stores its answer; a repeat within 24 hours
 * returns that answer instead of doing the work again. A key reused for a different request is
 * refused. Two concurrent requests with one key serialize on the unique index.
 */
@Component
public class Idempotency {

  private final JsonMapper json = JsonMapper.builder().build();

  public static String requireKey(String header) {
    if (header == null || header.isBlank()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "idempotency_key_required", "Idempotency-Key is required");
    }
    String key = header.trim();
    if (key.length() < 8 || key.length() > 200) {
      throw ApiException.invalid(
          "Idempotency-Key", "length", "The key must be 8 to 200 characters");
    }
    return key;
  }

  public static String hash(String... parts) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (String p : parts) {
        md.update(String.valueOf(p).getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Runs {@code work} once per (user, key, operation). The work returns the response to store. */
  public <T> Mono<ResponseEntity<T>> run(
      DatabaseClient db,
      UUID user,
      String key,
      String operation,
      String requestHash,
      Class<T> type,
      Supplier<Mono<ResponseEntity<T>>> work) {
    return db.sql(
            "INSERT INTO idempotency_keys (user_id, idempotency_key, operation, request_hash)"
                + " VALUES (:u, :k, :o, :h) ON CONFLICT (user_id, idempotency_key, operation)"
                + " DO NOTHING RETURNING id")
        .bind("u", user)
        .bind("k", key)
        .bind("o", operation)
        .bind("h", requestHash)
        .map((row, meta) -> row.get("id", UUID.class))
        .one()
        .flatMap(
            claimed ->
                work.get().flatMap(response -> store(db, claimed, response).thenReturn(response)))
        .switchIfEmpty(Mono.defer(() -> replay(db, user, key, operation, requestHash, type)));
  }

  private <T> Mono<Void> store(DatabaseClient db, UUID id, ResponseEntity<T> response) {
    String body = response.getBody() == null ? null : json.writeValueAsString(response.getBody());
    String location =
        response.getHeaders().getLocation() == null
            ? null
            : response.getHeaders().getLocation().toString();
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
                "UPDATE idempotency_keys SET response_status = :s, response_body = CAST(:b AS jsonb),"
                    + " location = :l WHERE id = :id")
            .bind("s", response.getStatusCode().value())
            .bind("id", id);
    spec = body == null ? spec.bindNull("b", String.class) : spec.bind("b", body);
    spec = location == null ? spec.bindNull("l", String.class) : spec.bind("l", location);
    return spec.fetch().rowsUpdated().then();
  }

  /**
   * The stored answer of an earlier request with this key, or empty. For flows that cannot claim
   * first.
   */
  public <T> Mono<ResponseEntity<T>> lookup(
      DatabaseClient db, UUID user, String key, String operation, String hash, Class<T> type) {
    return replay(db, user, key, operation, hash, type);
  }

  /** Remembers a finished answer. A concurrent twin loses quietly (ON CONFLICT DO NOTHING). */
  public <T> Mono<Void> remember(
      DatabaseClient db,
      UUID user,
      String key,
      String operation,
      String hash,
      ResponseEntity<T> response) {
    return db.sql(
            "INSERT INTO idempotency_keys (user_id, idempotency_key, operation, request_hash)"
                + " VALUES (:u, :k, :o, :h) ON CONFLICT (user_id, idempotency_key, operation)"
                + " DO NOTHING RETURNING id")
        .bind("u", user)
        .bind("k", key)
        .bind("o", operation)
        .bind("h", hash)
        .map((row, meta) -> row.get("id", UUID.class))
        .one()
        .flatMap(id -> store(db, id, response));
  }

  private <T> Mono<ResponseEntity<T>> replay(
      DatabaseClient db, UUID user, String key, String operation, String hash, Class<T> type) {
    return db.sql(
            "SELECT request_hash, response_status, response_body::text AS body, location"
                + " FROM idempotency_keys WHERE user_id = :u AND idempotency_key = :k"
                + " AND operation = :o")
        .bind("u", user)
        .bind("k", key)
        .bind("o", operation)
        .map(
            (row, meta) -> {
              if (!hash.equals(row.get("request_hash", String.class))) {
                throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "idempotency_key_reused",
                    "This key was used for a different request");
              }
              Integer status = row.get("response_status", Integer.class);
              if (status == null) {
                throw ApiException.conflict(
                    "request_in_progress", "The first request with this key is still running");
              }
              String body = row.get("body", String.class);
              HttpHeaders headers = new HttpHeaders();
              String location = row.get("location", String.class);
              if (location != null) {
                headers.setLocation(java.net.URI.create(location));
              }
              headers.add("Idempotent-Replay", "true");
              T value = body == null ? null : json.readValue(body, type);
              return ResponseEntity.status(status).headers(headers).body(value);
            })
        .one();
  }
}
