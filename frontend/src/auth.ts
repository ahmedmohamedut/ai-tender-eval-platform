import { InMemoryWebStorage, WebStorageStateStore, type UserManagerSettings } from "oidc-client-ts";
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
    // The signed-in user, with its tokens, is held in memory only (PI 7.1: the SPA keeps no
    // secret and stores nothing). A reload signs in again through the Keycloak session.
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    // The sign-in request state must survive the redirect, so it uses session storage.
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: false,
  };
}
