package com.hms.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.WebFilter;

/**
 * TLS behaviour at the edge.
 *
 * <p>Two things only matter once the gateway is actually serving HTTPS, so both are conditional on
 * the TLS profile being active: a plain-HTTP request is redirected rather than served, and every
 * response carries HSTS so a browser will not try plain HTTP again.
 */
@Configuration
@ConditionalOnProperty(name = "server.ssl.enabled", havingValue = "true")
public class TlsRedirectConfig {

    /** One year, including subdomains — the value a browser preload list expects. */
    private static final String HSTS = "max-age=31536000; includeSubDomains";

    @Bean
    public WebFilter httpsEnforcementFilter() {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String forwardedProto = request.getHeaders().getFirst("X-Forwarded-Proto");
            boolean secure = "https".equalsIgnoreCase(forwardedProto)
                    || (request.getURI().getScheme() != null
                        && request.getURI().getScheme().equalsIgnoreCase("https"));

            if (!secure) {
                // Redirect rather than serve: answering over plain HTTP would leak the bearer
                // token in the request that follows.
                String target = "https://" + request.getURI().getHost()
                        + request.getURI().getRawPath()
                        + (request.getURI().getRawQuery() == null ? ""
                           : "?" + request.getURI().getRawQuery());
                exchange.getResponse().setStatusCode(HttpStatus.PERMANENT_REDIRECT);
                exchange.getResponse().getHeaders().set("Location", target);
                return exchange.getResponse().setComplete();
            }

            exchange.getResponse().getHeaders().set("Strict-Transport-Security", HSTS);
            return chain.filter(exchange);
        };
    }
}
