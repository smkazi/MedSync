"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { readForm, refused, withoutBlanks, type FormState } from "@/lib/form";
import { money } from "@/lib/money";
import { submit } from "@/lib/mutate";
import type {
  ChargeItem,
  Claim,
  CreditNote,
  Invoice,
  Payer,
  Refund,
  TaxRate,
} from "@/lib/types";
import {
  CHARGE_ITEM_EDIT_FIELDS,
  CHARGE_ITEM_FIELDS,
  CLAIM_FIELDS,
  CREDIT_NOTE_FIELDS,
  INVOICE_FIELDS,
  LINE_FIELDS,
  NUMBER_FIELDS,
  PAYER_FIELDS,
  PAYMENT_FIELDS,
  REFUND_FIELDS,
  TARIFF_FIELDS,
  TAX_RATE_FIELDS,
} from "./state";

/**
 * The revenue cycle's writes.
 *
 * <p>Every refusal is shown verbatim, and in this module the reason is arithmetic rather than
 * safety: "INV/2026-27/00031 cannot take 400.00: 300.00 is outstanding of 500.00. Somebody may
 * have just paid it." tells a cashier what to collect, and a generic "conflict" tells them the
 * computer is being difficult while a patient waits at the counter with cash in their hand.
 *
 * <p>No amount is computed here. A line total, a tax figure and a balance all come back from the
 * service, because the service does that arithmetic in `BigDecimal` against `numeric(14,2)` and
 * this layer would do it in a double.
 */

/** Row actions land back on a screen with the outcome in the query string. */
function back(path: string, problem: string | null, done: string | null): never {
  revalidatePath(path);
  const params = new URLSearchParams();
  if (problem) params.set("problem", problem);
  if (done) params.set("done", done);
  redirect(`${path}?${params}`);
}

function coerceNumbers(values: Record<string, string>): Record<string, unknown> {
  const body = withoutBlanks(values);
  for (const field of NUMBER_FIELDS) {
    if (body[field] !== undefined) {
      body[field] = Number(body[field]);
    }
  }
  return body;
}

// ---- invoices --------------------------------------------------------------

