import { defineConfig } from "@playwright/test";

/**
 * End-to-end journeys against the real stack (`make up`): gateway, backend, Keycloak, Mailpit.
 * Run with `make e2e`, which loads the root .env.
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 120_000,
  expect: { timeout: 15_000 },
  workers: 1,
  fullyParallel: false,
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:18080",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
