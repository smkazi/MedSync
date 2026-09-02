import { expect, test } from "@playwright/test";
import { openBookableDay } from "./chart";
import { FIXTURE_SURNAME } from "./global-setup";
import { signIn } from "./sign-in";

/**
 * Booking, checking in and moving an appointment through its lifecycle — from a browser.
 *
 * <p>Until this slice the appointment book was read-only: every booking in this repository had been
 * made with `curl` or from a service test. The assertions that matter here are the refusals, not the
 * happy path. `AppointmentService.overlapConflict` distinguishes a taken room from a taken clinician
 * and the front desk needs to be told which, so the test pins the *message*, not the status code.
 */

const CLINICIAN = "Dr Anika Rao";

async function openSlots(page: import("@playwright/test").Page, date: string, mrn: string) {
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
  await expect(page.getByRole("group", { name: "Slot" })).toBeVisible();
}

/**
 * Books the first free slot and returns the time label the book will show it under.
 *
 * <p>The fixture patient is shared and the suite is idempotent rather than isolated, so a second run
 * against the same day finds the previous run's booking already there — and a row locator that
 * matched only on MRN would resolve to two rows and fail in strict mode. That is not a UI bug; it is
 * the same trap that made `RoomBookingIntegrationTest` pass once and fail on re-run. The slot's own
 * time makes the row unambiguous: if 09:00 is already taken, this run gets 09:15.
 */
async function bookFirstFreeSlot(
  page: import("@playwright/test").Page,
  offsetDays: number,
  mrn: string,
  options: { room?: string; reason?: string } = {},
): Promise<{ date: string; time: string; instant: string }> {
  // Walked forward from the offset rather than pinned to it, and the day it settles on is returned
  // so the caller can filter the book by it. One clinician has sixteen slots a day, each of these
  // tests books one on its own fixed day every run, and nothing cleans up — so after enough runs
  // against the same development database the day is full and the suite fails on a fixture rather
  // than on booking. It was green for weeks and then four of these went red at once, which is the
  // worst way for a test to be wrong. Same fix, and same reasoning, as `openBookableDay`.
  const date = await openBookableDay(page, mrn, offsetDays);
  const radio = page.locator('input[name="startsAt"]:not([disabled])').first();
  await expect(radio).toBeVisible();
  const instant = await radio.inputValue();
  // The label wrapping the radio carries the rendered time; read it rather than reformatting the
  // instant here, which would be a second copy of the UI's own formatting.
  const time = ((await radio.locator("xpath=..").locator("span.numeric").textContent()) ?? "").trim();
  await radio.check();

  await page.getByLabel("Department").selectOption("GEN");
  if (options.room) await page.getByLabel("Room").selectOption(options.room);
  if (options.reason) await page.getByLabel("Reason for attendance").fill(options.reason);
  await page.getByRole("button", { name: "Book appointment" }).click();
  await expect(page.getByRole("status")).toContainText(/Booked/);
  return { date, time, instant };
}

/**
 * The one row for this booking.
 *
 * <p>The MRN alone is not unique across runs, and a bare start time is not either — the book renders
 * a range, so "09:30" appears in both the 09:15-09:30 row and the 09:30-09:45 one. Anchoring to the
 * start of the row's text is what makes it exactly one.
 */
function bookedRow(page: import("@playwright/test").Page, mrn: string, time: string) {
  return page
    .getByRole("row")
    .filter({ hasText: mrn })
    .filter({ hasText: new RegExp(`^${time}\u2013`) });
}

/** The fixture patient's MRN, read off the register rather than guessed. */
async function fixtureMrn(page: import("@playwright/test").Page): Promise<string> {
  await page.goto(`/patients?q=${FIXTURE_SURNAME}`);
  const cell = page.getByRole("cell", { name: /^MRN-/ }).first();
  await expect(cell).toBeVisible();
  return ((await cell.textContent()) ?? "").trim();
}