export async function raiseInvoice(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, INVOICE_FIELDS);
  const result = await submit<Invoice>("/invoices", "POST", withoutBlanks(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/billing");
  redirect(`/billing/${result.data.id}?done=${encodeURIComponent(
    `${result.data.number} raised. Add what is being charged for, then issue it.`,
  )}`);
}

export async function addLine(_previous: FormState, form: FormData): Promise<FormState> {
  const invoiceId = String(form.get("invoiceId") ?? "");
  const values = readForm(form, LINE_FIELDS);
  const result = await submit<Invoice>(`/invoices/${invoiceId}/lines`, "POST",
    coerceNumbers(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath(`/billing/${invoiceId}`);
  const line = result.data.lines[result.data.lines.length - 1];
  return {
    values: {},
    fieldErrors: {},
    error: null,
    // The service's own figures: the line total it computed and the invoice total it recomputed.
    done: `${line?.description ?? "The charge"} added at ${money(line?.lineTotal)}. The invoice now totals ${money(result.data.total)}.`,
  };
}

export async function issueInvoice(form: FormData): Promise<void> {
  const id = String(form.get("invoiceId") ?? "");
  const result = await submit<Invoice>(`/invoices/${id}/issue`, "POST");
  back(`/billing/${id}`, result.ok ? null : result.error,
    result.ok
      ? `${result.data.number} issued. ${money(result.data.total)} is now payable.`
      : null);
}

export async function cancelInvoice(form: FormData): Promise<void> {
  const id = String(form.get("invoiceId") ?? "");
  const reason = String(form.get("reason") ?? "").trim();
  const result = await submit<Invoice>(`/invoices/${id}/cancel`, "POST", { reason });
  back(`/billing/${id}`, result.ok ? null : result.error,
    result.ok ? `${result.data.number} cancelled.` : null);
}

export async function takePayment(_previous: FormState, form: FormData): Promise<FormState> {
  const invoiceId = String(form.get("invoiceId") ?? "");
  const values = readForm(form, PAYMENT_FIELDS);
  const result = await submit<Invoice>(`/invoices/${invoiceId}/payments`, "POST",
    coerceNumbers(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath(`/billing/${invoiceId}`);
  revalidatePath("/billing");
  revalidatePath("/billing/day-book");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: result.data.outstanding > 0
      ? `Received. ${money(result.data.outstanding)} of ${money(result.data.total)} is still outstanding.`
      : `Received. ${result.data.number} is paid in full.`,
  };
}

// ---- credit notes and refunds ----------------------------------------------

/**
 * Issues a credit note: this much of the bill is not owed.
 *
 * <p>Revalidates the day book as well as the invoice, because a credit changes what the day billed
 * even though no money moved. The confirmation quotes the service's own recomputed figures — what
 * is now payable, and what has become refundable — since those are the two numbers that decide
 * whether anybody needs to hand money back next.
 */
export async function issueCreditNote(_previous: FormState, form: FormData): Promise<FormState> {
  const invoiceId = String(form.get("invoiceId") ?? "");
  const values = readForm(form, CREDIT_NOTE_FIELDS);
  const result = await submit<CreditNote>(`/invoices/${invoiceId}/credit-notes`, "POST",
    coerceNumbers(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath(`/billing/${invoiceId}`);
  revalidatePath("/billing");
  revalidatePath("/billing/day-book");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: result.data.invoiceRefundable > 0
      ? `${result.data.number} issued. ${money(result.data.invoicePayable)} is now payable, and ${money(result.data.invoiceRefundable)} is owed back.`
      : `${result.data.number} issued. ${money(result.data.invoicePayable)} is now payable and ${money(result.data.invoiceOutstanding)} is outstanding.`,
  };
}

/**
 * Pays money back.
 *
 * <p>A refusal here is shown exactly as the service worded it, and that matters more than usual:
 * the ordinary refusal is "money goes back only once a credit note says it is not owed", whose fix
 * is an administrator issuing a note rather than the cashier trying a smaller number.
 */
export async function payRefund(_previous: FormState, form: FormData): Promise<FormState> {
  const invoiceId = String(form.get("invoiceId") ?? "");
  const values = readForm(form, REFUND_FIELDS);
  const result = await submit<Refund>(`/invoices/${invoiceId}/refunds`, "POST",
    coerceNumbers(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath(`/billing/${invoiceId}`);
  revalidatePath("/billing");
  revalidatePath("/billing/day-book");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: result.data.invoiceRefundable > 0
      ? `${money(result.data.amount)} paid back. ${money(result.data.invoiceRefundable)} is still owed back.`
      : `${money(result.data.amount)} paid back. Nothing further is owed back on this invoice.`,
  };
}

// ---- claims ----------------------------------------------------------------

export async function raiseClaim(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, CLAIM_FIELDS);
  const result = await submit<Claim>("/claims", "POST", withoutBlanks(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/billing/claims");
  revalidatePath(`/billing/${values.invoiceId}`);
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `Claim raised against ${result.data.payerCode} for ${money(result.data.claimedAmount)}. Submit it when it goes to the payer.`,
  };
}

export async function submitClaim(form: FormData): Promise<void> {
  const id = String(form.get("claimId") ?? "");
  const result = await submit<Claim>(`/claims/${id}/submit`, "POST");
  back("/billing/claims", result.ok ? null : result.error,
    result.ok ? `Claim submitted to ${result.data.payerCode}.` : null);
}

export async function settleClaim(form: FormData): Promise<void> {
  const id = String(form.get("claimId") ?? "");
  const settledAmount = Number(form.get("settledAmount") ?? 0);
  const result = await submit<Claim>(`/claims/${id}/settle`, "POST", { settledAmount });
  back("/billing/claims", result.ok ? null : result.error,
    result.ok
      ? result.data.shortfall > 0
        ? `Settled ${money(result.data.settledAmount)} of ${money(result.data.claimedAmount)}. ${money(result.data.shortfall)} is short and goes to the patient or is written off — somebody has to decide which.`
        : `Settled in full: ${money(result.data.settledAmount)}.`
      : null);
}

export async function denyClaim(form: FormData): Promise<void> {
  const id = String(form.get("claimId") ?? "");
  const reason = String(form.get("reason") ?? "").trim();
  const result = await submit<Claim>(`/claims/${id}/deny`, "POST", { reason });
  back("/billing/claims", result.ok ? null : result.error,
    result.ok ? "Denial recorded, with its reason." : null);
}

// ---- the price list --------------------------------------------------------

export async function addChargeItem(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, CHARGE_ITEM_FIELDS);
  const body = coerceNumbers(values);
  body.taxable = values.taxable === "true";
  const result = await submit<ChargeItem>("/charge-items", "POST", body);
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/billing/charge-items");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `${result.data.name} added at ${money(result.data.unitPrice)}${
      result.data.taxable ? ` plus ${result.data.taxRateCode}` : ", exempt"}.`,
  };
}

export async function updateChargeItem(_previous: FormState, form: FormData): Promise<FormState> {
  const code = String(form.get("code") ?? "");
  const values = readForm(form, CHARGE_ITEM_EDIT_FIELDS);
  const body = coerceNumbers(values);
  if (values.active !== "") {
    body.active = values.active === "true";
  }
  const result = await submit<ChargeItem>(`/charge-items/${code}`, "PATCH", body);
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/billing/charge-items");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    // What changes and what does not is the point of the sentence: invoices already raised keep
    // the price they were raised at, because a line snapshots it.
    done: `${result.data.code} is now ${money(result.data.unitPrice)}${
      result.data.active ? "" : " and no longer charged for"}. Invoices already raised keep the price they carried.`,
  };
}

export async function addTaxRate(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, TAX_RATE_FIELDS);
  const result = await submit<TaxRate>("/tax-rates", "POST", coerceNumbers(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/billing/tax-rates");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `${result.data.code} is ${result.data.percent}% from ${result.data.effectiveFrom}. Any earlier rate for that code was closed the day before.`,
  };
}

export async function addPayer(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, PAYER_FIELDS);
  const body = withoutBlanks(values);
  for (const flag of ["requiresPreauth", "allowsCopay", "settlesDirectly", "taxExempt"]) {
    body[flag] = values[flag as keyof typeof values] === "true";
  }
  const result = await submit<Payer>("/payers", "POST", body);
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/billing/payers");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `${result.data.name} added. Agree its tariffs next — without them its invoices price at the list price, which is a claim that will be short-paid.`,
  };
}

export async function setTariff(_previous: FormState, form: FormData): Promise<FormState> {
  const payerCode = String(form.get("payerCode") ?? "");
  const values = readForm(form, TARIFF_FIELDS);
  const result = await submit<Payer>(`/payers/${payerCode}/tariffs`, "POST",
    coerceNumbers(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/billing/payers");
  const agreed = result.data.tariffs.find((row) => row.chargeItemCode === values.chargeItemCode);
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: agreed
      ? `${result.data.code} pays ${money(agreed.agreedPrice)} for ${agreed.chargeItemName}, against a list price of ${money(agreed.listPrice)}.`
      : `Tariff recorded for ${result.data.code}.`,
  };
}
