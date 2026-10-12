package app.tendereval.backend.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One audit event to record (DM Audit trail). {@code before} and {@code after} hold only the
 * columns that changed; {@link #changed} builds them from two full maps.
 */
public record AuditEntry(
    String action,
    String objectType,
    UUID objectId,
    UUID tenderId,
    UUID tenderVendorId,
    Map<String, Object> before,
    Map<String, Object> after,
    String note,
    UUID batchId) {

  public static Builder of(String action, String objectType, UUID objectId) {
    return new Builder(action, objectType, objectId);
  }

  public static final class Builder {
    private final String action;
    private final String objectType;
    private final UUID objectId;
    private UUID tenderId;
    private UUID tenderVendorId;
    private Map<String, Object> before = Map.of();
    private Map<String, Object> after = Map.of();
    private String note;
    private UUID batchId;

    Builder(String action, String objectType, UUID objectId) {
      this.action = action;
      this.objectType = objectType;
      this.objectId = objectId;
    }

    public Builder tender(UUID id) {
      this.tenderId = id;
      return this;
    }

    public Builder vendor(UUID tenderVendorId) {
      this.tenderVendorId = tenderVendorId;
      return this;
    }

    public Builder note(String note) {
      this.note = note;
      return this;
    }

    public Builder batch(UUID batchId) {
      this.batchId = batchId;
      return this;
    }

    public Builder created(Map<String, Object> values) {
      this.after = values;
      return this;
    }

    /** Keeps only the keys whose values differ. */
    public Builder changed(Map<String, Object> from, Map<String, Object> to) {
      Map<String, Object> b = new LinkedHashMap<>();
      Map<String, Object> a = new LinkedHashMap<>();
      for (Map.Entry<String, Object> e : to.entrySet()) {
        Object old = from.get(e.getKey());
        if (!java.util.Objects.equals(old, e.getValue())) {
          b.put(e.getKey(), old);
          a.put(e.getKey(), e.getValue());
        }
      }
      this.before = b;
      this.after = a;
      return this;
    }

    public AuditEntry build() {
      return new AuditEntry(
          action, objectType, objectId, tenderId, tenderVendorId, before, after, note, batchId);
    }
  }
}
