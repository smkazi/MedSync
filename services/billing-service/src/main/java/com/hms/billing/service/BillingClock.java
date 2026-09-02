package com.hms.billing.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What "today" means here.
 *
 * <p>A bean rather than {@code LocalDate.now()} scattered through the module, because the answer is
 * a deployment's decision and not the JVM's. Money is dated in the hospital's own zone: an invoice
 * raised at eight in the evening in Kolkata belongs to that day, and a container running in UTC
 * would date it tomorrow — which was not a hypothetical. The day book was written against the
 * configured zone while invoices were dated in the JVM's, and a day's billing therefore read zero
 * while its payments read eight hundred.
 *
 * <p>Everything that decides a date goes through here: the invoice date, the tax rate in force, the
 * financial year an invoice number belongs to, and the day the day book totals.
 */
@Component
public class BillingClock {

    private final ZoneId zone;

    public BillingClock(@Value("${hms.billing.zone:Asia/Kolkata}") ZoneId zone) {
        this.zone = zone;
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    /** Midnight at the start of a day, as an instant — the boundary a cash-up runs between. */
    public Instant startOf(LocalDate day) {
        return day.atStartOfDay(zone).toInstant();
    }

    public ZoneId zone() {
        return zone;
    }
}
