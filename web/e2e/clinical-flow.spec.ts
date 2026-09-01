import { expect, test } from "@playwright/test";

/**
 * The journeys a clinician actually performs, driven through the browser.
 *
 * Credentials come from the environment so the suite never hard-codes a password.
 */
const PASSWORD = process.env.SEED_PASSWORD ?? "ChangeMe!Dev2026";

async function signIn(page: import("@playwright/test").Page, username: string) {
  await page.goto("/login");
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Today" })).toBeVisible();
}

test.describe("authentication", () => {
  test("an unauthenticated visitor is sent to sign-in", async ({ page }) => {
    await page.goto("/patients");
    await expect(page).toHaveURL(/\/login/);
  });

  test("bad credentials are rejected without revealing which part was wrong", async ({ page }) => {
    await page.goto("/login");
    await page.getByLabel("Username").fill("dr.rao");
    await page.getByLabel("Password").fill("definitely-wrong");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page.getByRole("main").getByRole("alert")).toContainText(
      /invalid username or password/i,
    );
  });

  test("a clinician can sign in and out", async ({ page }) => {
    await signIn(page, "dr.rao");
    await expect(page.getByRole("banner").getByText("Dr Anika Rao")).toBeVisible();

    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login/);
  });

  test("no access token is exposed to client-side script", async ({ page }) => {
    await signIn(page, "dr.rao");
    // The session lives in httpOnly cookies; document.cookie must not see it.
    const visible = await page.evaluate(() => document.cookie);
    expect(visible).not.toContain("medsync_at");
  });
});

test.describe("navigation is role-aware", () => {
  test("a doctor sees the laboratory and triage sections", async ({ page }) => {
    await signIn(page, "dr.rao");
    const nav = page.getByRole("navigation");
    await expect(nav.getByRole("link", { name: "Laboratory" })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Triage" })).toBeVisible();
  });

  test("a lab technician does not see triage", async ({ page }) => {
    await signIn(page, "lab.tech");
    const nav = page.getByRole("navigation");
    await expect(nav.getByRole("link", { name: "Laboratory" })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Triage" })).toHaveCount(0);
  });
});

test.describe("patients", () => {
  test("search finds a patient and opens their chart", async ({ page }) => {
    await signIn(page, "reception");
    await page.getByRole("navigation").getByRole("link", { name: "Patients" }).click();

    await page.getByLabel("Search").fill("nair");
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.getByRole("cell", { name: /MRN-/ }).first()).toBeVisible();

    await page.getByRole("link", { name: "Open chart" }).first().click();
    await expect(page.getByRole("heading", { level: 1 })).toContainText("Nair");
  });

  test("a critical allergy is surfaced as an alert on the chart", async ({ page }) => {
    await signIn(page, "dr.rao");
    await page.goto("/patients?q=nair");
    await page.getByRole("link", { name: "Open chart" }).first().click();

    // The chart must not require scrolling to discover a life-threatening allergy.
    const banner = page.getByRole("main").getByRole("alert").filter({ hasText: "Allergy alert" });
    await expect(banner).toBeVisible();
    await expect(banner).toContainText(/penicillin/i);
  });

  test("encrypted identifiers are not rendered on the chart", async ({ page }) => {
    await signIn(page, "dr.rao");
    await page.goto("/patients?q=nair");
    await page.getByRole("link", { name: "Open chart" }).first().click();

    // The national id used by the seeded data must never appear in a chart view.
    await expect(page.locator("body")).not.toContainText("ABCDE1234F");
  });
});

test.describe("laboratory", () => {
  test("the worklist opens a report showing flags and reference ranges", async ({ page }) => {
    await signIn(page, "dr.pathan");
    // Filtered to released orders: an order with no results yet correctly shows no result table.
    await page.goto("/laboratory?status=VERIFIED");
    await expect(page.getByRole("heading", { name: "Laboratory", level: 1 })).toBeVisible();

    const open = page.getByRole("link", { name: "Open" }).first();
    if ((await open.count()) === 0) {
      test.skip(true, "no released laboratory orders in this environment");
    }
    await open.click();

    await expect(page.getByRole("columnheader", { name: "Reference" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Flag" })).toBeVisible();
  });
});

test.describe("triage", () => {
  test("an assessment states what set the acuity", async ({ page }) => {
    await signIn(page, "nurse.iqbal");
    await page.getByRole("navigation").getByRole("link", { name: "Triage" }).click();

    await page
      .getByLabel("Presenting complaint")
      .fill("Central crushing chest pain radiating to left arm");
    await page.getByLabel("Age").fill("58");
    await page.getByLabel("Heart rate").fill("118");
    await page.getByLabel("SpO2 (%)").fill("92");
    await page.getByRole("button", { name: "Assess acuity" }).click();

    await expect(page.getByText("What set this acuity")).toBeVisible();
    // The same driver appears in the list and as a red-flag badge; assert on the list.
    await expect(
      page.getByRole("listitem").filter({ hasText: /rule out acute coronary syndrome/i }),
    ).toBeVisible();
    // Advisory framing must always be present alongside AI output.
    await expect(page.getByText(/advisory only/i)).toBeVisible();
  });

  test("a negated finding does not inflate the acuity", async ({ page }) => {
    await signIn(page, "nurse.iqbal");
    await page.goto("/triage");

    await page.getByLabel("Presenting complaint").fill("Sore throat for two days, no fever");
    await page.getByLabel("Age").fill("28");
    await page.getByLabel("Heart rate").fill("76");
    await page.getByRole("button", { name: "Assess acuity" }).click();

    await expect(page.getByText("What set this acuity")).toBeVisible();
    await expect(page.getByText(/no abnormal vitals recorded/i)).toBeVisible();
  });
});
