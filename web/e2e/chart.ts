import { expect, type Page } from "@playwright/test";
import { FIXTURE_SURNAME } from "./global-setup";

/**
 * Getting a browser to an open, chartable encounter.
 *
 * <p>Extracted from `charting.spec.ts` when the laboratory spec needed the same thing. Every step
 * is a real click, because the point is that the chain works from a browser: nothing opens an
 * encounter on its own, so a consultation that has begun is not chartable until somebody presses
 * "Open chart".
 *
 * <p>Each caller passes its own day offset. Two specs booking the same clinician on the same day
 * would contend for slots, and the exclusion constraint would refuse the second — so the offsets
 * are allocated per spec rather than defaulted.
 */

export const CLINICIAN = "Dr Anika Rao";

export function nextWeekday(offsetDays: number): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() + offsetDays);
  while (day.getUTCDay() === 0 || day.getUTCDay() === 6) {
    day.setUTCDate(day.getUTCDate() + 1);
  }
  return day.toISOString().slice(0, 10);
}

export async function fixtureMrn(page: Page): Promise<string> {
  await page.goto(`/patients?q=${FIXTURE_SURNAME}`);
  const cell = page.getByRole("cell", { name: /^MRN-/ }).first();
  await expect(cell).toBeVisible();
  return ((await cell.textContent()) ?? "").trim();
}

/** Books, checks in, starts and opens an encounter, returning its URL and the row's identifiers. */
export async function encounterFor(page: Page, offsetDays: number) {
  const mrn = await fixtureMrn(page);
  const date = nextWeekday(offsetDays);

  await page.goto(`/appointments/new?mrn=${encodeURIComponent(mrn)}`);
  // A combobox, not just a label. `Card` names its <section> with `aria-labelledby`, which is
  // correct ARIA and what makes a screen full of same-named fields addressable at all - but it
  // means the region "Clinician and day" answers to getByLabel("Clinician") too. Saying the role
  // says which of the two is meant.
  await page
    .getByRole("combobox", { name: "Clinician" })
    .selectOption({ label: `${CLINICIAN} — Consultant Physician` });
  await page.getByLabel("Date").fill(date);
  await page.getByRole("button", { name: "Show slots" }).click();

  const radio = page.locator('input[name="startsAt"]:not([disabled])').first();
  await expect(radio).toBeVisible();
  const time = ((await radio.locator("xpath=..").locator("span.numeric").textContent()) ?? "").trim();
  await radio.check();
  await page.getByLabel("Department").selectOption("GEN");
  await page.getByRole("button", { name: "Book appointment" }).click();
  await expect(page.getByRole("status")).toContainText(/Booked/);

  await page.goto(`/appointments?from=${date}&to=${date}`);
  const row = () =>
    page
      .getByRole("row")
      .filter({ hasText: mrn })
      .filter({ hasText: new RegExp(`^${time}\\u2013`) });

  await row().getByRole("button", { name: "Check in" }).click();
  await row().getByRole("button", { name: "Start" }).click();
  await row().getByRole("button", { name: "Open chart" }).click();

  await expect(page.getByRole("heading", { name: "Encounter" })).toBeVisible();
  return { url: page.url(), mrn, date, time };
}
