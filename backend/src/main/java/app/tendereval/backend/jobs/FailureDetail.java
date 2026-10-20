package app.tendereval.backend.jobs;

import java.util.Map;

/**
 * The sentence for a worker's failure cause. A worker sends a closed cause and whole numbers; the
 * wording is here, so nothing a worker wrote as text reaches a screen or a log (rule 10).
 */
final class FailureDetail {

  private FailureDetail() {}

  /** Null for a cause this version does not know. */
  static String render(String cause, Map<String, Integer> params) {
    if (cause == null) {
      return null;
    }
    Map<String, Integer> p = params == null ? Map.of() : params;
    return switch (cause) {
      case "embedding_dimension_mismatch" -> {
        Integer returned = p.get("returned");
        Integer configured = p.get("configured");
        yield returned == null || configured == null
            ? null
            : "The embedding model returned vectors of "
                + returned
                + " values but "
                + configured
                + " are configured. Set EMBEDDING_DIMENSIONS to "
                + returned
                + " and use a new QDRANT_COLLECTION.";
      }
      case "embedding_http_error" ->
          p.get("status") == null
              ? null
              : "The embedding service answered with HTTP " + p.get("status") + ".";
      case "embedding_timeout" ->
          p.get("seconds") == null
              ? null
              : "The embedding service did not answer within " + p.get("seconds") + " seconds.";
      case "embedding_unreachable" -> "The embedding service could not be reached.";
      case "embedding_unreadable" -> "The embedding service sent an answer that could not be read.";
      default -> null;
    };
  }
}
