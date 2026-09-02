package com.hms.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * What one payer has agreed to pay for one item.
 *
 * <p>A join table with no surrogate key: the pair is the row. Absent means the list price applies,
 * which is a real answer rather than missing data — most payers negotiate a handful of items and
 * take the list price for the rest.
 */
@Entity
@Table(name = "payer_tariffs")
public class PayerTariff {

    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "payer_code", nullable = false, length = 32)
        private String payerCode;

        @Column(name = "charge_item_code", nullable = false, length = 32)
        private String chargeItemCode;

        protected Key() {
        }

        public Key(String payerCode, String chargeItemCode) {
            this.payerCode = payerCode;
            this.chargeItemCode = chargeItemCode;
        }

        public String getPayerCode() {
            return payerCode;
        }

        public String getChargeItemCode() {
            return chargeItemCode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(payerCode, that.payerCode)
                    && Objects.equals(chargeItemCode, that.chargeItemCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(payerCode, chargeItemCode);
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "price", nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    @Column(name = "version")
    private Long version;

    @Column(name = "created_at", insertable = false, updatable = false)
    private java.time.Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private java.time.Instant updatedAt;

    protected PayerTariff() {
    }

    public PayerTariff(String payerCode, String chargeItemCode, BigDecimal price) {
        this.id = new Key(payerCode, chargeItemCode);
        this.price = price;
    }

    public String getPayerCode() {
        return id.getPayerCode();
    }

    public String getChargeItemCode() {
        return id.getChargeItemCode();
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
