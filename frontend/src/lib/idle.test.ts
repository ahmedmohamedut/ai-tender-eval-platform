import { describe, expect, it } from "vitest";
import { idleState, shouldRenewToken } from "./idle";

const T0 = 1_000_000;

describe("idleState (UX-AUTH-04)", () => {
  it("is active while the person is working", () => {
    expect(idleState(T0 + 10_000, T0, 1800, 120)).toEqual({ phase: "active", secondsLeft: 0 });
    expect(idleState(T0 + 1679_000, T0, 1800, 120).phase).toBe("active");
  });

  it("warns two minutes before the realm's idle timeout", () => {
    const s = idleState(T0 + 1680_000, T0, 1800, 120);
    expect(s.phase).toBe("warning");
    expect(s.secondsLeft).toBe(120);
    expect(idleState(T0 + 1740_000, T0, 1800, 120).secondsLeft).toBe(60);
  });

  it("expires at the timeout", () => {
    expect(idleState(T0 + 1800_000, T0, 1800, 120)).toEqual({ phase: "expired", secondsLeft: 0 });
  });

  it("restarts the clock on activity", () => {
    expect(idleState(T0 + 1790_000, T0 + 1700_000, 1800, 120).phase).toBe("active");
  });
});

describe("shouldRenewToken", () => {
  it("renews near expiry while active", () => {
    expect(shouldRenewToken(T0 + 30_000, T0, "active")).toBe(true);
    expect(shouldRenewToken(T0 + 200_000, T0, "active")).toBe(false);
  });

  it("never renews while idle, so the session can end (PI 3.1)", () => {
    expect(shouldRenewToken(T0 + 30_000, T0, "warning")).toBe(false);
    expect(shouldRenewToken(T0 + 30_000, T0, "expired")).toBe(false);
  });

  it("does nothing without an expiry", () => {
    expect(shouldRenewToken(undefined, T0, "active")).toBe(false);
  });
});
