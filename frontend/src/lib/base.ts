/** The API base URL, set once at start (config.json), for code that does not use the typed client. */
let base = "/api/v1";

export function setApiBase(url: string): void {
  base = url.replace(/\/$/, "");
}

export function getApiBase(): string {
  return base;
}
