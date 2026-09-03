import { expect, test, type Page } from "@playwright/test";
import { signIn, PASSWORD } from "./sign-in";

/**
 * Editing a patient, and the allergy list.
 *
 * <p>The allergy assertions are the ones that matter. An allergy row is not a remark — its
 * severity is read by the platform, and a life-threatening entry is an instruction to refuse a
 * drug later. So this spec checks that recording one takes a deliberate second step, that removing
 * one does too, and that the confirmation names the substance and what the entry will do rather
 * than asking "are you sure".
 *
 * <p>Every test registers its own patient. The shared fixture chart is searched for by surname by
 * three other specs, and a test that edits a name out from under them would be the ambient-data
 * problem this suite already had once.
 */

/** A surname nothing else in the suite queries, so a run cannot disturb another test. */
function uniqueSurname(): string {
  return `Editcase${Date.now().toString().slice(-8)}${Math.floor(Math.random() * 100)}`;
}

/** Registers a patient through the real form and returns the chart URL it lands on. */
async function freshPatient(page: Page): Promise<{ url: string; surname: string }> {
  const surname = uniqueSurname();
  await page.goto("/patients/new");
  await page.getByLabel("First name").fill("Rohan");
  await page.getByLabel("Surname").fill(surname);
  await page.getByLabel("Date of birth").fill("1978-11-23");
  await page.getByLabel("Sex").selectOption("MALE");
  await page.getByRole("button", { name: "Register patient" }).click();
  await expect(page.getByRole("status")).toContainText(/Registered\. MRN MRN-/);
  return { url: page.url().split("?")[0] ?? page.url(), surname };
}

test.describe("editing a patient", () => {
  test("demographics are corrected, and the service's own validation is what refuses a bad one", async ({
    page,
  }) => {
    test.setTimeout(90_000);
    await signIn(page, "reception");
    const { url } = await freshPatient(page);

    await page.goto(`${url}/edit`);
    await page.getByLabel("Phone", { exact: true }).fill("+971501234567");
    await page.getByLabel("City").fill("Kochi");
    await page.getByLabel("Blood group").fill("O+");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page.getByRole("status")).toContainText("Patient record updated");
    // Read back off the chart, so this is the record rather than the form echoing itself.
    await expect(page.getByRole("main")).toContainText("+971501234567");
    await expect(page.getByRole("main")).toContainText("Kochi");
    await expect(page.getByRole("main")).toContainText("O+");

    // A future date of birth is refused by @Past in the service, and its message is what shows.
    await page.goto(`${url}/edit`);
    await page.getByLabel("Date of birth").fill("2099-01-01");
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.getByRole("main")).toContainText(/past|future/i);
    // Still on the form with the typed value, not bounced back to the chart.
    await expect(page.getByLabel("Date of birth")).toHaveValue("2099-01-01");
  });

  test("a blank optional field is left alone rather than cleared", async ({ page }) => {
    test.setTimeout(90_000);
    await signIn(page, "reception");
    const { url } = await freshPatient(page);

    await page.goto(`${url}/edit`);
    await page.getByLabel("City").fill("Thrissur");
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.getByRole("status")).toContainText("Patient record updated");

    // Save again touching only the phone. City was not retyped, and PATCH is sparse: a blank
    // field means "not provided", so the city must survive.
    await page.goto(`${url}/edit`);
    await page.getByLabel("City").fill("");
    await page.getByLabel("Phone", { exact: true }).fill("+971509999999");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page.getByRole("main")).toContainText("+971509999999");
    await expect(page.getByRole("main")).toContainText("Thrissur");
  });

  test("only an administrator may archive, and archiving keeps the record", async ({ page }) => {
    test.setTimeout(90_000);
    await signIn(page, "reception");
    const { url, surname } = await freshPatient(page);

    // Archiving is DELETE /patients/{id}, which is ADMIN_ONLY, so the front desk is not offered it.
    await expect(page.getByRole("button", { name: "Archive" })).toHaveCount(0);

    await signIn(page, "admin");
    await page.goto(url);
    await page.getByRole("button", { name: "Archive" }).click();
    await expect(page.getByRole("status")).toContainText("Patient archived");
    await expect(page.getByText("archived", { exact: true })).toBeVisible();

    // A "delete" that keeps every row: the chart still opens, and still carries its name.
    await expect(page.getByRole("heading", { level: 1 })).toContainText(surname);

    await page.getByRole("button", { name: "Restore" }).click();
    await expect(page.getByRole("status")).toContainText("Patient restored");
    await expect(page.getByText("archived", { exact: true })).toHaveCount(0);
  });
});

