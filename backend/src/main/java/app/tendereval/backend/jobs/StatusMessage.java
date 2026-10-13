package app.tendereval.backend.jobs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A {@code documents.analysis.status.*} body (PI 5.5), read from JSON. */
public record StatusMessage(
    UUID jobId,
    String jobType,
    UUID tenderId,
    String targetType,
    UUID targetId,
    long seq,
    String state,
    String step,
    Integer progressPercent,
    List<String> steps,
    Integer resultParts,
    Map<String, Integer> counts,
    String warningsJson,
    String failureCode,
    String failureMessage) {

  public static final java.util.Set<String> STATES =
      java.util.Set.of("running", "completed", "completed_with_warnings", "failed", "cancelled");

  public boolean terminal() {
    return !"running".equals(state);
  }

  /**
   * @throws IllegalArgumentException when the body is not a valid status message
   */
  public static StatusMessage parse(JsonNode body) {
    String state = text(body, "state");
    if (state == null || !STATES.contains(state)) {
      throw new IllegalArgumentException("unknown state");
    }
    JsonNode seq = body.get("seq");
    if (seq == null || !seq.isNumber() || seq.asLong() < 1) {
      throw new IllegalArgumentException("invalid seq");
    }
    JsonNode target = body.get("target");
    if (target == null || text(target, "id") == null || text(target, "type") == null) {
      throw new IllegalArgumentException("missing target");
    }
    List<String> steps = null;
    if (body.get("steps") != null && body.get("steps").isArray()) {
      steps = new ArrayList<>();
      for (JsonNode s : body.get("steps")) {
        steps.add(s.asString());
      }
    }
    Map<String, Integer> counts = null;
    if (body.get("counts") != null && body.get("counts").isObject()) {
      counts = new LinkedHashMap<>();
      for (var e : body.get("counts").properties()) {
        counts.put(e.getKey(), e.getValue().asInt());
      }
    }
    JsonNode failure = body.get("failure");
    JsonNode warnings = body.get("warnings");
    return new StatusMessage(
        uuid(text(body, "job_id")),
        required(body, "job_type"),
        uuid(required(body, "tender_id")),
        text(target, "type"),
        uuid(text(target, "id")),
        seq.asLong(),
        state,
        text(body, "step"),
        intOrNull(body, "progress_percent"),
        steps,
        intOrNull(body, "result_parts"),
        counts,
        warnings == null || warnings.isNull() ? null : warnings.toString(),
        failure == null ? null : text(failure, "code"),
        failure == null ? null : text(failure, "message"));
  }

  private static String required(JsonNode n, String f) {
    String v = text(n, f);
    if (v == null) {
      throw new IllegalArgumentException("missing " + f);
    }
    return v;
  }

  private static UUID uuid(String v) {
    return v == null ? null : UUID.fromString(v);
  }

  private static Integer intOrNull(JsonNode n, String f) {
    JsonNode v = n.get(f);
    return v == null || v.isNull() || !v.isNumber() ? null : v.asInt();
  }

  static String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode v = node.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }
}
