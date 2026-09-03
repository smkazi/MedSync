package com.hms.billing.domain;

/** The vocabulary of the revenue cycle. */
public final class BillingEnums {

    private BillingEnums() {
    }

    /**
     * An invoice's life.
     *
     * <p>DRAFT is where charges accumulate — an in-patient's invoice collects bed-days for a week
     * before anybody looks at it — and ISSUED is the moment it becomes a document a patient is
     * asked to pay. Lines can be added to a draft and not to an issued invoice, which is the whole
     * reason the two states are separate.
     *
     * <p>PAID is derived from the numbers, not set by hand: it happens when {@code amount_paid}
     * reaches {@code total}, in the same statement that takes the money.
     */
    public enum InvoiceStatus {
        DRAFT, ISSUED, PAID, CANCELLED
    }

    /**
     * How money arrived.
     *
     * <p>In code because each value is a different reconciliation: cash is counted in a drawer, a
     * card settles through a terminal's own batch, UPI arrives with a reference somebody can look
     * up, and INSURANCE is a payer settling a claim rather than a person paying. A configurable
     * list would let somebody add a method no reconciliation knows what to do with.
     */
    public enum PaymentMethod {
        CASH, CARD, UPI, BANK_TRANSFER, INSURANCE
    }

    /**
     * Where a claim is.
     *
     * <p>PARTIALLY_SETTLED is its own value rather than a settled amount less than the claim,
     * because the difference is a decision somebody has to act on: the balance either goes back to
     * the patient or is written off, and a status that could not distinguish "settled" from
     * "settled less than we asked" would hide that decision.
     */
    public enum ClaimStatus {
        DRAFT, SUBMITTED, SETTLED, PARTIALLY_SETTLED, DENIED
    }

    /**
     * Where a charge came from.
     *
     * <p>Half of the key that stops a patient being billed twice: {@code (sourceType, sourceId,
     * chargeItemCode)} is the primary key of {@code posted_charges}, so a redelivered event
     * collides with the charge it already produced. In code because each value names a service
     * whose events this one consumes, and a value with no producer behind it would be a charge
     * nobody can trace.
     */
    public enum ChargeSource {
        APPOINTMENT, LAB_ORDER, DISPENSE, ADMISSION, MANUAL
    }

    /** A cash drawer's shift. There is no third state: it is either being used or signed off. */
    public enum CashSessionStatus {
        OPEN, CLOSED
    }
}
