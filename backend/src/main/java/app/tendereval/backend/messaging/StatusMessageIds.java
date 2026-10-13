package app.tendereval.backend.messaging;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Reads the job id of a status or result body without validating the rest. */
final class StatusMessageIds {

  private StatusMessageIds() {}

  static String jobId(JsonNode body) {
    JsonNode v = body.get("job_id");
    if (v == null || v.isNull()) {
      return null;
    }
    try {
      return UUID.fromString(v.asString()).toString();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
