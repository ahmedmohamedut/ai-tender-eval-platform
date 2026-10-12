package app.tendereval.backend.messaging;

/** Subjects, schemas and consumer settings of PI 5.2 to 5.4. */
public final class Subjects {

  public static final String COMMAND_PREFIX = "documents.analysis.requested.";
  public static final String STATUS_FILTER = "documents.analysis.status.>";
  public static final String SCHEMA_COMMAND = "tender.analysis.requested/1";
  public static final String SCHEMA_STATUS_NAME = "tender.analysis.status";
  public static final String SCHEMA_STATUS_MAJOR = "1";

  private Subjects() {}

  public static String command(String jobType) {
    return COMMAND_PREFIX + jobType;
  }
}
