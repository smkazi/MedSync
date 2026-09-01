package com.hms.gateway;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.WebFilter;

/**
 * Browser-facing concerns that belong at the edge: CORS for the Next.js origin and a correlation
 * id stamped on every inbound request so one user action can be traced across all services.
 */
@Configuration
public class WebConfig {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final List<String> allowedOrigins;

    public WebConfig(@Value("${hms.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", CORRELATION_HEADER));
        config.setExposedHeaders(List.of(CORRELATION_HEADER));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * What an acceptable inbound correlation id looks like. The same rule as
     * {@code CorrelationIdFilter} in hms-common, applied here because the gateway is the first
     * thing to touch the header and the only place a client's value gets in.
     *
     * <p>A caller-supplied id is echoed to the client and written to the logs of every service the
     * request reaches, so a CR or LF in it is response splitting on one side and forged audit lines
     * on the other. A malformed id is replaced rather than stripped: there is no legitimate trace
     * to preserve in a value that could not have come from a real client.
     */
    private static final java.util.regex.Pattern SAFE_CORRELATION_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /**
     * Gives every request a correlation id before it is proxied, so a downstream service always
     * receives one rather than inventing its own per hop.
     */
    @Bean
    public WebFilter correlationIdFilter() {
        return (exchange, chain) -> {
            String inbound = exchange.getRequest().getHeaders().getFirst(CORRELATION_HEADER);
            String correlationId = (inbound == null || inbound.isBlank()
                    || !SAFE_CORRELATION_ID.matcher(inbound).matches())
                    ? java.util.UUID.randomUUID().toString()
                    : inbound;
            exchange.getResponse().getHeaders().set(CORRELATION_HEADER, correlationId);
            return chain.filter(exchange.mutate()
                    .request(builder -> builder.header(CORRELATION_HEADER, correlationId))
                    .build());
        };
    }
}
