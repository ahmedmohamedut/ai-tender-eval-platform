/**
 * The access token, held in memory only. The OIDC library owns the user; this is the one place
 * the API client reads the current token from, so a refresh is picked up by the next request.
 */
let current: string | undefined;

export function setAccessToken(token: string | undefined): void {
  current = token;
}

export function getAccessToken(): string | undefined {
  return current;
}
