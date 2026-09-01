import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests run against a real stack: the Next.js server, the gateway, and the platform
 * services behind it. There are no mocks here on purpose — these tests exist to catch the failures
 * that only appear when the pieces are wired together.
 */
export default defineConfig({
  testDir: "./e2e",
  // Provisions the patient the chart tests search for. Without it the suite depends on whatever
  // happens to be in the database, which is how three tests passed locally and failed the first
  // time CI ran them against a fresh PostgreSQL.
  globalSetup: "./e2e/global-setup.ts",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    // Must match the origin Next resolves its redirects against, or the SameSite=Strict session
    // cookie is dropped when the sign-in redirect crosses to a different host name.
    baseURL: process.env.WEB_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        // The image ships Chromium at a known path; never download a browser at test time.
        launchOptions: { executablePath: process.env.CHROMIUM_PATH ?? undefined },
      },
    },
  ],
});
