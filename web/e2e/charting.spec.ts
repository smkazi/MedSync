import { expect, test } from "@playwright/test";
import { FIXTURE_SURNAME } from "./global-setup";
import { signIn } from "./sign-in";

/**
 * Clinical charting from a browser: vitals, the SOAP note, signing, amending, coding, closing.
 *
 * <p>The assertions that carry weight here are the ones about the note's lifecycle, because that
 * lifecycle is a medico-legal rule rather than a UI convenience. `PUT /encounters/{id}/note` edits
 * a draft in place but *amends* a signed revision, and closing is refused while the latest revision
 * is unsigned. Both are the service's rules; these tests check the screen tells the truth about
 * them, and that the refusal names the outstanding revision rather than saying "conflict".
 */

const CLINICIAN = "Dr Anika Rao";

function nextWeekday(offsetDays: number): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() + offsetDays);
  while (day.getUTCDay() === 0 || day.getUTCDay() === 6) {
    day.setUTCDate(day.getUTCDate() + 1);
  }
  return day.toISOString().slice(0, 10);
}

async function fixtureMrn(page: import("@playwright/test").Page): Promise<string> {
  await page.goto(`/patients?q=${FIXTURE_SURNAME}`);
  const cell = page.getByRole("cell", { name: /^MRN-/ }).first();
  await expect(cell).toBeVisible();
  return ((await cell.textContent()) ?? "").trim();
}

/**
 * Books, checks in, starts and opens an encounter, returning its URL.
 *
 * <p>Every step is a real click, because the point is that the chain works from a browser: nothing
 * opens an encounter on its own, so a consultation that has begun is not chartable until somebody
 * presses "Open chart".
 */
