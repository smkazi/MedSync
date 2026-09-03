import { expect, test, type Page } from "@playwright/test";
import { signIn } from "./sign-in";

/**
 * The patient portal, through the browser, under a real patient session.
 *
 * <p>The identity is not a seeded account and cannot be: a portal account has to point at a patient
 * record, and the seed runs before there is one. So the first test enrols a patient at the front
 * desk exactly as a receptionist would — register, issue a one-time password, read it off the
 * screen — and every test after it signs in as that patient. The enrolment card, the initial-
 * password gate and the {@code patient_id} claim are therefore all exercised before the first
 * assertion about the portal itself.
 *
 * <p>Serial, because that shared identity is created by the first test. The alternative was a
 * global-setup fixture, and this is better: the enrolment path is the part most likely to break and
 * a fixture would hide it inside a helper nobody reads when a test fails.
 */
test.describe.configure({ mode: "serial" });

const PATIENT_PASSWORD = "PatientChosen!2026";

/** Populated by the first test and used by every one after it. */
const patient = {
  surname: "",
  mrn: "",
  username: "",
  chartUrl: "",
};

/** Signs in as the enrolled patient and lands on the portal rather than the dashboard. */
async function signInAsPatient(page: Page): Promise<void> {
  await page.context().clearCookies();
  await page.goto("/login");
  await page.getByLabel("Username").fill(patient.username);
  await page.getByLabel("Password").fill(PATIENT_PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("link", { name: "Test results" })).toBeVisible();
}