test.describe("booking an appointment", () => {
  test("a slot the platform offered can be booked, and it holds the room", async ({ page }) => {
    await signIn(page, "reception");
    const mrn = await fixtureMrn(page);
    // The slot's value is the platform's own instant. Nothing here builds a timestamp.
    const { date, time } = await bookFirstFreeSlot(page, 8, mrn, {
      room: "GF-GEN",
      reason: "Routine review",
    });

    await page.goto(`/appointments?from=${date}&to=${date}`);
    const row = bookedRow(page, mrn, time);
    await expect(row).toContainText("BOOKED");
    // The wayfinding the facility work was for: a name and a floor, not a bare code.
    await expect(row).toContainText("General OPD");
    await expect(row).toContainText("Ground Floor");
  });

  test("a second booking into the same room and slot is refused, naming the room", async ({
    page,
  }) => {
    await signIn(page, "reception");
    const mrn = await fixtureMrn(page);
    const { date, instant } = await bookFirstFreeSlot(page, 15, mrn, { room: "GF-MAS" });

    // Same instant, offered again: the grid must now show it as taken with the reason.
    await openSlots(page, date, mrn);
    const same = page.locator(`input[name="startsAt"][value="${instant}"]`);
    // The clinician's slot now reads as taken, which is itself the point: the grid shows why.
    await expect(same).toBeDisabled();
    await expect(page.getByText("already booked").first()).toBeVisible();
  });

  test("the slot grid says why each unavailable slot is unavailable", async ({ page }) => {
    await signIn(page, "reception");
    const mrn = await fixtureMrn(page);
    // A day in the past: every slot is unavailable for the same stated reason.
    const past = new Date();
    past.setUTCDate(past.getUTCDate() - 3);
    while (past.getUTCDay() === 0 || past.getUTCDay() === 6) {
      past.setUTCDate(past.getUTCDate() - 1);
    }
    await openSlots(page, past.toISOString().slice(0, 10), mrn);

    await expect(page.getByText("in the past").first()).toBeVisible();
    // Nothing bookable, so the submit is not offered as if it were.
    await expect(page.getByRole("button", { name: "Book appointment" })).toBeDisabled();
  });

  test("an unknown MRN is a field error, not a booking against nothing", async ({ page }) => {
    await signIn(page, "reception");
    // Walked forward like the booking helper, for the same reason: this test needs one enabled
    // slot to select, and a day that earlier runs filled has none.
    await openBookableDay(page, "MRN-0000-000000", 22);

    await page.locator('input[name="startsAt"]:not([disabled])').first().check();

    await page.getByLabel("Department").selectOption("GEN");
    await page.getByRole("button", { name: "Book appointment" }).click();

    await expect(page.getByLabel("Patient MRN")).toHaveAttribute("aria-invalid", "true");
    await expect(page.getByRole("main")).toContainText(/No patient with MRN MRN-0000-000000/);
  });
});

test.describe("moving an appointment through its lifecycle", () => {
  test("check in, start, complete — each step offered only when it is legal", async ({ page }) => {
    await signIn(page, "admin");
    const mrn = await fixtureMrn(page);
    const { date, time } = await bookFirstFreeSlot(page, 29, mrn);
    await page.goto(`/appointments?from=${date}&to=${date}`);

    // BOOKED offers check-in and no-show, and not start.
    await expect(bookedRow(page, mrn, time).getByRole("button", { name: "Check in" })).toBeVisible();
    await expect(bookedRow(page, mrn, time).getByRole("button", { name: "Start" })).toHaveCount(0);

    await bookedRow(page, mrn, time).getByRole("button", { name: "Check in" }).click();
    await expect(bookedRow(page, mrn, time)).toContainText("CHECKED_IN");

    await bookedRow(page, mrn, time).getByRole("button", { name: "Start" }).click();
    await expect(bookedRow(page, mrn, time)).toContainText("IN_PROGRESS");

    await bookedRow(page, mrn, time).getByRole("button", { name: "Complete" }).click();

    // Completing takes it off the default list, which filters to what still needs attention. That
    // is the right behaviour for a clinic board, so assert it rather than working around it.
    await expect(bookedRow(page, mrn, time)).toHaveCount(0);

    await page.goto(`/appointments?from=${date}&to=${date}&status=COMPLETED`);
    const done = bookedRow(page, mrn, time);
    await expect(done).toContainText("COMPLETED");
    // Terminal: nothing further is offered, and the service is what makes that true.
    await expect(done.getByRole("button", { name: "Check in" })).toHaveCount(0);
    await expect(done.getByRole("button", { name: "Cancel" })).toHaveCount(0);
  });

  test("a cancellation records its reason and frees the slot", async ({ page }) => {
    await signIn(page, "reception");
    const mrn = await fixtureMrn(page);
    const { date, time, instant } = await bookFirstFreeSlot(page, 36, mrn, { room: "GF-GEN" });

    await page.goto(`/appointments?from=${date}&to=${date}`);
    const row = bookedRow(page, mrn, time);
    await row.getByLabel("Cancellation reason").fill("Patient rang to postpone");
    await row.getByRole("button", { name: "Cancel" }).click();
    await expect(page.getByRole("status")).toContainText(/Updated/);

    // A cancelled booking releases the room and the clinician — the exclusion constraints exclude
    // CANCELLED — so the same instant is offered again.
    await openSlots(page, date, mrn);
    await expect(page.locator(`input[name="startsAt"][value="${instant}"]`)).toBeEnabled();
  });

  test("a receptionist is not offered the clinical steps", async ({ page }) => {
    await signIn(page, "reception");
    const mrn = await fixtureMrn(page);
    const { date, time } = await bookFirstFreeSlot(page, 43, mrn);
    await page.goto(`/appointments?from=${date}&to=${date}`);
    await bookedRow(page, mrn, time).getByRole("button", { name: "Check in" }).click();

    // Check-in is front-desk work; starting the consultation is not.
    await expect(bookedRow(page, mrn, time)).toContainText("CHECKED_IN");
    await expect(bookedRow(page, mrn, time).getByRole("button", { name: "Start" })).toHaveCount(0);
  });

  test("a lab technician cannot reach the booking screen", async ({ page }) => {
    await signIn(page, "lab.tech");
    await page.goto("/appointments/new");
    await expect(page).toHaveURL(/\/appointments$/);
    await expect(page.getByRole("link", { name: "Book an appointment" })).toHaveCount(0);
  });
});
