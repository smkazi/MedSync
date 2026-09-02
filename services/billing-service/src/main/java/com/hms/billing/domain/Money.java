package com.hms.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Two decimal places, HALF_UP, at every boundary.
 *
 * <p>One place rather than a rounding call at each of thirty arithmetic sites, because the failure
 * mode of the alternative is invisible: a total that differs from the sum of its lines by one paisa
 * is not a crash, it is a reconciliation that never closes and a patient arguing about a bill.
 *
 * <p>HALF_UP because it is what a person doing the arithmetic by hand does, and an invoice has to
 * agree with the person checking it. Banker's rounding is defensible in aggregate and indefensible
 * on a single receipt somebody is reading.
 *
 * <p>{@code double} appears nowhere in this module. Not in the entities, not in the DTOs, not in
 * the tax arithmetic.
 */
public final class Money {

    /** Every amount in this service. Matches `numeric(14,2)` in the schema exactly. */
    public static final int SCALE = 2;

    private Money() {
    }

    public static BigDecimal scale(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP)
                : amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Tax on an amount, at a percentage.
     *
     * <p>Rounded once, at the end, on the line — not on each unit and not on the invoice total.
     * Rounding per unit and multiplying magnifies the error by the quantity; rounding only at the
     * invoice level produces a total that does not equal the sum of the printed lines, which is
     * the version a patient notices.
     */
    public static BigDecimal taxOn(BigDecimal taxableAmount, BigDecimal percent) {
        if (taxableAmount == null || percent == null || percent.signum() == 0) {
            return scale(BigDecimal.ZERO);
        }
        return scale(taxableAmount.multiply(percent)
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
    }
}
