import { expect, test, type Page } from "@playwright/test";
import { encounterFor, fixtureMrn } from "./chart";
import { signIn } from "./sign-in";

/**
 * Consent, through the browser, under the roles that really hold each half.
 *
 * <p>The point of driving it this way is the split: `reception` writes down what the patient
 * decided and cannot send anything; `dr.rao` sends a record under a consent and cannot record the
 * decision. A spec that did both as an administrator would be testing a system nobody runs — the
 * same reasoning the laboratory spec uses for its four identities.
 *
 * <p>Every refusal asserted here is the platform's own sentence. "Consent X does not cover
 * prescription" is what tells a clinician what to ask the patient for; a generic "forbidden" is
 * what teaches them to send an email attachment instead.
 */

const STAMP = Date.now().toString(36).toUpperCase();

/** Records a consent request as the front desk, and returns its artefact id. */
async function requestConsent(page: Page, mrn: string, hiTypes: string[]): Promise<string> {
  await page.goto(`/sharing?mrn=${encodeURIComponent(mrn)}`);

  const form = page.getByRole("region", { name: "Record a consent request" });
  await form.getByLabel("Who is asking").fill(`A referring clinic ${STAMP}`);
  await form.getByLabel("Why").selectOption("CARE_MANAGEMENT");
  for (const hiType of hiTypes) {
    await form.getByRole("checkbox", { name: hiType }).check();
  }
  await form.getByLabel("Records dated from").fill("2025-01-01");
  await form.getByLabel("Records dated to").fill(new Date().toISOString().slice(0, 10));
  const lapses = new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString().slice(0, 16);
  await form.getByLabel("Permission lapses").fill(lapses);
  await form.getByRole("button", { name: "Record the request" }).click();

  const banner = form.getByRole("status");
  // The platform's own sentence, which says plainly that a request is not permission.
  await expect(banner).toContainText("recorded as requested");
  await expect(banner).toContainText("authorises nothing until the patient grants it");
  const text = (await banner.textContent()) ?? "";
  const artefactId = text.match(/LOCAL-[A-Z0-9-]+/)?.[0];
  expect(artefactId, `no artefact id in "${text}"`).toBeTruthy();
  return artefactId as string;
}

