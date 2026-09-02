package com.hms.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a day is.
 *
 * <p>Small, and it earns its place: the day book totals payments between two instants, and getting
 * that boundary wrong moves an evening's takings into the next day without anything failing. The
 * bug that prompted this was the other half of the same mistake — invoices dated in the JVM's zone
 * and counted in the configured one, so a day's billing read zero while its collections read eight
 * hundred.
 */
class BillingClockTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    @Test
    @DisplayName("a day starts at local midnight, which is not midnight UTC")
    void aDayStartsAtLocalMidnight() {
        BillingClock clock = new BillingClock(KOLKATA);

        // India is UTC+05:30 all year, so local midnight on the 14th is 18:30 UTC on the 13th.
        assertThat(clock.startOf(LocalDate.of(2026, 4, 14)))
                .isEqualTo(Instant.parse("2026-04-13T18:30:00Z"));
    }

    @Test
    @DisplayName("consecutive days abut exactly, so a payment lands in one day and not two")
    void daysAbut() {
        BillingClock clock = new BillingClock(KOLKATA);
        LocalDate day = LocalDate.of(2026, 4, 14);

        Instant endOfDay = clock.startOf(day.plusDays(1));
        assertThat(endOfDay).isEqualTo(clock.startOf(day).plusSeconds(24 * 60 * 60));
        // The window is half-open: a payment taken at exactly this instant belongs to the 15th.
        assertThat(endOfDay).isEqualTo(Instant.parse("2026-04-14T18:30:00Z"));
    }

    @Test
    @DisplayName("the zone decides the date, not the machine")
    void theZoneDecidesTheDate() {
        // 20:00 in Kolkata on the 14th is 14:30 UTC the same day; at 19:00 UTC it is already the
        // 15th in Kolkata while the machine still says the 14th.
        assertThat(LocalDate.ofInstant(Instant.parse("2026-04-14T19:00:00Z"), KOLKATA))
                .isEqualTo(LocalDate.of(2026, 4, 15));
        assertThat(LocalDate.ofInstant(Instant.parse("2026-04-14T19:00:00Z"), ZoneId.of("UTC")))
                .isEqualTo(LocalDate.of(2026, 4, 14));

        assertThat(new BillingClock(KOLKATA).zone()).isEqualTo(KOLKATA);
    }
}
