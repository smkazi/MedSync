import { expect, test } from "@playwright/test";
import { FIXTURE_DATE_OF_BIRTH, FIXTURE_SURNAME } from "./global-setup";
import { signIn } from "./sign-in";

/**
 * Registering a patient — the platform's first write from a browser.
 *
 * <p>Everything the UI had done until now was a read; every write in this repository had been
 * verified with `curl` or from a service test. So this spec is not only about registration: it pins
 * the shape every later form copies. Three behaviours, and the middle one is the reason the screen
 * is more than a POST.
 */

/** A surname nothing else in the suite queries, so a successful run cannot disturb another test. */
function uniqueSurname(): string {
  return `Testcase${Date.now().toString().slice(-8)}`;
}

test.describe("registering a patient", () => {
  test("a valid registration is created and the platform issues the MRN", async ({ page }) => {
    await signIn(page, "reception");
    await page.goto("/patients/new");

    const surname = uniqueSurname();
    await page.getByLabel("First name").fill("Asha");
    await page.getByLabel("Surname").fill(surname);
    await page.getByLabel("Date of birth").fill("1991-07-02");
    await page.getByLabel("Sex").selectOption("FEMALE");
    await page.getByRole("button", { name: "Register patient" }).click();

    // Lands on the new chart, and the MRN came back from the service rather than from this form.
    await expect(page.getByRole("status")).toContainText(/Registered\. MRN MRN-/);
    await expect(page.getByRole("heading", { level: 1 })).toContainText(surname);

    // And it is findable, which is the only proof that the write actually persisted.
    await page.goto(`/patients?q=${surname}`);
    await expect(page.getByRole("cell", { name: new RegExp(surname) })).toBeVisible();
  });

  test("a suspected duplicate is a question, not a failure", async ({ page }) => {
    await signIn(page, "reception");
    await page.goto("/patients/new");

    // Same surname and date of birth as the fixture patient: exactly what the service flags.
    await page.getByLabel("First name").fill("Meera");
    await page.getByLabel("Surname").fill(FIXTURE_SURNAME);
    await page.getByLabel("Date of birth").fill(FIXTURE_DATE_OF_BIRTH);
    await page.getByLabel("Sex").selectOption("FEMALE");
    await page.getByRole("button", { name: "Register patient" }).click();

    const main = page.getByRole("main");
    await expect(main).toContainText(/look(s)? like the same person/i);
    // The candidate chart is offered as a link, so the front desk can go and look rather than
    // being told only that something conflicts.
    await expect(main.getByRole("link", { name: new RegExp(FIXTURE_SURNAME) })).toBeVisible();
    await expect(main).toContainText(/MRN-/);

    // Overriding is available and deliberate — a second button, never a pre-set hidden field.
    await expect(main.getByRole("button", { name: /register anyway/i })).toBeVisible();

    // Nothing was created, and nothing typed was lost.
    await expect(page.getByLabel("First name")).toHaveValue("Meera");
    await expect(page.getByLabel("Date of birth")).toHaveValue(FIXTURE_DATE_OF_BIRTH);
    await expect(page).toHaveURL(/\/patients\/new/);
  });

  test("the service's own validation is what the field errors say", async ({ page }) => {
    await signIn(page, "reception");
    await page.goto("/patients/new");

    await page.getByLabel("First name").fill("Ravi");
    await page.getByLabel("Surname").fill(uniqueSurname());
    // A date the browser has no opinion about and the platform must refuse. Every other field is
    // left valid on purpose: a malformed email would be blocked by the input's own type before the
    // form ever posted, and this test would then be asserting Chromium's behaviour, not MedSync's.
    await page.getByLabel("Date of birth").fill("2099-01-01");
    await page.getByLabel("Sex").selectOption("MALE");
    await page.getByRole("button", { name: "Register patient" }).click();

    await expect(page.getByLabel("Date of birth")).toHaveAttribute("aria-invalid", "true");
    // Verbatim from the service's own @Past message, not a message this form invented. One copy of
    // the rule; a second in TypeScript would drift and then disagree with the platform.
    await expect(page.getByRole("main")).toContainText(/date of birth must be in the past/i);
    // Refused, and still on the form with what was typed intact.
    await expect(page).toHaveURL(/\/patients\/new/);
    await expect(page.getByLabel("First name")).toHaveValue("Ravi");
  });

  test("a role without registration rights is not offered the screen", async ({ page }) => {
    await signIn(page, "lab.tech");
    // The menu does not show it, and the route itself sends them back rather than rendering a form
    // the platform would refuse.
    await page.goto("/patients/new");
    await expect(page).toHaveURL(/\/patients$/);
  });
});
