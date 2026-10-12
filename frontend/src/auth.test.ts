import { describe, expect, it } from "vitest";
import { oidcSettings } from "./auth";

const config = {
  apiBaseUrl: "/api/v1",
  oidcAuthority: "http://localhost:18081/realms/tender-eval",
  oidcClientId: "tender-spa",
  oidcRedirectUri: "http://localhost:18080/",
  postLogoutRedirectUri: "http://localhost:18080/",
};

describe("oidcSettings", () => {
  const settings = oidcSettings(config);

  it("uses the authorization code flow for the public client", () => {
    expect(settings.response_type).toBe("code");
    expect(settings.client_id).toBe("tender-spa");
  });

  it("holds no client secret", () => {
    expect(settings).not.toHaveProperty("client_secret");
    expect(JSON.stringify(settings)).not.toMatch(/secret/i);
  });
});
