import { expect, test, type Page } from "@playwright/test";
import { fixtureMrn } from "./chart";
import { signIn } from "./sign-in";

/**
 * The revenue cycle through the browser.
 *
 * <p>Driven as {@code cashier}, and that is the point rather than a convenience: this account holds
 * CASHIER and nothing else, so every screen it reaches and every button it is offered is what a
 * billing desk really has. The two refusal tests are the other half — a doctor is offered no
 * payment form and a cashier is offered no price field, which is the separation the module exists
 * to enforce written down where somebody can see it fail.
 *
 * <p>No amount asserted here is computed by the test. Each is a figure the platform rendered, so a
 * change to the arithmetic breaks these rather than being quietly mirrored by a test that did the
 * same sum the same wrong way.
 */

/**
 * The fixture patient's MRN, read as somebody who may read the register.
 *
 * <p>A cashier cannot: {@code /patients} is CLINICAL_READ and the billing desk is not in it. That
 * is the narrowing this module is built on rather than an obstacle to route around, so the MRN
 * comes from a front-desk session and the billing work is done in a cashier's — which is also how
 * it happens in a hospital, where somebody at reception registers the patient.
 */
async function mrnFromTheFrontDesk(page: Page): Promise<string> {
  await signIn(page, "reception");
  return fixtureMrn(page);
}

/** Raises a draft for the fixture patient and returns the invoice screen's URL. */
async function raiseInvoice(page: Page, mrn: string, payerLabel?: string): Promise<string> {
  await page.goto("/billing/new");
  await page.getByLabel("MRN or name").fill(mrn);
  await page.getByRole("button", { name: "Search" }).click();

  const row = page.getByRole("row").filter({ hasText: mrn }).first();
  await row.getByRole("link", { name: /Bill this patient|Chosen/ }).click();

  const form = page.getByRole("region", { name: /Who is paying/ });
  await expect(form).toBeVisible();
  if (payerLabel) {
    await form.getByLabel("Payer").selectOption({ label: payerLabel });
  }
  await form.getByRole("button", { name: "Raise the invoice" }).click();

  await expect(page.getByRole("status").last()).toContainText("raised");
  await expect(page).toHaveURL(/\/billing\/[0-9a-f-]{36}/);
  return page.url().split("?")[0] ?? page.url();
}

async function addLine(page: Page, itemLabel: RegExp, qty: string) {
  const charges = page.getByRole("region", { name: "Add a charge" });
  // Selected by its rendered label, which carries the live price — so the option text is read off
  // the page rather than written into the test, and a repriced item does not break this.
  await charges.getByLabel("Charge item").selectOption({ label: await optionLabel(page, itemLabel) });
  await charges.getByLabel("Quantity").fill(qty);
  await charges.getByRole("button", { name: "Add the charge" }).click();
}

/** The full text of the first option matching a pattern — the labels carry live prices. */
async function optionLabel(page: Page, pattern: RegExp): Promise<string> {
  const options = await page
    .getByRole("region", { name: "Add a charge" })
    .getByLabel("Charge item")
    .locator("option")
    .allTextContents();
  const found = options.find((option) => pattern.test(option));
  expect(found, `no charge item matching ${pattern} was offered`).toBeTruthy();
  return found as string;
}

