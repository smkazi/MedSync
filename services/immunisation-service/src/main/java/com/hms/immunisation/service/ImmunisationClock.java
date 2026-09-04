package com.hms.immunisation.service;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * "Today", for a register.
 *
 * <p>The {@code BillingClock} shape, and here for the same reason: everything that decides a date
 * goes through one place, so a deployment cannot end up with two answers to what day it is. The
 * zone defaults from {@code HMS_ZONE} like the day book, the audit report and the disclosure
 * register — this service joins that chain from its first commit rather than adding a second
 * exception to it.
 *
 * <p><strong>Note how little reads this.</strong> Only two questions in the whole module need a
 * clock: whether a dose is overdue, and which period a measure covers. The schedule arithmetic
 * itself does not — "28 days after dose 1" is a difference between two dates and has no zone in it,
 * which is why {@code ImmunisationScheduleCalculator} takes an {@code asAt} date as a parameter and
 * never reads one. A calculator that read the clock could not be tested against a chart and could
 * not answer "what was due on the first of the month", which is exactly what a measure asks.
 */
@Component
public class ImmunisationClock {

    private final ZoneId zone;

    public ImmunisationClock(@Value("${hms.immunisation.zone:Asia/Kolkata}") ZoneId zone) {
        this.zone = zone;
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    public ZoneId zone() {
        return zone;
    }
}
