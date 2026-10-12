import { WebStorageStateStore, type UserManagerSettings } from "oidc-client-ts";
import type { RuntimeConfig } from "./config";

/**
 * Authorization code flow with PKCE (S256) for the public client `tender-spa`.
 * oidc-client-ts adds the code challenge itself; there is no client secret and
 * no implicit or password flow (PI 3.3).
 */
export function oidcSettings(config: RuntimeConfig): UserManagerSettings {
  return {
    authority: config.oidcAuthority,
    client_id: config.oidcClientId,
    redirect_uri: config.oidcRedirectUri,
    post_logout_redirect_uri: config.postLogoutRedirectUri,
    response_type: "code",
    scope: "openid profile email",
    // Tokens stay in memory for the tab's session only.
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: false,
  };
}
