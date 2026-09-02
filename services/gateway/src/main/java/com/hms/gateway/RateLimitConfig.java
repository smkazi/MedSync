package com.hms.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.WebFilter;

/**
 * Per-client rate limiting at the edge, with a much tighter bucket on the auth endpoints.
 *
 * <p>Two different problems, two different limits. The general bucket is there so one misbehaving
 * client cannot exhaust a connection pool for everybody - it is generous, because a clinician
 * loading a busy worklist legitimately makes a burst of requests. The auth bucket is there to make
 * online password guessing impractical, and it is strict, because nobody signs in forty times a
 * minute.
 *
 * <p>Account lockout in identity-service already stops guessing at <em>one</em> account. This stops
 * the other shape of the same attack: one password tried against a thousand usernames, where no
 * single account ever reaches its threshold. Neither control substitutes for the other.
 *
 * <h2>What this is not</h2>
 *
 * <p>The counters live in this JVM's heap. With one gateway that is exactly right - no Redis to
 * run, no network hop on the hot path, no shared state to get wrong. Behind two gateways each
 * instance enforces its own share, so the effective limit is the configured one multiplied by the
 * number of instances. That is a deliberate trade for the single-gateway deployment this ships
 * with, not an oversight, and it is the reason
 * {@code spring.cloud.gateway.filter.request-rate-limiter} with a Redis backend is the documented
 * upgrade path rather than something bolted on here. Set {@code hms.rate-limit.enabled=false} and
 * put the limit in front of the gateway if you have one there already.
 */
@Configuration
@ConditionalOnProperty(name = "hms.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    private final int generalPerMinute;
    private final int authPerMinute;

    private final int publicPerMinute;

    public RateLimitConfig(@Value("${hms.rate-limit.requests-per-minute:600}") int generalPerMinute,
                           @Value("${hms.rate-limit.auth-requests-per-minute:20}") int authPerMinute,
                           @Value("${hms.rate-limit.public-requests-per-minute:3000}") int publicPerMinute) {
        this.generalPerMinute = generalPerMinute;
        this.authPerMinute = authPerMinute;
        this.publicPerMinute = publicPerMinute;
    }

    /**
     * A fixed-window counter per key.
     *
     * <p>Fixed window rather than a sliding log or a leaky bucket: it is one long per client, it
     * cannot leak memory in a way that is itself a denial of service, and its known weakness -
     * twice the limit across a window boundary - does not matter for either job here. A password
     * guesser who gets 40 attempts in one straddled minute instead of 20 is still stopped; a client
     * that gets 1200 reads across a boundary is still not exhausting anything.
     */
    private static final class Window {
        private final AtomicLong count = new AtomicLong();
        private volatile Instant resetsAt;

        Window(Instant resetsAt) {
            this.resetsAt = resetsAt;
        }

        /** Increments and returns the count in the current window, rolling the window if due. */
        synchronized long increment(Instant now, Duration length) {
            if (!now.isBefore(resetsAt)) {
                count.set(0);
                resetsAt = now.plus(length);
            }
            return count.incrementAndGet();
        }

        synchronized Instant resetsAt() {
            return resetsAt;
        }
    }

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * Cap on distinct keys tracked at once. Without it the map is an unbounded allocation driven by
     * a header an attacker controls - a memory exhaustion bug inside the defence against
     * exhaustion. On overflow the map is cleared rather than evicted one by one: under the only
     * condition that reaches this size, the keys are spoofed and none of the counts is worth
     * keeping.
     */
    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final Map<String, Window> generalWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> authWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> publicWindows = new ConcurrentHashMap<>();

    /**
     * Ordered just after {@link SecurityHeadersConfig}, so a 429 goes out carrying the same
     * headers as any other response - a rejected request is still a response a browser parses.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public WebFilter rateLimitFilter() {
        log.info("Rate limiting: {}/min general, {}/min on /auth, {}/min on /public (per client, in-process)",
                generalPerMinute, authPerMinute, publicPerMinute);

        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getPath().value();

            // Health checks come from an orchestrator on a schedule; limiting them would take the
            // service out of rotation under exactly the load the limit exists to survive.
            if (path.startsWith("/actuator/health")) {
                return chain.filter(exchange);
            }

            // Three buckets, because they defend against three different things. The general one
            // stops a client exhausting the pool; the auth one makes password spraying
            // impractical; this one exists so a wall display can poll.
            //
            // The public bucket is the loosest of the three on purpose, and that is safe precisely
            // because of what is behind it: the corridor board returns a room code and some
            // numbers, so the worst a flood achieves is reading numbers faster. Counting it in the
            // general bucket would have meant one waiting-room screen refreshing every two seconds
            // spending its whole minute's allowance and then locking out every clinician sharing
            // that address behind a NAT.
            boolean isAuth = path.startsWith("/auth/");
            boolean isPublic = path.startsWith("/public/");
            Map<String, Window> windows = isAuth ? authWindows : isPublic ? publicWindows : generalWindows;
            int limit = isAuth ? authPerMinute : isPublic ? publicPerMinute : generalPerMinute;

            if (windows.size() > MAX_TRACKED_CLIENTS) {
                log.warn("Rate-limit table exceeded {} keys; clearing. Client keys are likely spoofed.",
                        MAX_TRACKED_CLIENTS);
                windows.clear();
            }

            Instant now = Instant.now();
            String key = clientKey(request);
            Window window = windows.computeIfAbsent(key, k -> new Window(now.plus(WINDOW)));
            long used = window.increment(now, WINDOW);

            long remaining = Math.max(0, limit - used);
            exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(limit));
            exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(remaining));

            if (used > limit) {
                long retryAfter = Math.max(1, Duration.between(now, window.resetsAt()).toSeconds());
                // Logged at the key, never the credential: a rate-limit log line that carried the
                // attempted username would put a list of guessed usernames in the log file.
                log.warn("Rate limit exceeded on {} for client {} ({} in the last minute, limit {})",
                        isAuth ? "/auth" : isPublic ? "/public" : "the API", key, used, limit);
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(retryAfter));
                return exchange.getResponse().setComplete();
            }

            return chain.filter(exchange);
        };
    }

    /**
     * Who to count against.
     *
     * <p>The remote address, and only the remote address. X-Forwarded-For is deliberately NOT
     * trusted here: it is a request header, so a client that can set it can mint a fresh identity
     * for every request and walk straight through the limit. If this sits behind a proxy that sets
     * it honestly, configure Spring's {@code ForwardedHeaderTransformer} (server.forward-headers-
     * strategy) so the framework rewrites the remote address before this filter sees it - then this
     * method is reading a value the proxy vouched for rather than one the client asserted.
     */
    private static String clientKey(ServerHttpRequest request) {
        var remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? "unknown"
                : remote.getAddress().getHostAddress();
    }
}
