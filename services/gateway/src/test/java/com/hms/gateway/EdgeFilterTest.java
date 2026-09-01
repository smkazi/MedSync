package com.hms.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The edge behaviours the gateway is solely responsible for: rate limiting, security headers, and
 * refusing to echo a correlation id it was not willing to vouch for.
 *
 * <p>Runs against a real port with no services behind it. That is the point - every assertion here
 * is about what the gateway does before it proxies anything, so an unroutable path returning 404 or
 * 503 is fine as long as the header or the limit behaved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EdgeFilterTest {

    @LocalServerPort
    private int port;

    private WebTestClient client;

    /** Built lazily: @LocalServerPort is not populated until after construction. */
    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer()
                    .baseUrl("http://localhost:" + port)
                    .build();
        }
        return client;
    }

    @Test
    @DisplayName("every response carries the security headers, including one the gateway rejects")
    void securityHeadersOnEveryResponse() {
        client().get().uri("/no-such-route")
                .exchange()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader().valueEquals("Cross-Origin-Opener-Policy", "same-origin")
                .expectHeader().exists("Content-Security-Policy")
                .expectHeader().exists("Permissions-Policy");
    }

    @Test
    @DisplayName("the runtime and its version are not advertised")
    void noServerBanner() {
        client().get().uri("/no-such-route")
                .exchange()
                .expectHeader().doesNotExist("Server")
                .expectHeader().doesNotExist("X-Powered-By");
    }

    @Test
    @DisplayName("a well-formed correlation id is honoured so a trace spans services")
    void correlationIdIsEchoed() {
        client().get().uri("/no-such-route")
                .header("X-Correlation-Id", "trace-abc123.def:456")
                .exchange()
                .expectHeader().valueEquals("X-Correlation-Id", "trace-abc123.def:456");
    }

    /**
     * A correlation id outside the allowlist is replaced rather than echoed.
     *
     * <p>Not tested with a literal CRLF, and that is worth explaining: Reactor Netty refuses to
     * <em>send</em> such a header, so the request never leaves the client and the test would
     * assert nothing about the server. The transport already blocks the response-splitting case
     * from both ends. What the allowlist adds is everything else that is legal on the wire and
     * still unwelcome in a log line or a header value - the payloads below - plus a guarantee that
     * holds if the id is ever written somewhere the transport is not policing.
     */
    @ParameterizedTest(name = "a correlation id of {0} is replaced")
    @ValueSource(strings = {
            "abc; X-Injected: yes",
            "abc def",
            "<script>alert(1)</script>",
            "\"quoted\"",
            "id,with,commas",
            "id\twith\ttabs",
    })
    void malformedCorrelationIdIsReplaced(String malformed) {
        String echoed = client().get().uri("/no-such-route")
                .header("X-Correlation-Id", malformed)
                .exchange()
                .expectHeader().doesNotExist("X-Injected")
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst("X-Correlation-Id");

        assertThat(echoed)
                .isNotNull()
                .as("the malformed value must not survive into the response header")
                .isNotEqualTo(malformed)
                .matches("[A-Za-z0-9._:-]{1,64}");
    }

    @Test
    @DisplayName("an over-long correlation id is replaced")
    void oversizedCorrelationIdIsReplaced() {
        String echoed = client().get().uri("/no-such-route")
                .header("X-Correlation-Id", "a".repeat(500))
                .exchange()
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst("X-Correlation-Id");

        assertThat(echoed).isNotNull().hasSizeLessThanOrEqualTo(64);
    }

    /**
     * The auth bucket runs out and says so properly.
     *
     * <p>Written as "a 429 arrives within limit+1 attempts" rather than "attempt 4 is the first
     * 429", because every method in this class shares one application context and therefore one
     * set of counters. An absolute count would pass or fail on test order - and a test that
     * depends on JUnit's method ordering is a test that will fail on someone else's machine.
     */
    @Test
    @DisplayName("the auth bucket returns 429 with Retry-After once its limit is spent")
    void authRateLimitIsEnforced() {
        // application-test.yml sets the auth bucket to 3/min, so this is quick.
        int limit = 3;
        int firstRejection = 0;
        for (int attempt = 1; attempt <= limit + 1 && firstRejection == 0; attempt++) {
            int status = client().post().uri("/auth/login")
                    .exchange()
                    .returnResult(String.class)
                    .getStatus().value();
            if (status == 429) {
                firstRejection = attempt;
            }
        }

        assertThat(firstRejection)
                .as("the auth bucket holds %d per minute, so a 429 must arrive by attempt %d",
                        limit, limit + 1)
                .isBetween(1, limit + 1);

        // Every subsequent attempt in the window is refused, and the refusal is well-formed.
        client().post().uri("/auth/login")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                // A 429 is a response a browser parses like any other, so it must be as hardened
                // as a 200.
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
    }

    @Test
    @DisplayName("the general bucket is separate from the auth bucket")
    void generalTrafficIsNotBlockedByTheAuthBucket() {
        // Spend the auth bucket several times over.
        for (int i = 0; i < 10; i++) {
            client().post().uri("/auth/login").exchange();
        }
        // A read must still get through. One shared counter would have taken the entire API down
        // with the login endpoint - which is what a password sprayer would want.
        client().get().uri("/patients")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
    }

    @Test
    @DisplayName("health checks are never rate limited")
    void healthChecksBypassTheLimit() {
        // An orchestrator polls this on a schedule. Limiting it would pull the service out of
        // rotation under exactly the load the limit exists to survive.
        for (int i = 0; i < 30; i++) {
            client().get().uri("/actuator/health")
                    .exchange()
                    .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
        }
    }
}
