/**
 * Billing field names and vocabulary. Out of `actions.ts` because a `"use server"` module may
 * export only async functions — the rule `patients/new/state.ts` exists to record.
 */

export const INVOICE_FIELDS = [
  "patientId",
  "patientMrn",
  "encounterId",
  "payerCode",
  "invoiceDate",
] as const;

export const LINE_FIELDS = ["chargeItemCode", "qty", "discount", "description"] as const;

export const PAYMENT_FIELDS = ["amount", "method", "reference"] as const;

export const CHARGE_ITEM_FIELDS = [
  "code",
  "name",
  "departmentCode",
  "unitPrice",
  "taxable",
  "taxRateCode",
] as const;

export const CHARGE_ITEM_EDIT_FIELDS = ["name", "unitPrice", "active"] as const;

export const TAX_RATE_FIELDS = ["code", "name", "percent", "effectiveFrom"] as const;

export const PAYER_FIELDS = [
  "code",
  "name",
  "requiresPreauth",
  "allowsCopay",
  "settlesDirectly",
  "taxExempt",
] as const;

export const TARIFF_FIELDS = ["chargeItemCode", "price"] as const;

export const CLAIM_FIELDS = ["invoiceId", "preauthNo"] as const;

export const CREDIT_NOTE_FIELDS = ["amount", "reason"] as const;

/**
 * `creditNoteId` is posted when the payout draws on a single note, which is the ordinary case and
 * the one somebody asks about afterwards. It is optional because a refund may settle several, and
 * the authorisation is enforced on the sum rather than on the link.
 */
export const REFUND_FIELDS = ["amount", "method", "creditNoteId", "reference"] as const;

/**
 * Fields the platform expects as numbers rather than strings.
 *
 * <p>Money included, which looks like the mistake this module exists to avoid and is not: these
 * are values a person typed, on their way to a `BigDecimal` and a `numeric(14,2)` column, and no
 * arithmetic happens to them in between. What must never happen is a screen adding two of them
 * together — every total rendered here is one the service computed.
 */
export const NUMBER_FIELDS = ["qty", "discount", "unitPrice", "amount", "percent", "price"];

/**
 * The minimum a credit note's reason may be, matching `@Size(min = 20)` on the request.
 *
 * <p>Here so the form can say the number before the service refuses on it. A person typing
 * "adjustment" into a box that accepts it and then reads a 400 has been made to do the work twice.
 */
export const CREDIT_REASON_MIN = 20;

/** How money arrived. In code because each value is a different reconciliation. */
export const PAYMENT_METHODS = [
  { value: "CASH", label: "Cash" },
  { value: "CARD", label: "Card" },
  { value: "UPI", label: "UPI" },
  { value: "BANK_TRANSFER", label: "Bank transfer" },
  { value: "INSURANCE", label: "Insurance settlement" },
] as const;
