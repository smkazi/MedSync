import { expect, test } from "@playwright/test";
import { encounterFor } from "./chart";
import { signIn } from "./sign-in";

/**
 * Order sets and care plans on the chart.
 *
 * <p>Two things are worth driving through a browser rather than only through the API. Applying an
 * order set is one click that reaches two other services, so what the screen shows *before* the
 * click — every line, with its dose — is the safety property: a set applied without being read is
 * a set nobody checked. And a care plan is refused a close while a goal is open, which is a rule
 * whose whole purpose is to make a person decide, so it is worth seeing a person meet it.
 */

const STAMP = Date.now().toString(36).toUpperCase();

test.describe("order sets", () => {
  test("every line is shown with its dose before anything is raised", async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, "dr.rao");
    const { url } = await encounterFor(page, 18);
    await page.goto(url);

    const sets = page.getByRole("region", { name: "Order sets" });
    await expect(sets).toBeVisible();

    // The fever set: two tests and a medicine, and the medicine's dose is on the screen. A set
    // that showed only its name would be a set applied in one click by somebody who could not have
    // known what was in it.
    const fever = sets.locator("div").filter({ hasText: "Fever, first line" }).first();
    await expect(fever).toContainText("CBC5");
    await expect(fever).toContainText("ESR");
    await expect(fever).toContainText("PARA500");
    await expect(fever).toContainText("four times daily");
    await expect(fever).toContainText("12 to dispense");
  });

  test("applying a set raises the tests and the medicines, and says what it did", async ({
    page,
  }) => {
    test.setTimeout(120_000);
    await signIn(page, "dr.rao");
    const { url } = await encounterFor(page, 25);
    await page.goto(url);

    const sets = page.getByRole("region", { name: "Order sets" });
    await sets.locator("form").filter({ has: page.locator('input[value="FEVER1"]') })
      .getByRole("button", { name: "Apply" }).click();

    // The service's own sentence: what was raised, in both services.
    await expect(page.getByRole("status")).toContainText("Fever, first line applied");
    await expect(page.getByRole("status")).toContainText("test(s)");
    await expect(page.getByRole("status")).toContainText("medicine(s)");

    // And both are on the chart, in the cards that own them.
    await expect(page.getByRole("region", { name: "Laboratory orders" })).toContainText("2 tests");
    await expect(page.getByRole("region", { name: "Medicines" })).toContainText("Paracetamol");
  });

  test("a laboratory-only set raises no medicine and offers no reason box", async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, "nurse.iqbal");
    const { url } = await encounterFor(page, 32);
    await page.goto(url);

    // A nurse may apply this one: it prescribes nothing, and the pharmacy is never asked. The
    // reason field is offered only for a set that can raise a warning, because a field that is
    // never needed is a field somebody fills in anyway.
    const sets = page.getByRole("region", { name: "Order sets" });
    const anaemia = sets.locator("form").filter({ has: page.locator('input[value="ANAEMIA"]') });
    await expect(anaemia.getByRole("textbox")).toHaveCount(0);
    await anaemia.getByRole("button", { name: "Apply" }).click();

    await expect(page.getByRole("status")).toContainText("Anaemia screen applied");
    await expect(page.getByRole("status")).not.toContainText("medicine");
  });
});

