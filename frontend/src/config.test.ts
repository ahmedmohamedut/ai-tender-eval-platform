import { describe, expect, it, vi } from "vitest";
import { loadConfig, parseConfig } from "./config";

const valid = {
  apiBaseUrl: "/api/v1",
  oidcAuthority: "http://localhost:18081/realms/tender-eval",
  oidcClientId: "tender-spa",
  oidcRedirectUri: "http://localhost:18080/",
  postLogoutRedirectUri: "http://localhost:18080/",
};

describe("parseConfig", () => {
  it("accepts a complete config", () => {
    expect(parseConfig(valid)).toEqual(valid);
  });

  it("names every missing key and fails", () => {
    const { oidcAuthority: _a, oidcClientId: _b, ...rest } = valid;
    expect(() => parseConfig(rest)).toThrow(/oidcAuthority, oidcClientId/);
  });

  it("rejects empty strings and non-objects", () => {
    expect(() => parseConfig({ ...valid, apiBaseUrl: "" })).toThrow(/apiBaseUrl/);
    expect(() => parseConfig(null)).toThrow();
  });

  it("drops unknown keys so nothing unexpected reaches the client", () => {
    expect(parseConfig({ ...valid, clientSecret: "x" })).not.toHaveProperty("clientSecret");
  });
});

describe("loadConfig", () => {
  it("fails when the gateway does not serve config.json", async () => {
    const fetcher = vi.fn().mockResolvedValue({ ok: false, status: 404 });
    await expect(loadConfig(fetcher as unknown as typeof fetch)).rejects.toThrow(/404/);
  });
});