test.describe("consent and sharing", () => {
  test("requested, granted, shared — and the register shows what left", async ({ page }) => {
    test.setTimeout(120_000);

    // The encounter to share, and a signed note on it so the bundle carries something.
    await signIn(page, "dr.rao");
    const { url } = await encounterFor(page, 6);
    const encounterId = url.split("/").pop() as string;

    await signIn(page, "reception");
    const mrn = await fixtureMrn(page);
    const artefactId = await requestConsent(page, mrn, ["Outpatient consultation"]);

    // A pending request authorises nothing, and the row says so before anybody tries.
    const row = page.getByRole("row").filter({ hasText: artefactId });
    await expect(row).toContainText("requested");

    await row.getByRole("button", { name: "Granted" }).click();
    // The banner the redirect left, which is the first on the page — the request form keeps its
    // own below it, and a page-wide status locator is a coin toss between the two.
    await expect(page.getByRole("status").first()).toContainText("granted until");

    await signIn(page, "dr.rao");
    await page.goto("/sharing");
    const share = page.getByRole("region", { name: "Send a record under a consent" });
    await share.getByLabel("Consent").selectOption({ label: await optionFor(page, artefactId) });
    await share.getByLabel("What kind of record").selectOption("OP_CONSULTATION");
    await share.getByLabel("Record id").fill(encounterId);
    await share.getByRole("button", { name: "Send it" }).click();

    const outcome = share.getByRole("status");
    await expect(outcome).toContainText("resource(s)");
    await expect(outcome)
      .toContainText(/No ABDM gateway is configured/);

    await page.goto(`/sharing/disclosures?mrn=${encodeURIComponent(mrn)}`);
    const disclosure = page.getByRole("row").filter({ hasText: artefactId });
    await expect(disclosure).toContainText("consented share");
    await expect(disclosure).toContainText("dr.rao");

    // The register can be asked for a period, which is how anybody actually asks the question.
    // Today's date from the browser's clock rather than a fixed string, so this does not expire.
    const today = new Date().toISOString().slice(0, 10);
    await page.goto(
      `/sharing/disclosures?mrn=${encodeURIComponent(mrn)}&from=${today}&to=${today}`,
    );
    await expect(page.getByRole("row").filter({ hasText: artefactId })).toBeVisible();

    await page.goto(
      `/sharing/disclosures?mrn=${encodeURIComponent(mrn)}&from=2020-01-01&to=2020-01-02`,
    );
    await expect(page.getByRole("row").filter({ hasText: artefactId })).toHaveCount(0);
    await expect(page.getByText(/Nothing about this patient was released in that period/)).toBeVisible();
  });

  test("a consent for one kind of record refuses another, in the platform's own words", async ({
    page,
  }) => {
    test.setTimeout(120_000);
    await signIn(page, "dr.rao");
    const { url } = await encounterFor(page, 7);
    const encounterId = url.split("/").pop() as string;

    await signIn(page, "reception");
    const mrn = await fixtureMrn(page);
    const artefactId = await requestConsent(page, mrn, ["Laboratory report"]);
    await page.getByRole("row").filter({ hasText: artefactId })
      .getByRole("button", { name: "Granted" }).click();
    await expect(page.getByRole("status").first()).toContainText("granted until");

    await signIn(page, "dr.rao");
    await page.goto("/sharing");
    const share = page.getByRole("region", { name: "Send a record under a consent" });
    await share.getByLabel("Consent").selectOption({ label: await optionFor(page, artefactId) });
    await share.getByLabel("What kind of record").selectOption("OP_CONSULTATION");
    await share.getByLabel("Record id").fill(encounterId);
    await share.getByRole("button", { name: "Send it" }).click();

    await expect(share.getByRole("alert")).toContainText("does not cover");
    await expect(share.getByRole("alert"))
      .toContainText("consent for one kind of record is not consent for another");
  });

  test("a revoked consent keeps its reason and authorises nothing", async ({ page }) => {
    await signIn(page, "reception");
    const mrn = await fixtureMrn(page);
    const artefactId = await requestConsent(page, mrn, ["Outpatient consultation"]);

    const row = page.getByRole("row").filter({ hasText: artefactId });
    await row.getByRole("button", { name: "Granted" }).click();
    await expect(page.getByRole("status").first()).toContainText("granted until");

    const granted = page.getByRole("row").filter({ hasText: artefactId });
    await granted.getByRole("textbox", { name: /why/i }).fill("Withdrawn at the desk");
    await granted.getByRole("button", { name: "Revoke" }).click();

    await expect(page.getByRole("status").first()).toContainText("revoked");

    // Gone from the open list, which is what "open" means — and still there, with its reason,
    // under "finished too". Kept rather than deleted because the question asked afterwards is
    // whether a disclosure was lawful at the time, and a deleted consent cannot answer it.
    await expect(page.getByRole("row").filter({ hasText: artefactId })).toHaveCount(0);
    await page.getByRole("checkbox", { name: "finished too" }).check();
    await page.getByRole("button", { name: "Show" }).click();

    const revoked = page.getByRole("row").filter({ hasText: artefactId });
    await expect(revoked).toContainText("revoked");
    await expect(revoked).toContainText("Withdrawn at the desk");
  });

  test("recording a decision and acting on one are different people's screens", async ({ page }) => {
    await signIn(page, "reception");
    // The front desk writes the decision down and is offered no way to send anything.
    await page.goto("/sharing");
    await expect(page.getByRole("region", { name: "Record a consent request" })).toBeVisible();
    await expect(page.getByRole("region", { name: "Send a record under a consent" }))
      .toHaveCount(0);

    await signIn(page, "dr.rao");
    await page.goto("/sharing");
    // A clinician sends and is offered no way to record the patient's decision — that would be
    // authorising their own access.
    await expect(page.getByRole("region", { name: "Send a record under a consent" })).toBeVisible();
    await expect(page.getByRole("region", { name: "Record a consent request" })).toHaveCount(0);
  });

  test("the laboratory is not offered consent at all", async ({ page }) => {
    await signIn(page, "lab.tech");
    await expect(page.getByRole("button", { name: "Sharing", exact: true })).toHaveCount(0);

    await page.goto("/sharing");
    await expect(page.getByRole("main")).toContainText(/does not have access|permission|403/i);
  });

  test("an ABHA is linked at the desk and never rendered on the chart", async ({ page }) => {
    await signIn(page, "reception");
    const mrn = await fixtureMrn(page);
    await page.goto(`/patients?q=${encodeURIComponent(mrn)}`);
    await page.getByRole("link", { name: "Open chart" }).first().click();

    const demographics = page.getByRole("region", { name: "Demographics" });
    await demographics.getByLabel("ABHA number").fill("12-3456-7890-1234");
    await demographics.getByLabel("ABHA address").fill(`asha.${STAMP.toLowerCase()}@sbx`);
    await demographics.getByRole("button", { name: "Link it" }).click();

    await expect(demographics.getByRole("status")).toContainText("stored encrypted");
    // The confirmation does not echo it, and neither does the page: it is a national identifier,
    // and a banner is rendered into a screenshot and a support ticket.
    await expect(page.getByRole("main")).not.toContainText("12345678901234");
    await expect(page.getByRole("main")).not.toContainText("12-3456-7890-1234");
  });

  test("the HL7 log shows what arrived, what was answered, and only the failures on request",
      async ({ page }) => {
    await signIn(page, "dr.rao");
    await page.goto("/sharing/hl7");

    await expect(page.getByRole("heading", { name: "HL7 interface" })).toBeVisible();

    // The acknowledgement codes are shown as themselves. "AE" and "AR" mean different things to a
    // sender -- stop, versus try again -- and a screen that collapsed both into "failed" would
    // throw away the only part of the answer they can act on.
    const table = page.getByRole("table").first();
    await expect(table).toContainText("AA");

    // The raw message is on the page verbatim, because "what did you actually receive" is the
    // question this screen exists to answer and a re-serialised message is this platform's
    // opinion of it rather than the message.
    await expect(page.getByRole("region", { name: /as it arrived/ })).toContainText("MSH");

    await page.getByRole("link", { name: "Only what failed" }).click();
    await expect(page).toHaveURL(/failures=1/);
    // Every row now carries a reason. An interface running a week has tens of thousands of
    // accepted messages and a dozen that matter.
    const failures = page.getByRole("table").first();
    if (await failures.count()) {
      await expect(failures).not.toContainText("accepted");
    }
    await expect(page.getByRole("link", { name: "Show everything" })).toBeVisible();
  });

  test("the interface log is not offered to everybody with a token", async ({ page }) => {
    // Reading whether a consent exists is a clinical question; reading the raw traffic between two
    // hospitals is not, so this is narrower than the rest of the Sharing menu.
    await signIn(page, "reception");
    await page.goto("/sharing/hl7");
    await expect(page.getByRole("main"))
      .toContainText(/does not have access|permission|403/i);
  });

  test("a clinician is not offered the ABHA form", async ({ page }) => {
    await signIn(page, "dr.rao");
    const mrn = await fixtureMrn(page);
    await page.goto(`/patients?q=${encodeURIComponent(mrn)}`);
    await page.getByRole("link", { name: "Open chart" }).first().click();

    await expect(page.getByRole("region", { name: "Demographics" })).toBeVisible();
    // Linking happens at the desk with the card in front of you, so the form is absent rather
    // than present and refused.
    await expect(page.getByRole("button", { name: "Link it" })).toHaveCount(0);
  });
});

/** The consent picker's option text, which carries the patient and the requester. */
async function optionFor(page: Page, artefactId: string): Promise<string> {
  const options = await page
    .getByRole("region", { name: "Send a record under a consent" })
    .getByLabel("Consent")
    .locator("option")
    .allTextContents();
  const found = options.find((option) => option.includes(artefactId));
  expect(found, `consent ${artefactId} was not offered`).toBeTruthy();
  return found as string;
}
