import { expect, test } from "@playwright/test";
import { openMenu, signIn } from "./sign-in";

/**
 * Public health from a browser: the counts, the names, and the gate between them.
 *
 * <p>The assertion worth making here rather than only against the API is the one about what the
 * epidemiologist's browser <em>does not offer</em>. The service refuses the line list to that role
 * and the abuse suite proves it; what this spec proves is that the account is never shown a link to
 * a screen the platform would refuse, because a menu offering a refusal is a menu that teaches
 * people the platform is broken.
 *
 * <p>The download is deliberately not clicked. It is a write — it registers a disclosure against
 * every patient the list names, in the register a patient reads — and a browser suite that fired it
 * on every run would fill that register with rows about a test. What is asserted instead is that the
 * screen says so before anybody presses it, and the API journey exercises the register-first
 * ordering against the real register.
 */

test.describe("public health", () => {
  test("the epidemiologist reads the counts and is not offered the names", async ({ page }) => {
    await signIn(page, "epidemiologist");

    const menu = await openMenu(page, "Public health");
    await expect(menu.getByRole("link", { name: "Notifiable return" })).toBeVisible();
    await expect(menu.getByRole("link", { name: "Quality measures" })).toBeVisible();
    // The one that must not be there. This is the browser half of the platform's narrowest safety
    // argument: the property that lets this role hold the whole surveillance module without
    // holding a chart is that it reads only aggregates.
    await expect(menu.getByRole("link", { name: "Line list" })).toHaveCount(0);

    await page.goto("/public-health");
    await expect(page.getByRole("heading", { name: "Notifiable-disease return" })).toBeVisible();

    // Every configured condition, zeroes included: a return that omitted them would render "no
    // cholera this fortnight" and "cholera is not on our list" identically.
    const table = page.getByRole("table");
    await expect(table.getByRole("row")).not.toHaveCount(1);
    await expect(table).toContainText("Measles");
    await expect(table).toContainText("Cholera");

    // And nothing identifying anybody, because the query behind it selects no identifier.
    const body = await page.locator("main").innerText();
    expect(body).not.toMatch(/MRN-\d/);
  });

  test("the epidemiologist typing the line list URL is refused, not shown an empty page", async ({
    page,
  }) => {
    await signIn(page, "epidemiologist");
    await page.goto("/public-health/line-list");

    // The menu hides it; the platform refuses it. Both are asserted, because the first is a
    // convenience and only the second is a control.
    await expect(page.getByRole("heading", { name: "Notifiable line list" })).toBeVisible();
    // Filtered, because Next renders an empty live region with role="alert" on every navigation.
    await expect(
      page.getByRole("alert").filter({ hasText: /permission|Forbidden/i }),
    ).toBeVisible();
    const body = await page.locator("main").innerText();
    expect(body).not.toMatch(/MRN-\d/);
  });

  test("the administrator sees the names, and is told what downloading them does", async ({
    page,
  }) => {
    await signIn(page, "admin");

    const menu = await openMenu(page, "Public health");
    await menu.getByRole("link", { name: "Line list" }).click();
    await expect(page.getByRole("heading", { name: "Notifiable line list" })).toBeVisible();

    // The warning is above the button and says the two things an operator has to know before
    // pressing it: that this page has notified nobody, and that the file is what does.
    const warning = page.getByRole("region", { name: "Notify the authority" });
    await expect(warning).toContainText("look");
    await expect(warning).toContainText("not notified");
    await expect(warning).toContainText(/disclosure per patient|one disclosure/i);

    // The recipient is configuration and is shown rather than typed: a recipient a form could set
    // is a column that could make an unlawful disclosure read as a statutory one.
    await expect(page.getByText("Would go to")).toBeVisible();
    await expect(page.getByRole("textbox", { name: /recipient/i })).toHaveCount(0);
  });

  test("a doctor is offered neither half of the return", async ({ page }) => {
    await signIn(page, "dr.rao");

    // The Public health menu exists for a clinician — a clinic may read its own coverage rate —
    // and the notifiable return is not in it. A statutory filing about a district is not a
    // clinician's work, and the doctor who diagnosed a case already knows about it.
    const menu = await openMenu(page, "Public health");
    await expect(menu.getByRole("link", { name: "Quality measures" })).toBeVisible();
    await expect(menu.getByRole("link", { name: "Notifiable return" })).toHaveCount(0);
    await expect(menu.getByRole("link", { name: "Line list" })).toHaveCount(0);
  });

  test("a coverage rate names no child, and says what it was computed against", async ({ page }) => {
    await signIn(page, "admin");
    await page.goto("/public-health/measures?code=CIS-2&periodFrom=2024-01-01&periodTo=2024-12-31");

    await expect(page.getByRole("heading", { name: "Clinical quality measures" })).toBeVisible();
    await expect(page.getByText("Coverage", { exact: true })).toBeVisible();

    // The specification's own sentences, and the version they came from. A rate published without
    // saying which specification produced it is a number nobody downstream can check.
    const computed = page.getByRole("region", { name: "What was computed" });
    await expect(computed).toContainText("Specification");
    await expect(computed).toContainText(/not cached/i);

    const body = await page.locator("main").innerText();
    expect(body).not.toMatch(/MRN-\d/);
  });
});
