import { describe, expect, it, vi } from "vitest";
import { createApiClient } from "./client";

describe("createApiClient", () => {
  it("sends the bearer token and calls the typed path under the base url", async () => {
    const fetchMock = vi.fn(
      async (_req: Request) =>
        new Response(JSON.stringify({}), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
    );
    const client = createApiClient("http://gateway.test/api/v1", () => "tok-123");
    // `/me` is operation getMe; a wrong path would fail type checking.
    await client.GET("/me", { fetch: fetchMock as unknown as typeof fetch });

    const request = fetchMock.mock.calls[0][0];
    expect(request.url).toBe("http://gateway.test/api/v1/me");
    expect(request.headers.get("Authorization")).toBe("Bearer tok-123");
  });

  it("sends no Authorization header when there is no token", async () => {
    const fetchMock = vi.fn(async (_req: Request) => new Response("{}", { status: 200 }));
    const client = createApiClient("http://gateway.test/api/v1", () => undefined);
    await client.GET("/me", { fetch: fetchMock as unknown as typeof fetch });
    expect(fetchMock.mock.calls[0][0].headers.has("Authorization")).toBe(false);
  });
});
