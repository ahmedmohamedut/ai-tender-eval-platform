import { render } from "@testing-library/react";
import { useEffect } from "react";
import { describe, expect, it, vi } from "vitest";
import { getAccessToken, setAccessToken } from "../lib/token";
import { TokenBridge } from "./TokenBridge";

vi.mock("react-oidc-context", () => ({
  useAuth: () => ({ user: { access_token: "tok-123" } }),
}));

describe("TokenBridge", () => {
  it("has the token in place before a child's first effect runs", () => {
    setAccessToken(undefined);
    let seen: string | undefined = "not-run";
    function Child() {
      // The session provider calls GET /me from exactly this kind of effect.
      useEffect(() => {
        seen = getAccessToken();
      }, []);
      return null;
    }
    render(
      <TokenBridge>
        <Child />
      </TokenBridge>,
    );
    expect(seen).toBe("tok-123");
  });
});
