package com.hms.billing.domain;

import com.hms.billing.domain.BillingEnums.ChargeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A record that this charge has already been posted.
 *
 * <p><strong>The most important table in the module.</strong> Events get redelivered — that is a
 * property of every message bus worth using, not a fault — so a consumer that reads "report
 * released" twice must produce one charge. The primary key is the source, so the second attempt
 * collides with the first, and the collision is caught and treated as "already done" rather than as
 * an error. Without it a patient is billed twice for one consultation and finds out before the
 * hospital does.
 *
 * <p>No {@code BaseEntity}: the key is the natural one and there is nothing else to say about the
 * row beyond that it exists and when.
 */
@Entity
@Table(name = "posted_charges")
public class PostedCharge {

    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Enumerated(EnumType.STRING)
        @Column(name = "source_type", nullable = false, length = 32)
        private ChargeSource sourceType;

        @Column(name = "source_id", nullable = false)
        private UUID sourceId;

        @Column(name = "charge_item_code", nullable = false, length = 32)
        private String chargeItemCode;

        protected Key() {
        }

        public Key(ChargeSource sourceType, UUID sourceId, String chargeItemCode) {
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.chargeItemCode = chargeItemCode;
        }

        public ChargeSource getSourceType() {
            return sourceType;
        }

        public UUID getSourceId() {
            return sourceId;
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
            return sourceType == that.sourceType && Objects.equals(sourceId, that.sourceId)
                    && Objects.equals(chargeItemCode, that.chargeItemCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceType, sourceId, chargeItemCode);
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "invoice_line_id", nullable = false)
    private UUID invoiceLineId;

    @Column(name = "posted_at", nullable = false, insertable = false, updatable = false)
    private Instant postedAt;

    protected PostedCharge() {
    }

    public PostedCharge(ChargeSource sourceType, UUID sourceId, String chargeItemCode,
                        UUID invoiceLineId) {
        this.id = new Key(sourceType, sourceId, chargeItemCode);
        this.invoiceLineId = invoiceLineId;
    }

    public Key getId() {
        return id;
    }

    public UUID getInvoiceLineId() {
        return invoiceLineId;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
}
