/**
 * Session idle handling (UX-AUTH-04): the browser warns `warningSeconds` before the idle
 * timeout of the realm and signs the person out when it passes. Keycloak enforces the end; this
 * only tells the person first. Pure, so it is tested without a browser.
 */
export type IdlePhase = "active" | "warning" | "expired";

export interface IdleState {
  phase: IdlePhase;
  /** Seconds until sign-out while warning, else 0. */
  secondsLeft: number;
}

export function idleState(
  nowMs: number,
  lastActivityMs: number,
  idleTimeoutSeconds: number,
  warningSeconds: number,
): IdleState {
  const idle = Math.max(0, (nowMs - lastActivityMs) / 1000);
  if (idle >= idleTimeoutSeconds) {
    return { phase: "expired", secondsLeft: 0 };
  }
  if (idle >= idleTimeoutSeconds - warningSeconds) {
    return { phase: "warning", secondsLeft: Math.ceil(idleTimeoutSeconds - idle) };
  }
  return { phase: "active", secondsLeft: 0 };
}

/** Whether the access token should be renewed now: close to expiry, and the person is active. */
export function shouldRenewToken(
  expiresAtMs: number | undefined,
  nowMs: number,
  phase: IdlePhase,
  leadSeconds = 60,
): boolean {
  if (expiresAtMs === undefined || phase !== "active") {
    return false;
  }
  return expiresAtMs - nowMs <= leadSeconds * 1000;
}
