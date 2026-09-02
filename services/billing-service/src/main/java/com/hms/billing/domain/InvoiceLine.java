package com.hms.billing.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One charged thing, priced as it was on the day.
 *
 * <p>Everything on this row is a snapshot: the description, the unit price, the tax percentage. It
 * is the deliberate opposite of how a room's directions are handled elsewhere in this platform — a
 * room must always show its current name, and an invoice must never change after it is raised. A
 * line that joined to the charge list would silently re-price last year's bill when somebody
 * corrected a typo in a charge item's name.
 */
@Entity
@Table(name = "invoice_lines")
public class InvoiceLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "charge_item_code", nullable = false, length = 32, updatable = false)
    private String chargeItemCode;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "qty", nullable = false, precision = 10, scale = 2)
    private BigDecimal qty;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "discount", nullable = false, precision = 14, scale = 2)
    private BigDecimal discount = Money.scale(BigDecimal.ZERO);

    @Column(name = "tax_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxPercent = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxAmount = Money.scale(BigDecimal.ZERO);

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal = Money.scale(BigDecimal.ZERO);

    protected InvoiceLine() {
    }

    public InvoiceLine(String chargeItemCode, String description, BigDecimal qty,
                       BigDecimal unitPrice, BigDecimal discount, BigDecimal taxPercent) {
        this.chargeItemCode = chargeItemCode;
        this.description = description;
        this.qty = qty;
        this.unitPrice = unitPrice;
        this.discount = Money.scale(discount);
        this.taxPercent = taxPercent == null ? BigDecimal.ZERO : taxPercent;
        recompute();
    }

    /** Quantity times price, before discount and tax. */
    public BigDecimal gross() {
        return Money.scale(qty.multiply(unitPrice));
    }

    /**
     * The tax is charged on the discounted amount, not the gross.
     *
     * <p>Which is both the legally correct order and the one people get wrong: taxing the list
     * price and then discounting collects tax on money nobody paid.
     */
    public void recompute() {
        BigDecimal taxable = gross().subtract(discount);
        this.taxAmount = Money.taxOn(taxable, taxPercent);
        this.lineTotal = Money.scale(taxable.add(taxAmount));
    }

    void setInvoice(Invoice invoice) {
        this.invoice = invoice;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public String getChargeItemCode() {
        return chargeItemCode;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public BigDecimal getTaxPercent() {
        return taxPercent;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}