async function encounterFor(page: import("@playwright/test").Page, offsetDays: number) {
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

test.describe("charting an encounter", () => {
  test("vitals, note, signing, amending, coding and closing — in order", async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, "dr.rao");
    const { url, date, mrn, time } = await encounterFor(page, 50);

    // ---- vitals -------------------------------------------------------------------------
    await page.getByLabel(/Heart rate/).fill("104");
    await page.getByLabel(/Systolic/).fill("148");
    await page.getByLabel(/Diastolic/).fill("92");
    await page.getByLabel(/SpO2/).fill("94");
    await page.getByRole("button", { name: "Record observations" }).click();
    await expect(page.getByRole("status")).toContainText("Vitals recorded");
    // Rendered back from the record, not echoed from the form.
    await expect(page.getByText("148/92")).toBeVisible();

    // A blank field must not be stored as zero: pain was left empty, so it reads as unrecorded.
    const observations = page.locator("dl").first();
    await expect(observations.filter({ hasText: "Pain" })).toContainText("—");

    // ---- the note -----------------------------------------------------------------------
    await page.getByLabel("Subjective").fill("Chest tightness on exertion for three days.");
    await page.getByLabel("Assessment").fill("Suspected stable angina.");
    await page.getByRole("button", { name: "Save note" }).click();
    await expect(page.getByRole("status")).toContainText(/Revision 1, unsigned/);

    // ---- closing is refused while the note is unsigned, and it says which revision ------
    await page.getByRole("button", { name: "Close this encounter" }).click();
    await expect(page.getByRole("main").getByRole("alert")).toContainText(
      /Revision 1 is unsigned; sign the note before closing/,
    );

    // ---- signing ------------------------------------------------------------------------
    await page.getByRole("button", { name: "Sign revision 1" }).click();
    await expect(page.getByRole("status")).toContainText("Note signed");
    await expect(page.getByText(/signed rev 1/)).toBeVisible();
    // Signed, so the sign button is gone: it is one-way and the service refuses a second signature.
    await expect(page.getByRole("button", { name: /^Sign revision/ })).toHaveCount(0);

    // ---- editing a signed note becomes an amendment, and the screen says so beforehand --
    await expect(page.getByText(/Saving creates an amendment/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Save as an amendment" })).toBeVisible();
    await page.getByLabel("Plan").fill("ECG and troponin; review with results.");
    await page.getByRole("button", { name: "Save as an amendment" }).click();
    await expect(page.getByRole("status")).toContainText(/Revision 2/);

    // The original stays readable — the whole reason amendments exist. An amendment carries the
    // note forward, so "Suspected stable angina." is now on both revisions and the text alone
    // proves nothing: this anchors on revision 1's own row, and on it still reading as signed.
    const revisionRow = (revision: number) =>
      page
        .getByRole("row")
        .filter({ has: page.getByRole("cell", { name: String(revision), exact: true }) });
    await expect(revisionRow(1)).toContainText("Suspected stable angina.");
    await expect(revisionRow(1)).not.toContainText("unsigned");
    await expect(revisionRow(2)).toContainText("unsigned");
    await expect(page.getByText(/amends an earlier signed revision/)).toBeVisible();

    // ---- coding -------------------------------------------------------------------------
    await page.getByLabel("ICD-10").fill("I20.9");
    await page.getByLabel("Description").fill("Angina pectoris, unspecified");
    await page.getByLabel("Category").selectOption("PRIMARY");
    await page.getByRole("button", { name: "Add diagnosis" }).click();
    await expect(page.getByRole("status")).toContainText("Added I20.9");
    await expect(page.getByRole("cell", { name: "I20.9" })).toBeVisible();

    // ---- closing, now that revision 2 is... unsigned, so still refused ------------------
    await page.getByRole("button", { name: "Close this encounter" }).click();
    await expect(page.getByRole("main").getByRole("alert")).toContainText(
      /Revision 2 is unsigned; sign the note before closing/,
    );
    await page.getByRole("button", { name: "Sign revision 2" }).click();
    await page.getByRole("button", { name: "Close this encounter" }).click();
    await expect(page.getByRole("status")).toContainText("Encounter closed");

    // A closed encounter offers no editor, and says why rather than simply hiding it.
    await page.goto(url);
    await expect(page.getByText(/This encounter is closed/)).toBeVisible();
    await expect(page.getByRole("button", { name: /Save note|Save as an amendment/ })).toHaveCount(0);

    // Closing completes the appointment, so it leaves the "needing attention" board.
    await page.goto(`/appointments?from=${date}&to=${date}&status=COMPLETED`);
    await expect(
      page
        .getByRole("row")
        .filter({ hasText: mrn })
        .filter({ hasText: new RegExp(`^${time}\\u2013`) }),
    ).toContainText("COMPLETED");
  });

  test("a nurse may write the note but not sign it", async ({ page }) => {
    test.setTimeout(90_000);
    await signIn(page, "nurse.iqbal");
    await encounterFor(page, 57);

    await page.getByLabel("Objective").fill("Alert, oriented. Chest clear.");
    await page.getByRole("button", { name: "Save note" }).click();
    await expect(page.getByRole("status")).toContainText(/Revision 1, unsigned/);

    // Signing is a doctor's act. The service gates it with hasAnyRole('ADMIN','DOCTOR').
    await expect(page.getByRole("button", { name: /^Sign revision/ })).toHaveCount(0);
    await expect(page.getByText(/A doctor signs it/)).toBeVisible();
  });

  test("recording nothing at all is refused rather than written as an empty observation", async ({
    page,
  }) => {
    test.setTimeout(90_000);
    await signIn(page, "dr.rao");
    await encounterFor(page, 64);

    await page.getByRole("button", { name: "Record observations" }).click();
    await expect(page.getByRole("main").getByRole("alert")).toContainText(
      /Enter at least one observation/,
    );
  });

  test("ICD-10 suggestions are offered but never written on their own", async ({ page }) => {
    test.setTimeout(90_000);
    await signIn(page, "dr.rao");
    await encounterFor(page, 71);

    await page.getByLabel("Suggest a code").fill("acute myocardial infarction");
    // Exact, because "Suggest a code" is the field's own label and a substring match would take it.
    await page.getByRole("button", { name: "Suggest", exact: true }).click();

    const suggestion = page.getByRole("button", { name: /^I2/ }).first();
    await expect(suggestion).toBeVisible();
    // Provenance sits with the suggestions, so model output is never mistaken for a recorded fact.
    await expect(page.getByText(/Advisory only — review before it informs care/)).toBeVisible();

    // Nothing is in the record yet, and the code field is still empty until a click.
    await expect(page.getByLabel("ICD-10")).toHaveValue("");
    await suggestion.click();
    await expect(page.getByLabel("ICD-10")).not.toHaveValue("");
    // Still nothing recorded: filling the field is not the same as adding the diagnosis.
    await expect(page.getByRole("cell", { name: /^I2/ })).toHaveCount(0);
  });

  test("a receptionist cannot reach a chart at all", async ({ page }) => {
    await signIn(page, "reception");
    // CHART_READ excludes RECEPTIONIST, so the encounter is not theirs to read. The refusal has to
    // arrive as a refusal: rethrowing the 403 rendered the error boundary, and "A server error
    // occurred" is both wrong and unactionable for a permission decision.
    await page.goto("/encounters/00000000-0000-4000-8000-000000000000");
    await expect(page.getByRole("main")).toContainText(/does not have access|Forbidden|not found/i);
    await expect(page.getByText(/A server error occurred/)).toHaveCount(0);
  });
});
