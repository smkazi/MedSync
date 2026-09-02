import { expect, test, type Locator, type Page } from "@playwright/test";
import { fixtureMrn } from "./chart";
import { signIn } from "./sign-in";

/**
 * The casualty board and the in-patient census, driven through the browser.
 *
 * <p>What is worth testing here is the ordering, and it is worth testing in the browser rather than
 * only against the API: the board is the one screen in this application where the order of the rows
 * is itself the safety property. A queue served in arrival order kills the person who arrived last
 * and is the sickest, so the assertion below is that an acuity 1 who walked in second is rendered
 * above the acuity 4 who was already waiting — not that the endpoint returned them in that order,
 * which tests/api already proves, but that nothing between the endpoint and the screen re-sorted
 * them.
 *
 * <p>Everything these tests open, they close. The seeded facility has four in-patient beds and one
 * casualty room, shared with the API suite and with whoever is using the development stack, so an
 * attendance left on the board or an admission left holding a bed makes the next run fail on a
 * fixture rather than on a behaviour.
 */

/** Unique per run, so two rows for the same fixture patient are still addressable. */
const STAMP = Date.now().toString(36).toUpperCase();

function complaint(what: string): string {
  return `${what} ${STAMP}`;
}

function boardRow(page: Page, text: string): Locator {
  return page.getByRole("row").filter({ hasText: text });
}

/** Triages somebody onto the board through the arrival form. */
async function arrive(page: Page, mrn: string, acuity: string, presenting: string) {
  await page.goto(`/casualty?mrn=${encodeURIComponent(mrn)}`);
  // Index 1, not 0: every required select opens on an em-dash placeholder, which is what makes
  // "required, with no default" true in the markup rather than only in the hint.
  await page.getByRole("combobox", { name: "Patient" }).selectOption({ index: 1 });
  await page.getByRole("textbox", { name: /^MRN/ }).fill(mrn);
  await page.getByRole("combobox", { name: "Triage level" }).selectOption(acuity);
  await page.getByRole("textbox", { name: /^Presenting complaint/ }).fill(presenting);
  await page.getByRole("button", { name: "Triage and admit to the board" }).click();
  // Waited for rather than assumed: the form posts a server action and clicking only dispatches
  // it, so navigating straight on reads the board a moment too early.
  await expect(boardRow(page, presenting)).toBeVisible();
}

/** Where a complaint sits among the board's rows. */
async function positionOf(page: Page, presenting: string): Promise<number> {
  const rows = await page.getByRole("row").allInnerTexts();
  const at = rows.findIndex((row) => row.includes(presenting));
  expect(at, `"${presenting}" is on the board`).toBeGreaterThan(-1);
  return at;
}

async function sendHome(page: Page, presenting: string) {
  await page.goto("/casualty");
  const row = boardRow(page, presenting);
  if ((await row.count()) > 0) {
    await row.getByRole("button", { name: "Discharge" }).click();
    await expect(page.getByRole("status")).toContainText("Discharged.");
    await expect(boardRow(page, presenting)).toHaveCount(0);
  }
}

