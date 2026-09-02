package com.hms.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The next invoice number, per series.
 *
 * <p>A series is a financial year — {@code INV-2026} — because that is how invoice numbering is
 * expected to work and audited: sequential within the year, restarting after it. The row exists so
 * that issuing a number is one statement rather than a read and a write; nothing reads this entity
 * except a diagnostic, and the repository's own comment explains why the issuing query is native
 * and carries no {@code @Modifying}.
 */
@Entity
@Table(name = "invoice_counters")
public class InvoiceCounter {

    @Id
    @Column(name = "series", nullable = false, length = 16)
    private String series;

    @Column(name = "next_number", nullable = false)
    private int nextNumber;

    protected InvoiceCounter() {
    }

    public String getSeries() {
        return series;
    }

    public int getNextNumber() {
        return nextNumber;
    }
}
