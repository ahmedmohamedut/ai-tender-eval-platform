/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// `npm run dev` proxies runtime config and the API to the local gateway (make up).
const gateway = process.env.GATEWAY_URL ?? "http://localhost:18080";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/config.json": gateway,
      "/api": { target: gateway, changeOrigin: false },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test-setup.ts"],
    // Playwright journeys run with `make e2e`, not under vitest.
    exclude: ["e2e/**", "node_modules/**", "dist/**"],
  },
});