test.describe("the casualty board", () => {
  test("the sickest patient is rendered above one who arrived first", async ({ page }) => {
    await signIn(page, "nurse.iqbal");
    const mrn = await fixtureMrn(page);

    const minor = complaint("Grazed knee");
    const critical = complaint("Collapse at home");
    await arrive(page, mrn, "4", minor);
    await arrive(page, mrn, "1", critical);

    // Relative, not "at the top": the board is shared with the API suite and with whatever a
    // developer left on it, so the position of a row is not a claim this test can make. That one
    // is above the other is.
    expect(await positionOf(page, critical)).toBeLessThan(await positionOf(page, minor));

    // And the level is on the row, coloured by acuity rather than by how long they have waited: an
    // acuity 1 is red the moment they walk in.
    await expect(boardRow(page, critical)).toContainText("1");

    await sendHome(page, critical);
    await sendHome(page, minor);
  });

  test("re-triaging moves a patient up the board without anybody re-sorting it", async ({ page }) => {
    await signIn(page, "nurse.iqbal");
    const mrn = await fixtureMrn(page);

    const worsening = complaint("Abdominal pain, comfortable");
    const other = complaint("Chest pain");
    await arrive(page, mrn, "4", worsening);
    await arrive(page, mrn, "2", other);
    expect(await positionOf(page, worsening)).toBeGreaterThan(await positionOf(page, other));

    const row = boardRow(page, worsening);
    await row.locator('select[name="triageAcuity"]').selectOption("1");
    await row.getByRole("button", { name: "Re-triage" }).click();
    // The service's own message, waited for rather than assumed. Reading the row back straight
    // after the click races the redirect and re-reads the board as it was - which is how this test
    // first failed, reporting an unmoved row that had in fact moved a moment later.
    await expect(page.getByRole("status")).toContainText("Re-triaged to acuity 1");

    // The patient who gets worse in a corridor is the case the board exists for.
    expect(await positionOf(page, worsening)).toBeLessThan(await positionOf(page, other));

    await sendHome(page, worsening);
    await sendHome(page, other);
  });

  test("a bay is allocated, then the admission frees it again", async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, "dr.rao");
    const mrn = await fixtureMrn(page);

    const presenting = complaint("Chest pain, radiating");
    await arrive(page, mrn, "2", presenting);

    const row = () => boardRow(page, presenting);
    await expect(row()).toContainText("waiting");
    const bay = ((await row().locator('select[name="bedId"] option').first().innerText()) ?? "").trim();
    await row().getByRole("button", { name: "Place" }).click();
    await expect(page.getByRole("status")).toContainText(`Moved to ${bay}`);
    await expect(row()).toContainText(bay);

    // Admitting is a link to the census carrying the attendance, not a second form on this screen:
    // the bed being allocated is a ward bed, and the screen that knows which are free is the one
    // that lists them.
    await boardRow(page, presenting).getByRole("link", { name: "Admit" }).click();
    await expect(page.getByRole("heading", { name: `Admit ${mrn} from casualty` })).toBeVisible();

    const wardBed = ((await page
      .getByRole("combobox", { name: "Ward bed" })
      .locator("option")
      .nth(1)
      .innerText()) ?? "").trim();
    await page.getByRole("combobox", { name: "Ward bed" }).selectOption({ index: 1 });
    await page.getByRole("combobox", { name: "Admitting clinician" }).selectOption({ index: 1 });
    await page.getByRole("button", { name: "Admit", exact: true }).click();
    const bedCode = wardBed.split("—")[0]!.trim();
    await expect(page.getByRole("status")).toContainText(`Admitted to ${bedCode}`);

    const censusRow = () =>
      page.getByRole("row").filter({ hasText: bedCode }).filter({ hasText: mrn });
    await expect(censusRow()).toBeVisible();
    // The source is on the row, and it is the whole reason the two paths are one service: this
    // admission came through casualty, and the census says so without anybody typing it.
    await expect(censusRow()).toContainText("casualty");

    // Off the casualty board, because they are no longer casualty's problem - and the bay they
    // were in is free in the same transaction. A bay left held is a bay the department believes it
    // does not have.
    await page.goto("/casualty");
    await expect(boardRow(page, presenting)).toHaveCount(0);
    // Not "8 of 8 free bays": the department is shared with the API suite and with whoever is
    // using the stack, so an absolute count is an assertion about other people's rows. That the
    // bay is released by the admission is proven exactly once, in tests/api's AdmissionsJourneyIT,
    // against beds that test allocated itself.

    await page.goto("/admissions");

    // Discharged before the test ends, and asserted rather than fired and forgotten: the seeded
    // facility has four in-patient beds and the next run needs them back.
    await censusRow().getByRole("button", { name: "Discharge" }).click();
    await expect(page.getByRole("status")).toContainText(/Discharged/);

    // Asserted on the bed map rather than on the absence of the census row, and that is a
    // correction: "no row contains this bed code and this MRN" also matches the transfer picker in
    // somebody else's row, whose options are free bed codes and whose label carries an MRN. With
    // the same fixture patient admitted twice it read as a failure while the discharge had worked.
    // The map is unambiguous - a bed is drawn free or occupied, and there is one of each per bed.
    const bed = page.getByRole("region", { name: "Bed map" }).getByTitle("Free", { exact: true });
    await expect(bed.filter({ hasText: bedCode })).toBeVisible();
  });

  test("the bed map says what is free, and agrees with the census", async ({ page }) => {
    await signIn(page, "nurse.iqbal");
    await page.goto("/admissions");

    // The map is the same occupancy the census reads, drawn per room rather than per patient - so
    // "how many free" has to be one number, not two that can disagree.
    const map = page.getByRole("region", { name: "Bed map" });
    await expect(map).toBeVisible();
    const free = Number(
      ((await page.getByText("Free beds").locator("xpath=..").innerText()) ?? "").match(/\d+/)?.[0],
    );
    const roomTotals = await map.locator("h3").allInnerTexts();
    const mapped = roomTotals
      .map((line) => Number(line.match(/(\d+) of \d+ free/)?.[1] ?? 0))
      .reduce((a, b) => a + b, 0);
    expect(mapped, "the bed map and the free-bed tile count the same beds").toBe(free);
  });

  test("the front desk cannot reach the board at all", async ({ page }) => {
    // Not a hidden menu item: the route itself refuses. A list of who is in casualty with what
    // complaint and how sick they are is a chart in table form, and reception books and registers.
    await signIn(page, "reception");
    await page.goto("/casualty");
    await expect(page.getByRole("main")).toContainText(/does not have access|Forbidden/i);
    await page.goto("/admissions");
    await expect(page.getByRole("main")).toContainText(/does not have access|Forbidden/i);
  });
});
