package com.hms.apitests.support;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

/**
 * Shared precondition for every suite here.
 *
 * <p>A stack that is not running should fail the suite immediately with a sentence that says so,
 * not with forty connection-refused stack traces. It fails rather than skips deliberately:
 * {@code make test-api} is meant to run these, and a suite that quietly reports "0 tests, all
 * green" when nothing was tested is worse than no suite.
 */
@Tag("api")
public abstract class RequiresRunningStack {

    @BeforeAll
    static void stackMustBeUp() {
        if (!Api.reachable()) {
            throw new IllegalStateException(
                    "No stack answering at " + Api.BASE_URL + "/actuator/health. Start it first "
                            + "(make up, or make dev), or point the suite elsewhere with "
                            + "-Dhms.api.base-url=...");
        }
        // The abuse-case suites deliberately spend the lockout threshold and hammer search
        // endpoints, so they can trip the gateway's auth bucket (20/min by default) and then fail
        // on 429s that look like broken assertions. Say so up front rather than leaving someone to
        // work it out from a dozen unrelated failures.
        if (Api.rateLimited()) {
            throw new IllegalStateException(
                    "The gateway is rate-limiting this suite (429 on /auth). These tests make more "
                            + "sign-in attempts per minute than a human would, on purpose. Start "
                            + "the stack with HMS_RATE_LIMIT_AUTH_RPM raised (500 is plenty) or "
                            + "HMS_RATE_LIMIT_ENABLED=false. The limiter itself is tested by "
                            + "EdgeFilterTest in the gateway module.");
        }
    }
}
