import { expect, test } from "@playwright/test";
import { FIXTURE_SURNAME } from "./global-setup";
import { signIn } from "./sign-in";

/**
 * Outbound messaging from a browser.
 *
 * <p>The assertion that matters is the one about what a message does <em>not</em> say. It is worth
 * making here as well as in the service's own suite because this is the screen a person uses: if
 * the platform's rule is that no message carries clinical information, then the surface where
 * somebody would try to work around it is the one to check.
 */

test.describe("outbound messaging", () => {
  test("a message says something is ready and never what it says", async ({ page }) => {
    await signIn(page, "dr.rao");
    await page.goto(`/messaging?mrn=${FIXTURE_SURNAME}`);

    const form = page.getByRole("region", { name: "Tell a patient something" });

    // There is no message box anywhere on the screen. That is the design rather than an omission,
    // and it is the whole reason the PHI rule is a property rather than a hope.
    await expect(form.getByRole("textbox", { name: /message|body|text/i })).toHaveCount(0);

    await form.getByRole("combobox", { name: "Patient" }).selectOption({ index: 1 });
    await form.getByRole("combobox", { name: "What it is about" })
      .selectOption("LAB_REPORT_READY");
    await form.getByRole("button", { name: "Send" }).click();

    await expect(form.getByRole("status")).toContainText(/Sent on|Not sent/);

    await page.goto("/messaging");
    const row = page.getByRole("row").filter({ hasText: "LAB_REPORT_READY" }).first();
    await expect(row).toContainText("ready");
    // Nothing about the patient, and nothing about the report.
    await expect(row).not.toContainText(FIXTURE_SURNAME);
    await expect(row).not.toContainText("MRN-");
    await expect(row).not.toContainText("9.8");
  });

  test("the delivery log says plainly when nothing was sent", async ({ page }) => {
    await signIn(page, "admin");
    await page.goto("/messaging");

    // "Not sent" is a real outcome, not a failure, and the screen is required to say so — a
    // message that silently never existed leaves the front desk believing the patient was told.
    await expect(page.getByRole("region", { name: /Delivery log/ })).toContainText(
      "evidence behind it",
    );
  });

  test("the screen says which channels the deployment really has", async ({ page }) => {
    await signIn(page, "dr.rao");
    await page.goto("/messaging");

    // A channel that is not configured falls back to the log rather than failing, which is right
    // at runtime and misleading in a UI unless the UI says so.
    const capabilities = page.getByRole("region", {
      name: "What this deployment can send with",
    });
    await expect(capabilities).toContainText("LOG");
  });

  test("the wording is a pathologist's to read and an administrator's to change", async ({
    page,
  }) => {
    await signIn(page, "dr.rao");
    await page.goto("/messaging/templates");
    await expect(page.getByRole("heading", { name: "Message wording" })).toBeVisible();
    // Exact: the page's own closing paragraph says "Rewording these is restricted to an
    // administrator", and getByText matches substrings, so a loose locator finds the prose that
    // exists precisely because the control does not.
    await expect(page.getByText("Reword", { exact: true })).toHaveCount(0);

    await signIn(page, "admin");
    await page.goto("/messaging/templates");

    const row = page.getByRole("row").filter({ hasText: "LAB_REPORT_READY" }).first();
    await row.getByText("Reword", { exact: true }).click();
    await row
      .getByRole("textbox", { name: "Body" })
      .fill("Your haemoglobin of {value} is ready. {portalUrl}");
    await row.getByRole("button", { name: "Save wording" }).click();

    // The service's own words, verbatim: it names the closed set and says why it is closed.
    await expect(row).toContainText("{value}");
    await expect(row).toContainText("never says what it says");
  });

  test("the laboratory is not offered messaging at all", async ({ page }) => {
    await signIn(page, "lab.tech");
    // Releasing a report triggers a message through the event; the bench does not originate one,
    // and an item filtered out on the server is never serialised into the page.
    await expect(page.getByRole("button", { name: "Messaging", exact: true })).toHaveCount(0);
  });
});
