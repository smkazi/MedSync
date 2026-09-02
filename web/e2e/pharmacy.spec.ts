import { expect, test } from "@playwright/test";
import { encounterFor, fixtureMrn } from "./chart";
import { signIn } from "./sign-in";

/**
 * The closed medication loop, through the browser, under three identities.
 *
 * <p>Three identities is the point rather than an accident of the fixture: a prescriber writes the
 * order, the pharmacy fills it, a nurse gives the dose. No account on this platform can do more
 * than one of the three, and a spec that drove the whole loop as an administrator would be quietly
 * testing a system nobody runs — the same reasoning that made the laboratory spec use four.
 *
 * <p>The fixture patient has a life-threatening penicillin allergy recorded, which makes the first
 * assertion here a real one: amoxicillin is refused for a patient whose chart says penicillin, on
 * the strength of an ingredient list rather than a name match.
 */

const STAMP = Date.now().toString(36).toUpperCase();

test.describe("the medication loop", () => {
  test("an allergy recorded as a class refuses a member of that class", async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, "dr.rao");
    const { url } = await encounterFor(page, 4);
    await page.goto(url);

    const medicines = page.getByRole("region", { name: "Medicines" });
    await medicines.getByRole("combobox", { name: "Medicine" })
      .selectOption({ label: "Amoxicillin 500 mg capsule" });
    await medicines.getByRole("textbox", { name: /^Dose/ }).fill("1 capsule");
    await medicines.getByRole("textbox", { name: /^Frequency/ }).fill("three times daily");
    await medicines.getByRole("spinbutton", { name: /^For \(days\)/ }).fill("7");
    await medicines.getByRole("spinbutton", { name: /^Quantity to dispense/ }).fill("21");
    await medicines.getByRole("button", { name: "Prescribe" }).click();

    // The service's own sentence, verbatim: what it found, and what it matched on. A generic
    // "conflict" here is how a clinician learns to work around a safety check.
    await expect(medicines.getByRole("alert")).toContainText("cannot be written");
    await expect(medicines.getByRole("alert")).toContainText("Penicillin");
    await expect(medicines.getByRole("alert")).toContainText("PENICILLIN");
  });

  test("prescribe, dispense, then give the dose — each by the role that owns it", async ({
    page,
  }) => {
    test.setTimeout(180_000);
    await signIn(page, "dr.rao");
    const { url, mrn } = await encounterFor(page, 11);
    await page.goto(url);

    const instructions = `Take after food ${STAMP}`;
    const medicines = page.getByRole("region", { name: "Medicines" });
    await medicines.getByRole("combobox", { name: "Medicine" })
      .selectOption({ label: "Paracetamol 500 mg tablet" });
    await medicines.getByRole("textbox", { name: /^Dose/ }).fill("1 tablet");
    await medicines.getByRole("textbox", { name: /^Frequency/ }).fill("four times daily");
    await medicines.getByRole("spinbutton", { name: /^For \(days\)/ }).fill("3");
    await medicines.getByRole("spinbutton", { name: /^Quantity to dispense/ }).fill("12");
    await medicines.getByRole("textbox", { name: /^Instructions/ }).fill(instructions);
    await medicines.getByRole("button", { name: "Prescribe" }).click();
    await expect(medicines.getByRole("status")).toContainText("Prescribed");

    // The pharmacy fills it. A different identity, because dispensing is not something the
    // prescriber can do.
    await signIn(page, "pharmacist");
    await page.goto("/pharmacy/stock");
    const batch = `B-${STAMP}`;
    const stockForm = page.getByRole("region", { name: "Receive a delivery" });
    await stockForm.getByRole("combobox", { name: "Medicine" })
      .selectOption({ label: "Paracetamol 500 mg tablet" });
    await stockForm.getByRole("textbox", { name: /^Batch number/ }).fill(batch);
    await stockForm.getByLabel(/^Expires on/).fill(nextYear());
    await stockForm.getByRole("spinbutton", { name: /^Quantity/ }).fill("500");
    await stockForm.getByRole("button", { name: "Receive into stock" }).click();
    await expect(stockForm.getByRole("status")).toContainText(batch);

    await page.goto("/pharmacy");
    const line = page.getByRole("row").filter({ hasText: "Paracetamol" }).first();
    await expect(line).toBeVisible();
    await line.getByRole("button", { name: "Dispense" }).click();
    await expect(page.getByRole("status")).toContainText(/Dispensed \d+ from batch/);

    // And the ward gives it. A third identity: dispensing hands the medicine over, administering
    // puts it into a patient.
    await signIn(page, "nurse.iqbal");
    const patientId = await patientIdFor(page, mrn);
    await page.goto(`/emar?patientId=${patientId}`);

    const round = page.getByRole("region", { name: new RegExp(mrn) }).first();
    await expect(round).toBeVisible();
    const wristband = round.getByRole("textbox", { name: /^Wristband/ }).first();
    const label = round.getByRole("textbox", { name: /^Medicine label/ }).first();

    // The wrong wristband first, because the refusal is the assertion that matters: a right
    // medicine given to the wrong patient is the error the whole loop exists to catch.
    await wristband.fill("MRN-0000-000000");
    await label.fill("PARA500");
    await round.getByRole("button", { name: "Record as given" }).first().click();
    await expect(round.getByRole("alert").first()).toContainText("Do not give this dose");

    await round.getByRole("textbox", { name: /^Wristband/ }).first().fill(mrn);
    await round.getByRole("textbox", { name: /^Medicine label/ }).first().fill("PARA500");
    await round.getByRole("button", { name: "Record as given" }).first().click();
    await expect(round.getByRole("status").first()).toContainText("Dose recorded as given");
  });

  test("the interaction table says what to do, not just that there is a problem", async ({
    page,
  }) => {
    await signIn(page, "pharmacist");
    await page.goto("/pharmacy/interactions");

    const row = page.getByRole("row").filter({ hasText: "SIMVASTATIN" }).first();
    await expect(row).toContainText("contraindicated");
    // The management column. "These interact" gets dismissed; this does not.
    await expect(row).toContainText("Do not co-prescribe");
  });

  test("a pharmacist can fill a prescription and cannot open a chart", async ({ page }) => {
    // The narrowing the PHARMACIST role exists for, asserted at the route rather than in the menu:
    // a hidden link is a convenience and the refusal is the control.
    await signIn(page, "pharmacist");
    await page.goto("/patients");
    // Whichever way the refusal is worded — the screen's own "does not have access" or the
    // service's "You do not have permission to perform this action" — what matters is that no
    // patient row is rendered.
    await expect(page.getByRole("main"))
      .toContainText(/do not have permission|does not have access|Forbidden/i);
    await expect(page.getByRole("link", { name: "Open chart" })).toHaveCount(0);

    await page.goto("/pharmacy");
    await expect(page.getByRole("heading", { name: "Dispensing queue" })).toBeVisible();
  });

  test("a doctor is shown the queue and not offered the dispense button", async ({ page }) => {
    await signIn(page, "dr.rao");
    await page.goto("/pharmacy");
    await expect(page.getByRole("heading", { name: "Dispensing queue" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Dispense" })).toHaveCount(0);
    await expect(page.getByRole("main")).toContainText("the dispense controls are not offered");
  });

  test("expired stock is refused at the door, in the service's own words", async ({ page }) => {
    await signIn(page, "pharmacist");
    await page.goto("/pharmacy/stock");

    const form = page.getByRole("region", { name: "Receive a delivery" });
    await form.getByRole("combobox", { name: "Medicine" })
      .selectOption({ label: "Paracetamol 500 mg tablet" });
    await form.getByRole("textbox", { name: /^Batch number/ }).fill(`X-${STAMP}`);
    await form.getByLabel(/^Expires on/).fill(yesterday());
    await form.getByRole("spinbutton", { name: /^Quantity/ }).fill("10");
    await form.getByRole("button", { name: "Receive into stock" }).click();

    await expect(form.getByRole("alert")).toContainText("not in the future");
  });
});

function nextYear(): string {
  const day = new Date();
  day.setUTCFullYear(day.getUTCFullYear() + 1);
  return day.toISOString().slice(0, 10);
}

function yesterday(): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() - 1);
  return day.toISOString().slice(0, 10);
}

/** The patient's id, read off the register rather than guessed. */
async function patientIdFor(page: import("@playwright/test").Page, mrn: string): Promise<string> {
  await page.goto(`/patients?q=${encodeURIComponent(mrn)}`);
  const link = page.getByRole("link", { name: "Open chart" }).first();
  const href = await link.getAttribute("href");
  return (href ?? "").split("/").pop() ?? "";
}
