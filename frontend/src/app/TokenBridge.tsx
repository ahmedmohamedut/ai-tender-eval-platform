import { useLayoutEffect, type ReactNode } from "react";
import { useAuth } from "react-oidc-context";
import { setAccessToken } from "../lib/token";

/**
 * Hands the access token held by the OIDC library to the API client. It uses a layout effect on
 * purpose: every layout effect runs before any passive effect, so the first `GET /me` of the
 * session provider (a passive effect in a child) already finds the token. With a passive effect
 * here the child's effect ran first and the first call went out without a token (401).
 */
export function TokenBridge({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const token = auth.user?.access_token;
  useLayoutEffect(() => {
    setAccessToken(token);
  }, [token]);
  return <>{children}</>;
}
