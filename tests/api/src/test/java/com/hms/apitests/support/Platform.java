package com.hms.apitests.support;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The zone the platform under test keeps its days in, and the dates that follow from it.
 *
 * <p>This exists because of a defect that could only fail for five and a half hours out of every
 * twenty-four. Every service on this platform decides what "today" means from {@code HMS_ZONE},
 * which defaults to {@code Asia/Kolkata}; this suite was computing its dates from the JVM's own
 * zone, which on a CI runner is UTC. Between 18:30 and midnight UTC those are different days — so a
 * diagnosis recorded "now" landed on tomorrow's service date, a report asked for "today" did not
 * contain it, a child born {@code now - 300 days} was 301 days old, and an appointment checked in
 * today got a token on tomorrow's board. Seven tests, all correct about the platform's behaviour and
 * all wrong about which day it was.
 *
 * <p>{@code QueueJourneyIT} even said so out loud — "the service date every booking in this class
 * lands on, in the clinic zone (UTC)" — and that comment was true when it was written. S13b moved
 * scheduling onto the shared {@code HMS_ZONE} chain and changed the default from UTC to
 * Asia/Kolkata, and its own commit message noted that no test broke: true, because the unit tests
 * pin {@code hms.scheduling.zone: UTC} in {@code application-test.yml}. This suite does not — it
 * runs against a live stack on whatever the deployment is configured with, which is exactly the
 * point of it.
 *
 * <p>So there is one place to ask, and it reads the same variable the services read. A test that
 * needs to know what day the platform thinks it is must not answer that question for itself.
 */
public final class Platform {

    /**
     * Kept identical to the services' own fallback. If that default ever changes, this constant is
     * wrong and the suite will say so within a day rather than silently drift, because the failures
     * it produces are the ones documented above.
     */
    private static final String DEFAULT_ZONE = "Asia/Kolkata";

    private Platform() {
    }

    /**
     * The clinic's zone: {@code HMS_ZONE} from the environment, or the platform's own default.
     *
     * <p>Read on every call rather than cached in a static, so a suite run with the variable
     * changed between classes cannot be answered from a value captured before the change.
     */
    public static ZoneId zone() {
        String configured = System.getenv("HMS_ZONE");
        if (configured == null || configured.isBlank()) {
            configured = DEFAULT_ZONE;
        }
        return ZoneId.of(configured);
    }

    /** What day it is where the platform is, which is the only "today" a service will agree with. */
    public static LocalDate today() {
        return LocalDate.now(zone());
    }
}