test.describe("care plans", () => {
  test("a plan will not close while a goal is open, and the refusal says how many", async ({
    page,
  }) => {
    test.setTimeout(150_000);
    await signIn(page, "dr.rao");
    const { url } = await encounterFor(page, 39);
    await page.goto(url);

    const plan = page.getByRole("region", { name: "Care plan" });
    await plan.getByRole("textbox", { name: "What is this episode trying to achieve?" })
      .fill(`Recovery plan ${STAMP}`);
    await plan.getByRole("button", { name: "Start a plan" }).click();
    await expect(page.getByRole("status")).toContainText("Care plan started");

    const afterStart = page.getByRole("region", { name: "Care plan" });
    await afterStart.getByRole("textbox", { name: "Goal" }).fill("Walking to the bathroom");
    await afterStart.getByRole("button", { name: "Add", exact: true }).click();
    await expect(page.getByRole("status")).toContainText("Goal added");

    // Closing with an open goal is refused, and the refusal counts them: this rule exists to make
    // somebody decide rather than letting an unfinished goal vanish at discharge.
    await page.getByRole("region", { name: "Care plan" })
      .getByRole("button", { name: "Close the plan" }).click();
    // Scoped to main: Next's route announcer is also role="alert", which makes an
    // unscoped locator ambiguous the moment a navigation has happened.
    await expect(page.getByRole("main").getByRole("alert")).toContainText("still open");

    const row = page.getByRole("row").filter({ hasText: "Walking to the bathroom" });
    await row.getByRole("combobox").selectOption("MET");
    await row.getByRole("button", { name: "Record" }).click();
    await expect(page.getByRole("status")).toContainText("Goal recorded as met");

    await page.getByRole("region", { name: "Care plan" })
      .getByRole("button", { name: "Close the plan" }).click();
    await expect(page.getByRole("status")).toContainText("Care plan completed");
  });

  test("an outcome other than met needs a note, in the service's own words", async ({ page }) => {
    test.setTimeout(150_000);
    await signIn(page, "dr.rao");
    const { url } = await encounterFor(page, 46);
    await page.goto(url);

    const plan = page.getByRole("region", { name: "Care plan" });
    await plan.getByRole("textbox", { name: "What is this episode trying to achieve?" })
      .fill(`Plan ${STAMP}`);
    await plan.getByRole("button", { name: "Start a plan" }).click();
    await page.getByRole("region", { name: "Care plan" })
      .getByRole("textbox", { name: "Goal" }).fill("Off oxygen");
    await page.getByRole("region", { name: "Care plan" })
      .getByRole("button", { name: "Add", exact: true }).click();
    await expect(page.getByRole("status")).toContainText("Goal added");

    const row = page.getByRole("row").filter({ hasText: "Off oxygen" });
    await row.getByRole("combobox").selectOption("ABANDONED");
    await row.getByRole("button", { name: "Record" }).click();
    await expect(page.getByRole("main").getByRole("alert")).toContainText("needs a note");

    const again = page.getByRole("row").filter({ hasText: "Off oxygen" });
    await again.getByRole("combobox").selectOption("ABANDONED");
    await again.getByRole("textbox").fill("Home oxygen arranged instead");
    await again.getByRole("button", { name: "Record" }).click();
    await expect(page.getByRole("status")).toContainText("abandoned");
  });

  test("a goal can only be filed under a diagnosis this visit made", async ({ page }) => {
    test.setTimeout(150_000);
    await signIn(page, "dr.rao");
    const { url } = await encounterFor(page, 53);
    await page.goto(url);

    // No diagnosis yet, so the problem picker offers nothing but "none". A free-text code box
    // would be offering a refusal.
    const problems = page.getByRole("region", { name: "Care plan" });
    await problems.getByRole("textbox", { name: "What is this episode trying to achieve?" })
      .fill(`Plan ${STAMP}`);
    await problems.getByRole("button", { name: "Start a plan" }).click();

    const picker = page.getByRole("combobox", { name: "Problem" });
    await expect(picker.locator("option")).toHaveCount(1);

    await page.getByRole("textbox", { name: /^ICD-10/ }).fill("J06.9");
    await page.getByRole("textbox", { name: /^Description/ }).fill("Acute upper respiratory infection");
    await page.getByRole("button", { name: "Add diagnosis" }).click();
    await expect(page.getByRole("status")).toContainText("Added J06.9");

    await expect(page.getByRole("combobox", { name: "Problem" }).locator("option"))
      .toHaveCount(2);
  });
});
