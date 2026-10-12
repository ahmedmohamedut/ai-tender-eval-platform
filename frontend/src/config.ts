/**
 * Runtime configuration, served by the gateway as /config.json (PI 7.5).
 * The single-page application is a public client: nothing here is a secret.
 */
export interface RuntimeConfig {
  apiBaseUrl: string;
  oidcAuthority: string;
  oidcClientId: string;
  oidcRedirectUri: string;
  postLogoutRedirectUri: string;
}

const REQUIRED: (keyof RuntimeConfig)[] = [
  "apiBaseUrl",
  "oidcAuthority",
  "oidcClientId",
  "oidcRedirectUri",
  "postLogoutRedirectUri",
];

export function parseConfig(raw: unknown): RuntimeConfig {
  if (typeof raw !== "object" || raw === null) {
    throw new Error("config.json is not an object");
  }
  const record = raw as Record<string, unknown>;
  const missing = REQUIRED.filter((k) => typeof record[k] !== "string" || record[k] === "");
  if (missing.length > 0) {
    throw new Error(`config.json is missing: ${missing.join(", ")}`);
  }
  return Object.fromEntries(REQUIRED.map((k) => [k, record[k]])) as unknown as RuntimeConfig;
}

export async function loadConfig(fetcher: typeof fetch = fetch): Promise<RuntimeConfig> {
  const response = await fetcher("/config.json", { cache: "no-store" });
  if (!response.ok) {
    throw new Error(`config.json returned ${response.status}`);
  }
  return parseConfig(await response.json());
}