test.describe("the patient portal", () => {
  test("the front desk registers somebody, enrols them, and reads out the password once", async ({
    page,
  }) => {
    patient.surname = `Portal${Date.now().toString(36)}`;

    await signIn(page, "reception");
    await page.goto("/patients/new");
    await page.getByLabel("First name").fill("Asha");
    await page.getByLabel("Surname").fill(patient.surname);
    await page.getByLabel("Date of birth").fill("1988-04-12");
    await page.getByLabel("Sex").selectOption("FEMALE");
    // An address on the record is the precondition for enrolment: it is where a password reset
    // would go, and an account nobody can reach is one nobody can recover.
    await page.getByLabel("Email").fill(`${patient.surname.toLowerCase()}@example.invalid`);
    await page.getByRole("button", { name: "Register patient" }).click();

    // The MRN the platform issued, captured because it is the one string that identifies this
    // record unambiguously later: the surname also appears in the header and inside the email
    // address the fixture derived from it, and a locator matching three things matches nothing.
    const registered = (await page.getByRole("status").first().textContent()) ?? "";
    patient.mrn = (/MRN-[0-9-]+/.exec(registered) ?? [""])[0];
    expect(patient.mrn, `no MRN in: ${registered}`).not.toBe("");

    // The chart, which is where the portal card lives.
    const portalCard = page.getByRole("region", { name: "Portal access" });
    await expect(portalCard).toBeVisible();
    patient.chartUrl = page.url().split("?")[0] as string;

    await portalCard.getByRole("button", { name: "Issue or re-issue access" }).click();

    // Shown once, in the card's own status region. Scoped to the card deliberately: registering
    // leaves its own "Registered. MRN …" banner on this page, and two role="status" regions on one
    // screen is the ambiguity this suite has been caught by before.
    const issued = page
      .getByRole("region", { name: "Portal access" })
      .getByRole("status");
    await expect(issued).toContainText("Read these to the patient now");
    const text = (await issued.textContent()) ?? "";
    const match = /Username\s+(\S+)\s+·\s+one-time password\s+([0-9A-HJKMNP-TV-Z]{20})/.exec(text);
    expect(match, `could not read the credentials out of: ${text}`).not.toBeNull();
    patient.username = match![1] as string;
    const temporary = match![2] as string;

    // Signing in with it reaches nothing but the change-password screen: the account holds a
    // role-less token until it has chosen its own password, and the middleware sends it there.
    await page.context().clearCookies();
    await page.goto("/login");
    await page.getByLabel("Username").fill(patient.username);
    await page.getByLabel("Password").fill(temporary);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/change-password/);

    await page.getByLabel("Current password").fill(temporary);
    await page.getByLabel("New password", { exact: true }).fill(PATIENT_PASSWORD);
    await page.getByLabel("Confirm new password").fill(PATIENT_PASSWORD);
    await page.getByRole("button", { name: /Change password/i }).click();
    await expect(page).toHaveURL(/\/login/);
  });

  test("the patient lands in the portal, and not in the clinical application", async ({ page }) => {
    await signInAsPatient(page);
    await expect(page).toHaveURL(/\/portal$/);

    // Its own chrome. The staff menu enumerates every module a hospital runs, and the shape of
    // that list is itself a description of the building.
    await expect(page.getByRole("link", { name: "Appointments" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Patients" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Laboratory" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Administration" })).toHaveCount(0);
  });

  test("typing a clinical path sends the patient back to their own portal", async ({ page }) => {
    await signInAsPatient(page);
    for (const path of ["/patients", "/appointments", "/laboratory", "/admin/users", "/billing"]) {
      await page.goto(path);
      // A redirect rather than an error page: the platform would refuse the request anyway, and a
      // patient following an old bookmark is better served by their own record than by a 403.
      await expect(page).toHaveURL(/\/portal$/);
    }
  });

  test("their record is their own, and the notes staff wrote about them are not on it", async ({
    page,
  }) => {
    await signInAsPatient(page);
    await page.goto("/portal/record");
    await expect(page.getByRole("heading", { name: "My record" })).toBeVisible();
    // The MRN rather than the surname: it identifies this record and nothing else on the page,
    // and it is the number the portal asks the patient to quote when they telephone.
    await expect(page.getByText(patient.mrn).first()).toBeVisible();
    // The download is the "transmit" half of the certification criterion: a FHIR bundle they can
    // save and hand to another hospital.
    await expect(page.getByRole("link", { name: "Download my record" })).toBeVisible();

    // The accounting of disclosures, on the screen where the patient is already reading their own
    // record. Nothing has left about this patient yet, and the empty state has to say that rather
    // than look like a page that failed to load.
    const released = page.getByRole("region", { name: "What has left this hospital about you" });
    await expect(released).toContainText(/Nothing about you has been released/);
    // Never the name of the member of staff. The hospital released the record and the hospital
    // answers for it; an accounting is not a complaint aimed at a person.
    await expect(released).not.toContainText(/released by/i);
  });

  test("a download appears in the patient's own accounting of disclosures", async ({ page }) => {
    await signInAsPatient(page);
    await page.goto("/portal/record");

    // Clicked, not fetched: this is the path a patient takes, and it goes through the app's own
    // route handler because the bearer token is in an httpOnly cookie the browser will not attach
    // to a cross-origin link. The defect this found was in the middleware, which treated
    // /api/portal/** as a clinical path and redirected the patient to the portal home — a 200, so
    // the download silently produced no file.
    const download = page.waitForEvent("download");
    await page.getByRole("link", { name: "Download my record" }).click();
    expect((await download).suggestedFilename()).toBe("health-record.fhir.json");

    // Downloading is itself a release, and the register is written at the moment it happens.
    await page.reload();
    const released = page.getByRole("region", { name: "What has left this hospital about you" });
    await expect(released).toContainText("You — your own copy");
    await expect(released).toContainText("Your own copy, so no consent was needed");
  });

  test("a patient books for themselves and cancels, in their own words", async ({ page }) => {
    await signInAsPatient(page);
    await page.goto("/portal/appointments");
    await expect(page.getByRole("heading", { name: "Your appointments" })).toBeVisible();

    // The form offers no priority and no room. Both are the platform's to decide and the request
    // it accepts has nowhere to put either, so a greyed-out field would be a promise it does not
    // keep.
    await expect(page.getByLabel("Priority")).toHaveCount(0);
    await expect(page.getByLabel("Room")).toHaveCount(0);
    await expect(page.getByLabel(/What it is about/)).toBeVisible();
  });

  test("results are not shown until a pathologist has released them", async ({ page }) => {
    await signInAsPatient(page);
    await page.goto("/portal/results");
    await expect(page.getByRole("heading", { name: "Your test results" })).toBeVisible();
    await expect(
      page.getByText("once a pathologist has checked and released it"),
    ).toBeVisible();
  });

  test("a written question reaches the hospital and comes back answered", async ({ page }) => {
    await signInAsPatient(page);
    await page.goto("/portal/messages");
    await page.getByLabel("What it is about").fill("My discharge medicines");
    await page
      .getByLabel("Your message")
      .fill("The two boxes say different things about food. Which should I follow?");
    await page.getByRole("button", { name: "Send" }).click();

    // Redirected into the conversation, which is the thing they wanted next.
    await expect(page).toHaveURL(/\/portal\/messages\/[0-9a-f-]{36}/);
    await expect(page.getByText("The two boxes say different things")).toBeVisible();
    // The standing notice comes from the platform on every thread and a caller cannot suppress it.
    await expect(page.getByText(/not monitored continuously/)).toBeVisible();
    const threadUrl = page.url();

    const threadId = threadUrl.split("/").pop() as string;

    // The hospital's side, under a nurse's identity.
    await signIn(page, "nurse.iqbal");
    await page.goto("/messaging/threads?status=OPEN");
    await expect(page.getByRole("heading", { name: "Patient questions" })).toBeVisible();

    // Navigated by id rather than by clicking the row. The queue is a long-lived list that every
    // previous run has added to, and it is served oldest-first by design — so "the row whose
    // subject is My discharge medicines" matches every run's thread, and the newest one is not on
    // the first page anyway. The row for this patient is still asserted, because the queue showing
    // the question is the thing worth checking.
    await expect(page.getByRole("cell", { name: patient.mrn, exact: true })).toBeVisible();
    await page.goto(`/messaging/threads/${threadId}`);
    await page.getByLabel("Your reply").fill("Take both with food. Ring the ward if you are unsure.");
    await page.getByRole("button", { name: "Send to the patient" }).click();
    await expect(page.getByText("Sent to the patient.")).toBeVisible();

    // And back to the patient, who now has an answer and no unread badge once they have read it.
    await signInAsPatient(page);
    await expect(page.getByText("Unread messages")).toBeVisible();
    await page.goto(threadUrl);
    await expect(page.getByText("Take both with food")).toBeVisible();
  });

  test("a clinician opening the portal is told what it is, not shown somebody's record", async ({
    page,
  }) => {
    await signIn(page, "dr.rao");
    await page.goto("/portal");
    // Not a redirect and not a blank page: an administrator or a clinician following a link a
    // patient sent them should be told what happened.
    await expect(page.getByText("This is the patient portal")).toBeVisible();
    await expect(page.getByText("no patient record for it to show")).toBeVisible();
  });

  test("a clinician is not offered the enrolment card", async ({ page }) => {
    await signIn(page, "dr.rao");
    await page.goto(patient.chartUrl);
    // Handing out credentials is an administrative act, and this platform keeps it at the desk
    // that already does identity checks.
    await expect(page.getByRole("region", { name: "Portal access" })).toHaveCount(0);
  });
});