test.describe("the allergy list", () => {
  test("a life-threatening allergy is confirmed before it is recorded", async ({ page }) => {
    test.setTimeout(90_000);
    await signIn(page, "dr.rao");
    const { url } = await freshPatient(page);
    await page.goto(url);

    await page.getByLabel("Substance").fill("Penicillin");
    await page.getByLabel("Reaction").fill("Urticaria and facial swelling");
    await page.getByLabel("Severity").selectOption("LIFE_THREATENING");
    await page.getByRole("button", { name: "Record allergy" }).click();

    // Not recorded yet: a question first, and the question names the substance and the consequence.
    const question = page.getByRole("alert").filter({ hasText: "Penicillin" });
    await expect(question).toContainText(/refuse to dispense/i);
    await expect(question).toContainText(/not warn, refuse/i);
    await expect(page.getByText("No allergies recorded.")).toBeVisible();

    await page.getByRole("button", { name: /Yes, record it as life-threatening/ }).click();
    await expect(page.getByRole("status")).toContainText("Recorded: Penicillin");

    // Now it is on the record, and it is the red banner at the top of the chart.
    const banner = page.getByRole("alert").filter({ hasText: "Allergy alert" });
    await expect(banner).toContainText("Penicillin");
    // The severity that was confirmed is the severity recorded. This asserted "moderate" once,
    // because React resets an uncontrolled <select> after an action and the answer to the question
    // was submitted with the field back at its default.
    await expect(banner).toContainText("life threatening");
    await expect(page.getByRole("listitem").filter({ hasText: "Penicillin" }))
      .toContainText("life threatening");
  });

  test("a milder allergy is recorded in one step", async ({ page }) => {
    test.setTimeout(90_000);
    await signIn(page, "nurse.iqbal");
    const { url } = await freshPatient(page);
    await page.goto(url);

    await page.getByLabel("Substance").fill("Dust mite");
    await page.getByLabel("Severity").selectOption("MILD");
    await page.getByRole("button", { name: "Record allergy" }).click();

    await expect(page.getByRole("status")).toContainText("Recorded: Dust mite");
    // Mild is not critical, so it is on the list without interrupting the chart.
    await expect(page.getByRole("alert").filter({ hasText: "Allergy alert" })).toHaveCount(0);
  });

  test("removing an allergy names what is being removed", async ({ page }) => {
    test.setTimeout(90_000);
    await signIn(page, "dr.rao");
    const { url } = await freshPatient(page);
    await page.goto(url);

    await page.getByLabel("Substance").fill("Sulfonamides");
    await page.getByLabel("Severity").selectOption("SEVERE");
    await page.getByRole("button", { name: "Record allergy" }).click();
    await expect(page.getByRole("status")).toContainText("Recorded: Sulfonamides");

    await page.getByRole("button", { name: "Remove Sulfonamides" }).click();
    await expect(page.getByRole("status")).toContainText("Removed Sulfonamides");
    await expect(page.getByText("No allergies recorded.")).toBeVisible();
  });

  test("the front desk may register a patient but not write their allergies", async ({ page }) => {
    test.setTimeout(90_000);
    await signIn(page, "reception");
    const { url } = await freshPatient(page);
    await page.goto(url);

    // Allergies are CLINICAL_WRITE. Reception registers the patient; it does not decide what the
    // platform will refuse to dispense.
    await expect(page.getByLabel("Substance")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Record allergy" })).toHaveCount(0);
  });
});

test.describe("the initial-password gate", () => {
  test("an account on its initial password can reach nothing but the change-password screen", async ({
    page,
  }) => {
    // Not signIn(): that asserts the dashboard, and this account never gets there.
    await page.goto("/login");
    await page.getByLabel("Username").fill("new.starter");
    await page.getByLabel("Password").fill(PASSWORD);
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page).toHaveURL(/\/change-password$/);
    // Filtered: Next stamps an empty role="alert" route announcer onto every page, so an
    // unfiltered getByRole("alert") is always ambiguous here.
    await expect(
      page.getByRole("alert").filter({ hasText: /still using the password it was issued/i }),
    ).toBeVisible();

    // Asking for anything else lands back here. The redirect is a courtesy; the platform itself
    // issues this session a token with no roles, which is what actually refuses the request.
    for (const path of ["/", "/patients", "/appointments"]) {
      await page.goto(path);
      await expect(page).toHaveURL(/\/change-password$/);
    }
  });

  test("the change is refused when the current password is wrong, with the service's message", async ({
    page,
  }) => {
    await signIn(page, "dr.rao");
    await page.goto("/change-password");

    await page.getByLabel("Current password").fill("definitely-not-the-password");
    await page.getByLabel("New password", { exact: true }).fill("Something!Else2026");
    await page.getByLabel("Confirm new password").fill("Something!Else2026");
    await page.getByRole("button", { name: "Change password" }).click();

    await expect(page.getByRole("main")).toContainText(/current password is incorrect/i);
    // Still signed in: a refused change is not a sign-out.
    await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  });

  test("a mismatched confirmation is caught before the platform is asked", async ({ page }) => {
    await signIn(page, "dr.rao");
    await page.goto("/change-password");

    await page.getByLabel("Current password").fill(PASSWORD);
    await page.getByLabel("New password", { exact: true }).fill("Something!Else2026");
    await page.getByLabel("Confirm new password").fill("Something!Different2026");
    await page.getByRole("button", { name: "Change password" }).click();

    await expect(page.getByRole("main")).toContainText(/does not match the new password/i);
    // And the password was not changed: the original still signs in.
    await signIn(page, "dr.rao");
  });
});

