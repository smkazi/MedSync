import { expect, test } from "@playwright/test";
import { encounterFor } from "./chart";
import { dicomInstance, runUid } from "./dicom";
import { openMenu, signIn } from "./sign-in";

/**
 * The radiology chain, driven through the browser under three identities.
 *
 * <p>Three, because the department is three jobs and no account holds two of them: a clinician
 * orders, a radiographer runs the worklist and files what comes off the modality, and only a
 * radiologist interprets and signs. The API suite proves the refusals row by row; what this proves
 * is that the screens each of them actually uses exist and work, and that neither is offered the
 * other's controls.
 *
 * <p>The file goes in through a real file input, which is the part no other test covers. A server
 * action posting multipart is a different code path from one posting JSON — the easiest mistake in
 * it is setting a `Content-Type` by hand and losing the boundary — and this is the only place it is
 * exercised end to end.
 */

const UID_ROOT = runUid();

test.describe("the radiology write layer", () => {
  test("an examination is ordered, scanned, reported and released", async ({ page }) => {
    test.setTimeout(180_000);

    // ---- the clinician orders, from the open chart -------------------------
    await signIn(page, "dr.rao");
    const { url } = await encounterFor(page, 99);

    const radiology = page.getByRole("region", { name: "Radiology" });
    await expect(radiology).toContainText("No imaging ordered on this visit.");

    await radiology
      .getByRole("combobox", { name: "Examination" })
      .selectOption({ index: 1 });
    await radiology
      .getByRole("textbox", { name: "What you want answered" })
      .fill("Persistent cough for three weeks, query consolidation.");
    await radiology.getByRole("button", { name: "Request the examination" }).click();
    await expect(radiology.getByRole("status")).toContainText("Requested.");

    // The accession number is read off the screen rather than assumed, because it is the thing the
    // rest of this test files against — the platform mints it and nothing else knows it yet.
    await page.goto(url);
    const row = page
      .getByRole("region", { name: "Radiology" })
      .getByRole("row")
      .filter({ hasText: /IMG/ })
      .first();
    await expect(row).toContainText("ORDERED");
    const accession = ((await row.innerText()).match(/IMG\S+/) ?? [])[0];
    expect(accession).toBeTruthy();
    // The examination's own address, taken off the chart's link. Kept because the worklist stops
    // listing this row the moment images are filed against it — see below — so after that there is
    // nowhere on the worklist left to click through from.
    const examinationUrl = await row.getByRole("link", { name: "Open" }).getAttribute("href");
    expect(examinationUrl).toBeTruthy();

    // ---- the radiographer books it and files what comes off the machine ----
    await signIn(page, "radiographer");
    await page.goto("/imaging");
    const worklistRow = page.getByRole("row").filter({ hasText: accession! });
    await expect(worklistRow).toBeVisible();

    // The worklist carries no clinical question, and that is the whole reason it is a narrower
    // shape than the examination: it is read on a screen beside a scanner, in a room patients walk
    // through.
    await expect(page.locator("body")).not.toContainText("query consolidation");

    await worklistRow.getByLabel(`Slot for ${accession}`).fill(inAnHour());
    await worklistRow.getByRole("button", { name: "Book" }).click();
    await expect(page.getByRole("row").filter({ hasText: accession! })).toContainText("scheduled");

    const filing = page.getByRole("region", {
      name: "File a study that came off a modality",
    });
    await filing.getByLabel("DICOM file").setInputFiles({
      name: "chest.dcm",
      mimeType: "application/dicom",
      buffer: dicomInstance(accession!, UID_ROOT),
    });
    await filing.getByRole("button", { name: "File this study" }).click();
    // The platform's own sentence, verbatim: whether it matched and whether it archived are two
    // facts a radiographer acts on, and the screen does not reword either.
    await expect(filing.getByRole("status")).toContainText(accession!);

    // And now it is off the worklist, which is what that list is for: it shows what is booked and
    // not yet acquired, so a study that has arrived has left it. A worklist that still carried an
    // examination the machine had already done would have the department scanning people twice.
    await page.goto("/imaging");
    await expect(page.getByRole("row").filter({ hasText: accession! })).toHaveCount(0);

    // A radiographer may open the examination and may not report on it. Both halves matter: the
    // first is why IMAGING_READ includes them at all, the second is the separation of duties.
    await page.goto(examinationUrl!);
    await expect(page.getByRole("heading", { level: 1 })).toContainText(accession!);
    await expect(page.getByRole("region", { name: "Write the report" })).toHaveCount(0);
    await expect(page.getByRole("region", { name: "Release this report" })).toHaveCount(0);

    // ---- the radiologist reports, and signing is what releases it ----------
    await signIn(page, "dr.mistry");
    await page.goto("/imaging/reporting");
    const queued = page.getByRole("row").filter({ hasText: accession! });
    await expect(queued).toBeVisible();
    await queued.getByRole("link", { name: "Report" }).click();

    // The clinical question is here, where it is read beside the images by the person answering it.
    await expect(page.getByText("query consolidation")).toBeVisible();

    const editor = page.getByRole("region", { name: "Write the report" });
    await editor
      .getByRole("textbox", { name: "Findings" })
      .fill("No focal consolidation. Heart size within normal limits.");
    await editor.getByRole("textbox", { name: "Impression" }).fill("Normal chest radiograph.");
    await editor.getByRole("button", { name: "Save as a draft" }).click();
    await expect(editor.getByRole("status")).toContainText("draft");

    // A draft says so, loudly, and says that nobody can see it. An unreleased finding that reads
    // like a released one is how a report gets assumed to have been communicated.
    // `exact`, because Playwright matches an accessible name as a substring by default —
    // "Report" would also match "Write the report" and "Release this report", and three
    // matches is a strict-mode failure rather than a wrong assertion.
    const report = page.getByRole("region", { name: "Report", exact: true });
    await expect(report).toContainText("draft — not released");
    await expect(report).toContainText("Signing is what releases it.");

    await page
      .getByRole("region", { name: "Release this report" })
      .getByRole("button", { name: "Sign and release this report" })
      .click();
    await expect(page.getByRole("region", { name: "Report", exact: true }))
      .toContainText("signed");

    // ---- the clinician who asked reads the answer -------------------------
    await signIn(page, "dr.rao");
    await page.goto(url);
    await expect(
      page.getByRole("region", { name: "Radiology" }).getByRole("row").filter({ hasText: accession! }),
    ).toContainText("REPORTED");

    await page
      .getByRole("region", { name: "Radiology" })
      .getByRole("row")
      .filter({ hasText: accession! })
      .getByRole("link", { name: "Open" })
      .click();
    await expect(page.getByRole("region", { name: "Report", exact: true })).toContainText(
      "Normal chest radiograph.",
    );
    // Ordering is a clinician's act and reporting is not: the requester gets no editor either.
    await expect(page.getByRole("region", { name: "Amend the report" })).toHaveCount(0);
  });

  test("the department's screens are offered to the department and to nobody else", async ({
    page,
  }) => {
    // A radiographer runs the worklist; a radiologist reports. The menu mirrors what the platform
    // enforces, and a visible item nobody may press teaches people to distrust the menu.
    //
    // Anchored regular expressions rather than exact names: each item's accessible name is its
    // label followed by the note under it ("Worklist What is booked and not yet scanned"), because
    // the note is part of the link and is meant to be read out.
    await signIn(page, "radiographer");
    const forRadiographer = await openMenu(page, "Radiology");
    await expect(forRadiographer.getByRole("link", { name: /^Worklist/ })).toBeVisible();
    await expect(forRadiographer.getByRole("link", { name: /^Unmatched studies/ })).toBeVisible();
    await expect(forRadiographer.getByRole("link", { name: /^Reporting queue/ })).toHaveCount(0);

    await signIn(page, "dr.mistry");
    const forRadiologist = await openMenu(page, "Radiology");
    await expect(forRadiologist.getByRole("link", { name: /^Reporting queue/ })).toBeVisible();
    await expect(forRadiologist.getByRole("link", { name: /^Worklist/ })).toHaveCount(0);

    // The billing desk has no radiology menu at all — and, more to the point, gets no worklist by
    // typing the address either, because the menu is a convenience and never the control.
    //
    // Asserted on the refusal and on the absence of rows rather than on the page being blank: the
    // page still renders its own frame and its counters read zero, which is what a screen that
    // could not load its data looks like. Expecting the words "Needs contrast" to be missing would
    // have been asserting that the heading of an empty counter is not there, and that assertion
    // fails against a page working exactly as intended.
    await signIn(page, "cashier");
    await expect(page.getByRole("button", { name: "Radiology", exact: true })).toHaveCount(0);
    await page.goto("/imaging");
    // On the message rather than on `role="alert"`: Next's own route announcer is an alert too, and
    // two matches is a strict-mode failure. The text is the platform's own refusal, forwarded
    // rather than replaced — `load` stopped substituting a generic sentence for a 403 precisely so
    // that a screen says which door was closed.
    await expect(page.getByText("You do not have permission")).toBeVisible();
    await expect(page.getByRole("row").filter({ hasText: /IMG/ })).toHaveCount(0);
  });
});

/** A slot an hour from now, in the shape a `datetime-local` input wants. */
function inAnHour(): string {
  const when = new Date(Date.now() + 3_600_000);
  // A well-formed local date-time is all this needs to be. Which instant it lands on is the
  // conversion's business — the server action reads a typed time in the deployment's zone, and
  // `zone.test.ts` is where that is pinned down, against a half-hour offset where an error shows.
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    `${when.getFullYear()}-${pad(when.getMonth() + 1)}-${pad(when.getDate())}` +
    `T${pad(when.getHours())}:${pad(when.getMinutes())}`
  );
}