test.describe("the billing desk", () => {
  test("raise, price, issue and collect — and the invoice says so at each step", async ({
    page,
  }) => {
    test.setTimeout(120_000);
    const mrn = await mrnFromTheFrontDesk(page);
    await signIn(page, "cashier");
    const invoice = await raiseInvoice(page, mrn);

    await addLine(page, /^Outpatient consultation/, "1");
    // Scoped to the form that did it: the page also carries the banner the redirect left, and a
    // page-wide status locator is a coin toss between the two.
    await expect(page.getByRole("region", { name: "Add a charge" }).getByRole("status"))
      .toContainText("500.00");

    // The line carries what it was priced at, and the tax the invoice's own date resolved.
    const lines = page.getByRole("region", { name: "What is being charged for" });
    await expect(lines.getByRole("row").filter({ hasText: "CONSULT_OP" })).toContainText("500.00");
    await expect(lines.getByRole("row").filter({ hasText: "CONSULT_OP" })).toContainText("exempt");

    await page.getByRole("button", { name: /^Issue / }).click();
    await expect(page.getByRole("status").last()).toContainText("payable");
    await expect(page.getByText("issued", { exact: true })).toBeVisible();

    // An issued invoice takes no more lines: that form is gone, not disabled.
    await expect(page.getByRole("region", { name: "Add a charge" })).toHaveCount(0);

    const payment = page.getByRole("region", { name: "Take a payment" });
    await payment.getByLabel("Amount").fill("900.00");
    await payment.getByLabel("How it arrived").selectOption("CASH");
    await payment.getByRole("button", { name: "Record the payment" }).click();
    // The service's own refusal, naming what is actually outstanding.
    await expect(payment.getByRole("alert")).toContainText("500.00");

    await payment.getByLabel("Amount").fill("500.00");
    await payment.getByLabel("How it arrived").selectOption("UPI");
    await payment.getByLabel("Reference").fill("UPI-E2E");
    await payment.getByRole("button", { name: "Record the payment" }).click();

    // Asserted on the state rather than on a banner, deliberately: paying the balance in full
    // removes the payment form — there is nothing left to collect — and the confirmation goes
    // with it. What the screen must say afterwards is that the invoice is paid and by what.
    await expect(page.getByText("paid", { exact: true })).toBeVisible();
    await expect(page.getByRole("region", { name: "Take a payment" })).toHaveCount(0);
    await expect(page.getByRole("region", { name: "Payments" })).toContainText("UPI-E2E");
    await expect(page.getByRole("region", { name: "Payments" })).toContainText("cashier");
    await expect(page.getByRole("region", { name: "Payments" })).toContainText("500.00");

    await page.goto(invoice);
    await expect(page.getByText("paid", { exact: true })).toBeVisible();
    // Paid, so no cancellation is offered: correcting it is a credit note and a refund, which the
    // journey below drives. Cancelling would say the treatment was never billed while the cash sat
    // in the drawer, and no reconciliation recovers from that.
    await expect(page.getByRole("region", { name: "Cancel it" })).toHaveCount(0);
  });

  test("a paid bill is corrected by an administrator and paid back by a cashier", async ({
    page,
  }) => {
    test.setTimeout(180_000);
    const mrn = await mrnFromTheFrontDesk(page);
    await signIn(page, "cashier");
    const invoice = await raiseInvoice(page, mrn);
    await addLine(page, /^Outpatient consultation/, "1");
    await page.getByRole("button", { name: /^Issue / }).click();

    const payment = page.getByRole("region", { name: "Take a payment" });
    await payment.getByLabel("Amount").fill("500.00");
    await payment.getByLabel("How it arrived").selectOption("CASH");
    await payment.getByRole("button", { name: "Record the payment" }).click();
    await expect(page.getByText("paid", { exact: true })).toBeVisible();

    // The cashier is offered neither half yet: nothing is refundable until a note exists, and
    // deciding a charge is not owed was never the till's to make.
    await expect(page.getByRole("region", { name: "Issue a credit note" })).toHaveCount(0);
    await expect(page.getByRole("region", { name: "Pay a refund" })).toHaveCount(0);

    await signIn(page, "admin");
    await page.goto(invoice);
    const credit = page.getByRole("region", { name: "Issue a credit note" });

    // The service's own floor on the reason, surfaced against the field that broke it rather than
    // as a banner: the service names `reason`, so the message belongs on `reason`.
    await credit.getByLabel("How much is not owed").fill("500.00");
    await credit.getByLabel("Why").fill("adjustment");
    await credit.getByRole("button", { name: "Issue the credit note" }).click();
    await expect(credit.getByLabel("Why")).toHaveAttribute("aria-invalid", "true");
    await expect(credit).toContainText("size must be between 20 and 255");

    await credit.getByLabel("Why").fill("Consultation was not given; billed in error at the desk.");
    await credit.getByRole("button", { name: "Issue the credit note" }).click();

    // Asserted on the state rather than on a banner, for the reason the payment above is: crediting
    // the bill in full leaves nothing further to credit, so the form goes and its confirmation with
    // it. What the screen must carry afterwards is the note itself and the money now owed back.
    await expect(page.getByRole("region", { name: "Credit notes" }))
      .toContainText("billed in error");
    await expect(page.getByRole("region", { name: "Issue a credit note" })).toHaveCount(0);
    // The charged total stands beside the credit rather than being rewritten by it.
    await expect(page.getByRole("main")).toContainText("Owed back");
    await expect(page.getByRole("main")).toContainText("Credited");

    // The register is readable by the cashier, and now so is the payout — which was refused a
    // moment ago on the same invoice, by the same account, before a note authorised it.
    await signIn(page, "cashier");
    await page.goto(invoice);
    const refund = page.getByRole("region", { name: "Pay a refund" });
    await expect(refund).toBeVisible();
    await expect(page.getByRole("region", { name: "Issue a credit note" })).toHaveCount(0);

    await refund.getByLabel("Amount").fill("500.00");
    await refund.getByLabel("How it goes back").selectOption("BANK_TRANSFER");
    await refund.getByLabel("Reference").fill("UTR-E2E-1");
    await refund.getByRole("button", { name: "Record the refund" }).click();

    // Paid back in full, so the form goes the way the payment form did: there is nothing left to
    // hand over, and what the screen must say instead is where the money went.
    await expect(page.getByRole("region", { name: "Refunds" })).toContainText("UTR-E2E-1");
    await expect(page.getByRole("region", { name: "Refunds" })).toContainText("cashier");
    await page.goto(invoice);
    await expect(page.getByRole("region", { name: "Pay a refund" })).toHaveCount(0);

    // And the day's cash-up separates what came in from what went back out, rather than netting
    // them into one figure that would balance and explain nothing.
    await page.goto("/billing/day-book");
    await expect(page.getByRole("main")).toContainText("Net taken");
    await expect(page.getByRole("main")).toContainText("bank transfer");
  });

  test("a payer's tariff prices the invoice, and the claim settles onto it", async ({ page }) => {
    test.setTimeout(120_000);
    const mrn = await mrnFromTheFrontDesk(page);
    await signIn(page, "cashier");
    await raiseInvoice(page, mrn, "Third-party administrator (sample) (TPA_A)");

    await addLine(page, /^Outpatient consultation/, "1");
    const lines = page.getByRole("region", { name: "What is being charged for" });
    await expect(lines.getByRole("row").filter({ hasText: "CONSULT_OP" }))
      .toContainText("400.00");

    await page.getByRole("button", { name: /^Issue / }).click();
    await expect(page.getByRole("status").last()).toContainText("payable");

    const claimForm = page.getByRole("region", { name: "Claim from the payer" });
    await claimForm.getByRole("button", { name: "Raise the claim" }).click();
    // This payer requires a number, and says so rather than raising a claim that will bounce.
    await expect(claimForm.getByRole("alert")).toContainText("pre-authorisation");

    await claimForm.getByLabel("Pre-authorisation number").fill("PA-E2E-1");
    await claimForm.getByRole("button", { name: "Raise the claim" }).click();

    // Same as the payment above: one invoice takes one claim, so raising it replaces the form
    // with the claim itself. The card is the assertion.
    const raised = page.getByRole("region", { name: "The claim" });
    await expect(raised).toContainText("400.00");
    await expect(raised).toContainText("PA-E2E-1");
    await expect(page.getByRole("region", { name: "Claim from the payer" })).toHaveCount(0);

    await page.goto("/billing/claims");
    const row = page.getByRole("row").filter({ hasText: "PA-E2E-1" }).first();
    await row.getByRole("button", { name: "Submit to payer" }).click();
    await expect(page.getByRole("status").last()).toContainText("submitted");

    const submitted = page.getByRole("row").filter({ hasText: "PA-E2E-1" }).first();
    await submitted.getByRole("spinbutton").fill("300.00");
    await submitted.getByRole("button", { name: "Settle" }).click();
    // Settled short is not settled: the shortfall is named, and somebody has to decide about it.
    await expect(page.getByRole("status").last()).toContainText("100.00");
    await expect(page.getByRole("status").last()).toContainText(/written off|patient/);
  });

  test("the day book splits what was collected by how it arrived", async ({ page }) => {
    await signIn(page, "cashier");
    await page.goto("/billing/day-book");

    await expect(page.getByText("Collected", { exact: true })).toBeVisible();
    await expect(page.getByText("Outstanding", { exact: true })).toBeVisible();
    // The earlier tests collected cash and a settlement in this run, so both appear. Taken as the
    // first table rather than the only one: a day with a refund on it renders a second, and money
    // out belongs nowhere near the tally of how money arrived.
    const table = page.getByRole("table").first();
    await expect(table).toContainText("upi");
  });

  test("a cashier reads prices and is offered no way to change one", async ({ page }) => {
    await signIn(page, "cashier");
    await page.goto("/billing/charge-items");

    await expect(page.getByRole("row").filter({ hasText: "CONSULT_OP" })).toContainText("500.00");
    // Reading a price list is a cashier's job; setting one is not, so the form is absent rather
    // than present and refused.
    await expect(page.getByRole("region", { name: "Add a charge item" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Save" })).toHaveCount(0);

    await page.goto("/billing/tax-rates");
    await expect(page.getByRole("row").filter({ hasText: "GST_EXEMPT" })).toBeVisible();
    await expect(page.getByRole("region", { name: "Change a rate" })).toHaveCount(0);
  });

  test("an administrator may reprice, and the screen says what that does not change", async ({
    page,
  }) => {
    await signIn(page, "admin");
    await page.goto("/billing/charge-items");

    await expect(page.getByRole("region", { name: "Add a charge item" })).toBeVisible();
    await expect(page.getByText(/nothing that has already been raised/)).toBeVisible();
  });

  test("a doctor reads what a patient was billed and is offered no payment form", async ({
    page,
  }) => {
    await signIn(page, "dr.rao");
    await page.goto("/billing");

    await expect(page.getByRole("heading", { name: "Invoices", exact: true })).toBeVisible();
    // No write is offered anywhere: not the link that raises one, not a payment form on a bill.
    await expect(page.getByRole("link", { name: "Raise an invoice" })).toHaveCount(0);

    const open = page.getByRole("link", { name: "Open" }).first();
    if (await open.count()) {
      await open.click();
      await expect(page.getByRole("region", { name: "Take a payment" })).toHaveCount(0);
      await expect(page.getByRole("region", { name: "Add a charge" })).toHaveCount(0);
    }
  });

  test("the laboratory is not offered the money at all", async ({ page }) => {
    await signIn(page, "lab.tech");

    await expect(page.getByRole("button", { name: "Billing", exact: true })).toHaveCount(0);
    await page.goto("/billing");
    // The API refuses it, and the screen shows the refusal rather than an empty list that would
    // read as "this patient owes nothing".
    await expect(page.getByRole("main"))
      .toContainText(/does not have access|permission|403/i);
  });
});
