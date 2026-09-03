package com.hms.billing.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One cashier's drawer, from opening float to counted close.
 *
 * <p>A shift and not a day. A drawer is handed over between people, and a day's takings cannot say
 * which of the three who sat at that counter is short — which is the only question a cash-up is
 * asked.
 *
 * <p>Everything the close records is frozen onto the row rather than recomputed on read. A figure
 * somebody has signed against must not move afterwards because an invoice was corrected later: the
 * count said what it said at the time, and a correction is a separate fact.
 */
@Entity
@Table(name = "cash_sessions")
public class CashSession extends BaseEntity {

    @Column(name = "cashier", nullable = false, length = 64, updatable = false)
    private String cashier;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "opening_float", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal openingFloat;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by", length = 64)
    private String closedBy;

    @Column(name = "declared_cash", precision = 14, scale = 2)
    private BigDecimal declaredCash;

    @Column(name = "expected_cash", precision = 14, scale = 2)
    private BigDecimal expectedCash;

    @Column(name = "variance", precision = 14, scale = 2)
    private BigDecimal variance;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BillingEnums.CashSessionStatus status = BillingEnums.CashSessionStatus.OPEN;

    protected CashSession() {
    }

    public CashSession(String cashier, BigDecimal openingFloat) {
        this.cashier = cashier;
        this.openingFloat = Money.scale(openingFloat);
    }

    /**
     * Counts the drawer and closes the shift.
     *
     * <p>The variance is computed here from the two numbers rather than accepted from the caller.
     * A cash-up whose difference is supplied by whoever is being reconciled is not a control, and
     * the arithmetic is the one thing about this table nobody should be able to disagree with.
     */
    public void close(BigDecimal declared, BigDecimal expected, String closedBy, String notes) {
        this.declaredCash = Money.scale(declared);
        this.expectedCash = Money.scale(expected);
        this.variance = Money.scale(this.declaredCash.subtract(this.expectedCash));
        this.closedBy = closedBy;
        this.closedAt = Instant.now();
        this.notes = notes;
        this.status = BillingEnums.CashSessionStatus.CLOSED;
    }

    public boolean isOpen() {
        return status == BillingEnums.CashSessionStatus.OPEN;
    }

    /** Over, short, or exact — the word a person uses for the number. */
    public String varianceDescription() {
        if (variance == null) {
            return "not counted";
        }
        int sign = variance.signum();
        return sign == 0 ? "exact" : sign > 0 ? "over" : "short";
    }

    public String getCashier() {
        return cashier;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public BigDecimal getOpeningFloat() {
        return openingFloat;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public BigDecimal getDeclaredCash() {
        return declaredCash;
    }

    public BigDecimal getExpectedCash() {
        return expectedCash;
    }

    public BigDecimal getVariance() {
        return variance;
    }

    public String getNotes() {
        return notes;
    }

    public BillingEnums.CashSessionStatus getStatus() {
        return status;
    }
}
