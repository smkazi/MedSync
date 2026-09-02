import { expect, test } from "@playwright/test";
import { encounterFor } from "./chart";
import { signIn } from "./sign-in";

/**
 * The laboratory chain of custody, driven through the browser under four identities.
 *
 * <p>`global-setup.ts` already drives this sequence through the API, and says why it refuses to use
 * an admin token to do it: a fixture that routed around the separation of duties "would be quietly
 * testing a system nobody runs". The same argument applies to the screens, and until this spec
 * existed nothing checked them at all — the entire write layer was reachable only by curl.
 *
 * <p>So: `dr.rao` orders from a chart, `lab.tech` collects the tube and enters the numbers,
 * `dr.pathan` verifies — which is the release — and the report opens. Four sign-ins, because the
 * point is that no one of them can do another's part.
 */

/** Below the female interval (11.5 – 14.5 g/dL), so the report carries a real flag. */
const LOW_HAEMOGLOBIN = "9.6";

test.describe("the laboratory write layer", () => {
  test("a chart order becomes a released report, through four pairs of hands", async ({ page }) => {
    test.setTimeout(180_000);

    // ---- the clinician orders, from the open chart -------------------------
    await signIn(page, "dr.rao");
    const { url } = await encounterFor(page, 78);

    const orders = page.getByRole("region", { name: "Laboratory orders" });
    await expect(orders).toContainText("No tests ordered on this visit.");

    await orders.getByRole("checkbox", { name: /\(CBC\)$/ }).check();
    await orders
      .getByRole("textbox", { name: "Clinical details for the laboratory" })
      .fill("Fatigue, pallor. Please report the differential.");
    await orders.getByRole("button", { name: "Order tests" }).click();
    await expect(orders.getByRole("status")).toContainText("Ordered CBC.");

    // The order is now listed against this encounter and nowhere else — which is what the
    // encounter_id column added in laboratory V5 is for.
    await page.goto(url);
    const listed = page
      .getByRole("region", { name: "Laboratory orders" })
      .getByRole("row")
      .filter({ hasText: "1 test" });
    await expect(listed).toContainText("ORDERED");

    await listed.getByRole("link", { name: "Open" }).click();
    await expect(page.getByRole("heading", { name: /Complete Blood Count|CBC/ })).toBeVisible();
    const orderUrl = page.url();

    // A doctor is offered no results form and no verify button: neither endpoint is theirs.
    await expect(page.getByRole("region", { name: "Enter results" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /Verify and release/ })).toHaveCount(0);
    // Cancelling is theirs, while nothing has been recorded.
    await expect(page.getByRole("button", { name: "Cancel the order" })).toBeVisible();

    // ---- the technician collects, then enters what the bench produced ------
    await signIn(page, "lab.tech");
    await page.goto(orderUrl);

    // Symmetrically, a technician may not cancel a clinician's order.
    await expect(page.getByRole("button", { name: "Cancel the order" })).toHaveCount(0);

    await page.getByRole("combobox", { name: "Specimen type" }).selectOption("WHOLE_BLOOD");
    await page.getByRole("button", { name: "Collect", exact: true }).click();
    await expect(page.getByRole("status")).toContainText(/Collected\. Accession /);

    const results = page.getByRole("region", { name: "Enter results" });
    // Exact, because each row has two text boxes: the value, labelled by the parameter, and the
    // unit, whose aria-label is "<parameter> unit" so a screen reader does not read two anonymous
    // fields per row.
    await results
      .getByRole("textbox", { name: "Haemoglobin", exact: true })
      .fill(LOW_HAEMOGLOBIN);
    await results.getByRole("textbox", { name: "WBC Count", exact: true }).fill("7.4");
    await results.getByRole("button", { name: "Record results" }).click();
    // One of the two is outside the interval, and the screen says which count is which rather
    // than reporting a bare success.
    await expect(results.getByRole("status")).toContainText("1 outside the reference interval");

    await page.goto(orderUrl);
    const haemoglobin = page.getByRole("row").filter({ hasText: LOW_HAEMOGLOBIN }).first();
    await expect(haemoglobin).toContainText("11.5 - 14.5");
    await expect(haemoglobin).toContainText("low");

    // Entering is not releasing. A technician is told who releases, and offered no button.
    await expect(page.getByRole("region", { name: "Release" })).toContainText(
      "A pathologist verifies them",
    );
    await expect(page.getByRole("button", { name: /Verify and release/ })).toHaveCount(0);

    // ---- the pathologist verifies, which is the release --------------------
    await signIn(page, "dr.pathan");
    await page.goto(orderUrl);
    await page.getByRole("button", { name: /Verify and release/ }).click();
    // The platform's own wording, not a rewrite of it.
    await expect(page.getByRole("status")).toContainText(/result\(s\) verified and released/);

    await page.goto(orderUrl);
    // Three badges now read VERIFIED - the order and each of its two results - so the assertion
    // that says something is the release card, which stops offering a button and says what
    // happened instead.
    await expect(page.getByText("VERIFIED", { exact: true })).toHaveCount(3);
    await expect(page.getByRole("region", { name: "Release" })).toContainText("Released.");
    await expect(page.getByRole("button", { name: /Verify and release/ })).toHaveCount(0);
    // No longer watermarked provisional.
    await expect(page.getByRole("link", { name: "Report PDF" })).toBeVisible();

    // And it can no longer be cancelled by anybody, results having been recorded.
    await signIn(page, "dr.rao");
    await page.goto(orderUrl);
    await expect(page.getByRole("button", { name: "Cancel the order" })).toHaveCount(0);
  });

  test("an inverted reference interval is refused in the service's own words", async ({ page }) => {
    // The refusal names both numbers and what would happen, because "Bad Request" would leave a
    // pathologist to work out which bound they got wrong.
    await signIn(page, "dr.pathan");
    await page.goto("/laboratory/reference-ranges?q=HGB");

    const row = page.getByRole("row").filter({ hasText: "HGB" }).first();
    await row.getByText("Retune").click();
    await row.getByRole("spinbutton", { name: /^Low/ }).fill("40");
    await row.getByRole("spinbutton", { name: /^High/ }).fill("5");
    await row.getByRole("button", { name: "Save interval" }).click();

    await expect(row).toContainText("A reference interval cannot start above where it ends");
    await expect(row).toContainText("Every value would read as high");
  });

  test("a morphology cut-off is a pathologist's to retune and nobody else's", async ({ page }) => {
    await signIn(page, "lab.tech");
    await page.goto("/laboratory/interpretation");
    // The form is not rendered for a technician at all - and the endpoint refuses one anyway,
    // which the API suite asserts. Hiding it is the courtesy; the service is the control.
    await expect(page.getByRole("row").filter({ hasText: "MCV_MICROCYTIC" })).not.toContainText(
      "Retune",
    );

    await signIn(page, "dr.pathan");
    await page.goto("/laboratory/interpretation");
    const row = page.getByRole("row").filter({ hasText: "MCV_MICROCYTIC" }).first();
    await row.getByText("Retune").click();
    // The note is read-only: it appears verbatim on a signed report, so rewording it is a
    // different act from moving the number.
    await expect(row).not.toContainText("Cut-off note");
    await row.getByRole("spinbutton", { name: "Cut-off" }).fill("77");
    await row.getByRole("button", { name: "Save cut-off" }).click();
    await expect(row.getByRole("status")).toContainText("MCV_MICROCYTIC updated.");

    await page.goto("/laboratory/interpretation");
    await expect(page.getByRole("row").filter({ hasText: "MCV_MICROCYTIC" }).first()).toContainText(
      "77",
    );

    // Put it back: this is shared configuration, and a test that leaves a threshold moved has
    // changed what every later report says.
    const restored = page.getByRole("row").filter({ hasText: "MCV_MICROCYTIC" }).first();
    await restored.getByText("Retune").click();
    await restored.getByRole("spinbutton", { name: "Cut-off" }).fill("76");
    await restored.getByRole("button", { name: "Save cut-off" }).click();
    await expect(restored.getByRole("status")).toContainText("MCV_MICROCYTIC updated.");
  });
});
