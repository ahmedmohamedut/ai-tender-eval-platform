package app.tendereval.backend.web;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** W3C trace context helpers. The trace id is carried into problem bodies and NATS headers. */
public final class TraceIds {

  private static final Pattern TRACEPARENT =
      Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");
  private static final SecureRandom RANDOM = new SecureRandom();

  private TraceIds() {}

  public static String traceIdOf(String traceparent) {
    if (traceparent != null) {
      Matcher m = TRACEPARENT.matcher(traceparent.trim());
      if (m.matches() && !m.group(1).equals("0".repeat(32))) {
        return m.group(1);
      }
    }
    return randomHex(16);
  }

  public static String newTraceId() {
    return randomHex(16);
  }

  public static String newTraceparent(String traceId) {
    return "00-" + traceId + "-" + randomHex(8) + "-01";
  }

  private static String randomHex(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }
}
