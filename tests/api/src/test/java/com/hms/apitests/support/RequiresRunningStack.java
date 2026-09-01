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
    }
}
