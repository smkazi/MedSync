package com.hms.gateway;

import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Security headers on every response the gateway produces or proxies.
 *
 * <p>The Next.js app sets its own headers for the pages it renders, but that only covers traffic
 * the browser gets from the web tier. Anything a browser fetches straight from the gateway - the
 * API, a JSON error, an actuator probe - was arriving bare. The authorization abuse suite in
 * tests/api caught it: {@code X-Content-Type-Options} was simply absent.
 *
 * <p>Set here rather than in each service, because the gateway is the only thing every browser
 * response passes through, and a header applied in five places is a header that will be missing
 * from the sixth.
 *
 * <p>Headers are set with {@code setIfAbsent} semantics: a downstream service that has a reason to
 * choose its own value keeps it. HSTS is not here - it belongs with the TLS listener and lives in
 * {@link TlsRedirectConfig}, so it is never advertised over plain HTTP.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersConfig implements WebFilter {

    /**
     * A JSON API's CSP has one job: make certain that nothing a browser is tricked into loading
     * from this origin can execute. Everything is denied, which is correct here precisely because
     * the gateway serves no HTML and no scripts of its own. The app's real, nonce-based policy is
     * in web/src/middleware.ts, where there is actually a document to protect.
     */
    private static final String API_CSP =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private static final Map<String, String> HEADERS = Map.of(
            // Stop a browser from second-guessing Content-Type. A JSON response sniffed as HTML is
            // how stored text becomes stored XSS.
            "X-Content-Type-Options", "nosniff",
            // No framing at all: nothing here is meant to be embedded.
            "X-Frame-Options", "DENY",
            // A referrer would carry patient identifiers in the path to whatever the user clicks.
            "Referrer-Policy", "no-referrer",
            // Nothing served from this origin has any business asking for hardware.
            "Permissions-Policy",
            "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), "
                    + "microphone=(), payment=(), usb=()",
            "Cross-Origin-Opener-Policy", "same-origin",
            "Cross-Origin-Resource-Policy", "same-site",
            "Content-Security-Policy", API_CSP);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // beforeCommit rather than up front: a proxied response's headers are populated when the
        // downstream reply arrives, so setting ours earlier would let the downstream overwrite
        // them, and setting them later than commit is not possible at all.
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            HEADERS.forEach((name, value) -> {
                if (!headers.containsHeader(name)) {
                    headers.set(name, value);
                }
            });
            // Never advertise the runtime or its version: it hands an attacker the exact CVE list
            // to work through.
            headers.remove(HttpHeaders.SERVER);
            headers.remove("X-Powered-By");
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
