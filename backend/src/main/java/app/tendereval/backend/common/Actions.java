package app.tendereval.backend.common;

import app.tendereval.backend.api.model.ActionState;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@code actions} map of a resource (UX-ROL-03). An action the caller can never take is
 * left out; one that depends on state is present, disabled, with a one-line reason.
 */
public final class Actions {

  private final Map<String, ActionState> map = new LinkedHashMap<>();

  public static Actions builder() {
    return new Actions();
  }

  public Actions allow(String name) {
    map.put(name, new ActionState().allowed(true));
    return this;
  }

  public Actions deny(String name, String reason) {
    map.put(name, new ActionState().allowed(false).reason(reason));
    return this;
  }

  public Actions when(String name, boolean allowed, String reasonIfNot) {
    return allowed ? allow(name) : deny(name, reasonIfNot);
  }

  public Map<String, ActionState> build() {
    return map;
  }
}