test.describe("the wristband", () => {
  test("the ward prints a band carrying the identity a scan and a person both need", async ({
    page,
  }) => {
    test.setTimeout(90_000);
    await signIn(page, "nurse.iqbal");
    const { url, surname } = await freshPatient(page);

    await page.goto(url);
    await page.getByRole("link", { name: "Wristband" }).click();

    // Both halves, because a band with only one of them fails in exactly the situation the other
    // covers: no bars and nothing scans, no printed identity and nobody can check the band went
    // onto the right wrist.
    const band = page.locator("figure").first();
    await expect(band.locator("svg rect").first()).toBeAttached();
    await expect(band.locator("svg")).toContainText(surname);
    await expect(band.locator("svg")).toContainText(/MRN-\d{4}-\d{6}/);
    // Date of birth rather than age: age changes and a band does not.
    await expect(band.locator("svg")).toContainText("1978-11-23");

    // The instruction beside it, which is why this page has any chrome at all: the check the
    // barcode feeds cannot see whose wrist the band went onto.
    await expect(page.getByRole("main")).toContainText(/check the name and date of birth/i);
  });

  test("a pathologist is not offered a band, and is told whose job it is", async ({ page }) => {
    test.setTimeout(90_000);
    await signIn(page, "nurse.iqbal");
    const { url } = await freshPatient(page);

    await signIn(page, "dr.pathan");
    await page.goto(url);
    // Not offered on the chart...
    await expect(page.getByRole("link", { name: "Wristband" })).toHaveCount(0);

    // ...and typing the address reaches a page that says whose job it is rather than a blank one.
    // The platform refuses the endpoint independently; this is about what the person is told.
    await page.goto(`${url}/wristband`);
    await expect(page.getByRole("main")).toContainText(/front desk/i);
    await expect(page.locator("figure")).toHaveCount(0);
  });
});
