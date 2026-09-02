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

/**
 * Opens the booking screen on the first day at or after {@code offsetDays} that has a free slot.
 *
 * <p>Walks forward rather than trusting the offset, and that is not defensive coding for its own
 * sake. The seeded clinic gives one clinician sixteen slots a day, every spec here books on a
 * fixed day offset, and nothing cleans up — so after enough runs against the same development
 * database the day is full and the suite starts failing on a fixture rather than on the behaviour
 * it was testing. It was green for a long time and then it was not, which is the worst way for a
 * test to be wrong.
 *
 * @return the date it settled on, so the caller can filter the appointment book by it
 */
export async function openBookableDay(page: Page, mrn: string, offsetDays: number): Promise<string> {
  for (let attempt = 0; attempt < 20; attempt++) {
    const date = nextWeekday(offsetDays + attempt * 7);
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
    if ((await page.locator('input[name="startsAt"]:not([disabled])').count()) > 0) {
      return date;
    }
  }
  throw new Error(
    `no free slot for ${CLINICIAN} on any of twenty weekdays from +${offsetDays} days. `
      + "The development database is full of this suite's own appointments; drop the scheduling "
      + "schema, or run `make dev-test-stack` for a fresh one.",
  );
}

/** Books, checks in, starts and opens an encounter, returning its URL and the row's identifiers. */
export async function encounterFor(page: Page, offsetDays: number) {
  const mrn = await fixtureMrn(page);
  const date = await openBookableDay(page, mrn, offsetDays);

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
