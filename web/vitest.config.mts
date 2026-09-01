import { defineConfig } from "vitest/config";
import { fileURLToPath } from "node:url";

/**
 * Unit tests for the web tier's pure logic only.
 *
 * Anything that needs a rendered page, a real cookie jar, or the gateway is covered by the
 * Playwright suite in e2e/, against the running stack. Mocking Next's `cookies()` and `fetch` to
 * assert that a component renders would test the mocks; the browser suite tests the behaviour.
 */
export default defineConfig({
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
});
