import { expect, test } from "@playwright/test";
import { openMenu, signIn } from "./sign-in";

/**
 * The immunisation register from a browser.
 *
 * <p>Two things are asserted here that no service test can reach, and they are both about the
 * <em>shape</em> of the screen rather than about a status code.
 *
 * <p><strong>The two recording forms are two forms.</strong> A dose given here has a lot-number
 * field and no evidence box; a dose from a card has an evidence box and no lot-number field
 * anywhere. That is the endpoint split made visible, and it is the reason the register cannot be
 * filled with invented lot numbers: the field a person would type one into does not exist on the
 * form they are using when they do not have a vial.
 *
 * <p><strong>The calling list explains itself.</strong> Every due row carries the rule that put it
 * there, and a dose that did not count carries the rule that rejected it — because a dose silently
 * ignored is a dose the clinician will give again.
 */

test.describe("the immunisation register", () => {
  test("the calling list asks for a birth cohort, and says why it needs one", async ({ page }) => {
    await signIn(page, "nurse.iqbal");

    const menu = await openMenu(page, "Immunisation");
    await menu.getByRole("link", { name: "Calling list" }).click();
    await expect(page.getByRole("heading", { name: "Immunisation calling list" })).toBeVisible();

    // Nothing is fetched until a range is given: there is deliberately no "every overdue child in
    // the district" query, because due and overdue are computed on read from a date of birth.
    await expect(page.getByText(/Choose a birth range/)).toBeVisible();
    await expect(page.getByLabel("Born from")).toBeVisible();
    await expect(page.getByLabel("Born to")).toBeVisible();

    // "As at" is a real field, not decoration: every status is a statement about one day, and a
    // printed calling list with no date on it is a list nobody can check.
    await expect(page.getByLabel("As at")).toBeVisible();

    const today = new Date().toISOString().slice(0, 10);
    await page.getByLabel("Born from").fill("2023-01-01");
    await page.getByLabel("Born to").fill(today);
    await page.getByRole("button", { name: "Build the list" }).click();

    await expect(page.getByText("Children in the cohort")).toBeVisible();
    // Exact, because the field label above says the same words: the assertion is that the answer
    // carries the day it is about, not that the form has a box.
    await expect(page.getByText("As at", { exact: true }).last()).toBeVisible();
  });

  test("the two recording forms are two forms, and only one has a lot number", async ({ page }) => {
    await signIn(page, "admin");

    // The administrator is outside the care-relationship narrowing, so this reaches any register
    // without a break-glass reason — which is what makes it the right identity for this spec. The
    // narrowing itself is proven in the service suite and in tests/api.
    const today = new Date().toISOString().slice(0, 10);
    await page.goto(`/immunisations?bornFrom=1900-01-01&bornTo=${today}`);

    const register = page.getByRole("link", { name: "Register" }).first();
    const anyChildren = (await register.count()) > 0;
    if (!anyChildren) {
      // No registered patient fell in the range. Said out loud rather than silently passing: an
      // empty cohort is a fixture problem, and a spec that reported nothing would hide it.
      test.skip(true, "No patients in the cohort on this run, so there is no register to open.");
    }
    await register.click();

    await expect(page.getByRole("heading", { name: "Immunisation register" })).toBeVisible();

    const givenHere = page.getByRole("region", { name: "Record a dose given here" });
    const fromCard = page.getByRole("region", { name: "Record a dose given somewhere else" });

    await expect(givenHere).toBeVisible();
    await expect(fromCard).toBeVisible();

    // The lot number is on one form and on neither the other nor anywhere near it. This is the
    // assertion: the failure the split prevents is somebody typing a card dose in as if given here
    // with an invented lot, and the field to do that in is absent rather than optional.
    await expect(givenHere.getByLabel("Lot number")).toBeVisible();
    await expect(fromCard.getByLabel(/lot/i)).toHaveCount(0);

    // And the evidence box is on the other one, with the two grades of it offered as a choice —
    // "I am holding the card" and "the parent says so" are different facts.
    await expect(fromCard.getByLabel("What you saw")).toBeVisible();
    await expect(fromCard.getByLabel("Evidence held")).toBeVisible();
    await expect(givenHere.getByLabel(/what you saw/i)).toHaveCount(0);
  });

  test("the schedule is in days from birth, and says so", async ({ page }) => {
    await signIn(page, "nurse.iqbal");
    await page.goto("/immunisations/schedules");

    await expect(page.getByRole("heading", { name: "Published schedule" })).toBeVisible();
    // The decision on this screen: days, never months, because "2 months" is 59, 60 or 62 days and
    // a due list four days wrong is wrong for every child in the district.
    await expect(page.getByText(/never months/)).toBeVisible();
    await expect(page.getByRole("table").first()).toContainText("Min interval");
    await expect(page.getByRole("table").first()).toContainText("first dose");
  });

  test("a product's contents list is shown and is not editable", async ({ page }) => {
    await signIn(page, "admin");
    await page.goto("/immunisations/vaccines");

    await expect(page.getByRole("heading", { name: "Vaccines" })).toBeVisible();
    // Both tables, joined: coverage is asked about antigens and a recall names a lot of a product.
    await expect(page.getByRole("region", { name: "Products" })).toBeVisible();
    await expect(page.getByRole("region", { name: "Antigens" })).toBeVisible();
    // No form, and the reason is on the screen: a child recorded as having had a combination
    // product had whatever that product contained on the day.
    await expect(page.getByRole("button", { name: /save|add|update/i })).toHaveCount(0);
    await expect(page.getByText(/reformulated vaccine is a new code/i)).toBeVisible();
  });

  test("the vial monitor is a number and not a green tick", async ({ page }) => {
    await signIn(page, "admin");
    await page.goto("/immunisations/lots");

    await expect(page.getByRole("heading", { name: "Vaccine stock" })).toBeVisible();
    // Recorded and enforced by nothing, stated rather than dressed up: nothing on this platform
    // knows what a fridge did overnight, and a status somebody trusted would be last month's
    // reading.
    // .first(), because the screen says it twice on purpose — once in the prose above and once on
    // the field itself, where somebody about to type a number will actually read it.
    await expect(page.getByText(/enforced by nothing/i).first()).toBeVisible();
    await expect(page.getByRole("region", { name: "Receive a lot" })).toBeVisible();
  });

  test("a cashier is offered no part of the register", async ({ page }) => {
    await signIn(page, "cashier");

    // The mirror of the pharmacist: a cashier can take money and cannot open a chart, and a
    // lifetime vaccination timeline is a chart.
    await expect(page.getByRole("button", { name: "Immunisation", exact: true })).toHaveCount(0);
  });
});
