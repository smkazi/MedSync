import { expect, test } from "@playwright/test";
import {
  FIXTURE_HAEMOGLOBIN_RANGE,
  FIXTURE_LOW_HAEMOGLOBIN,
  FIXTURE_SURNAME,
} from "./global-setup";
import { fixtureMrn } from "./chart";
import { openMenu, signIn } from "./sign-in";

/**
 * The journeys a clinician actually performs, driven through the browser.
 *
 * Sign-in and menu navigation live in `sign-in.ts`, shared with the navigation spec; credentials
 * come from the environment there so nothing here hard-codes a password.
 */

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

test.describe("patients", () => {
  test("search finds a patient and opens their chart", async ({ page }) => {
    await signIn(page, "reception");
    await (await openMenu(page, "Patients")).getByRole("link", { name: "Patient register" }).click();

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
    // Filtered to released orders *for this suite's own patient*. Released alone was not enough:
    // every other suite that drives a report to release adds a row to this worklist, and the first
    // one on it is whichever ran most recently — so this test opened a stranger's report and
    // failed on a haemoglobin it had never written. Green for months and then not, with nothing in
    // the change that broke it to point at.
    const mrn = await fixtureMrn(page);
    await page.goto(`/laboratory?status=VERIFIED&mrn=${encodeURIComponent(mrn)}`);
    await expect(page.getByRole("heading", { name: "Laboratory", level: 1 })).toBeVisible();

    // No conditional skip. globalSetup drives one CBC order through to a released report, so an
    // empty worklist here is a real failure and has to read as one - this test used to skip itself
    // in CI on every run, which is indistinguishable from not having written it.
    await page.getByRole("link", { name: "Open" }).first().click();

    await expect(page.getByRole("columnheader", { name: "Reference" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Flag" })).toBeVisible();

    // A number without its interval is not interpretable, and an out-of-range number that is not
    // marked is worse than no result at all. The fixture's haemoglobin sits below the female
    // interval, so both must appear on its row.
    const haemoglobin = page.getByRole("row").filter({ hasText: "Haemoglobin" });
    await expect(haemoglobin).toContainText(FIXTURE_LOW_HAEMOGLOBIN);
    await expect(haemoglobin).toContainText(FIXTURE_HAEMOGLOBIN_RANGE);
    await expect(haemoglobin).toContainText("low");

    // And the report says so at the top, where a clinician scanning a list of reports sees it.
    await expect(page.getByText("abnormal results")).toBeVisible();
  });

  test("scanning a tube barcode opens the order it belongs to", async ({ page }) => {
    await signIn(page, "lab.tech");
    await page.goto("/laboratory?status=VERIFIED");

    // Read the accession off the report the fixture released, then use it the way a bench scanner
    // would: type it into the scan box and press Enter.
    await page.getByRole("link", { name: "Open" }).first().click();
    const accession = (await page.getByText(/accession L\d{4}-\d{6}/).innerText())
      .replace(/^.*accession /, "")
      .trim();
    expect(accession).toMatch(/^L\d{4}-\d{6}$/);

    await page.goto("/laboratory");
    await page.getByLabel("Scan a tube").fill(accession);
    await page.getByRole("button", { name: "Open" }).click();

    // Landed on the right report, not on a search results page.
    await expect(page.getByRole("columnheader", { name: "Reference" })).toBeVisible();
    await expect(page.getByText(accession)).toBeVisible();
  });

  test("an unrecognised label stops rather than silently returning nothing", async ({ page }) => {
    await signIn(page, "lab.tech");
    await page.goto("/laboratory");

    await page.getByLabel("Scan a tube").fill("L2026-999999");
    await page.getByRole("button", { name: "Open" }).click();

    // A tube whose label does not resolve is an incident. Bouncing back to the worklist would look
    // like a scan that worked and found nothing.
    await expect(page.getByRole("heading", { name: "Scan not recognised" })).toBeVisible();
    await expect(page.getByText("L2026-999999").first()).toBeVisible();
  });

  test("the released report downloads as a real PDF", async ({ page }) => {
    await signIn(page, "dr.pathan");
    await page.goto("/laboratory?status=VERIFIED");
    await page.getByRole("link", { name: "Open" }).first().click();

    // page.request shares the browser context's cookies, so this exercises the same authenticated
    // path the link does - the token is httpOnly and never reaches client script.
    const href = await page.getByRole("link", { name: "Report PDF" }).getAttribute("href");
    expect(href).toBeTruthy();
    const response = await page.request.get(href!);

    expect(response.status()).toBe(200);
    expect(response.headers()["content-type"]).toContain("application/pdf");
    // Patient data: must not sit in a shared cache.
    expect(response.headers()["cache-control"]).toContain("no-store");

    const body = await response.body();
    expect(body.subarray(0, 5).toString("latin1")).toBe("%PDF-");
    expect(body.length).toBeGreaterThan(1000);
  });

  test("the report route serves nothing without a session", async ({ page, baseURL }) => {
    // Deliberately not signed in. The bearer token lives in an httpOnly cookie, so an
    // unauthenticated request has no authority at all - and must be turned away rather than
    // reaching the gateway.
    const response = await page.request.get(new URL("/laboratory/any-id/report", baseURL).toString(), {
      maxRedirects: 0,
    });
    expect(response.status()).toBe(307);
    expect(response.headers()["location"]).toContain("/login");
  });

  test("the label sheet renders a barcode carrying no patient identity", async ({ page }) => {
    await signIn(page, "lab.tech");
    await page.goto("/laboratory?status=VERIFIED");
    await page.getByRole("link", { name: "Open" }).first().click();
    await page.getByRole("link", { name: "Print labels" }).click();

    await expect(page.getByRole("heading", { name: "Specimen labels" })).toBeVisible();
    // The barcode is inlined SVG, so the bars are real elements on the page.
    const bars = page.locator("svg rect[fill='#000000']");
    await expect(bars.first()).toBeVisible();
    expect(await bars.count()).toBeGreaterThan(20);

    // A tube label is handled by couriers and seen in shared collection rooms.
    await expect(page.locator("figure")).not.toContainText(FIXTURE_SURNAME);
  });
});

test.describe("triage", () => {
  test("an assessment states what set the acuity", async ({ page }) => {
    await signIn(page, "nurse.iqbal");
    await (await openMenu(page, "Clinical")).getByRole("link", { name: "Triage" }).click();

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
