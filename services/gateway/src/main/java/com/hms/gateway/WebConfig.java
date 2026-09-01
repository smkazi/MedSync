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
     * Gives every request a correlation id before it is proxied, so a downstream service always
     * receives one rather than inventing its own per hop.
     */
    @Bean
    public WebFilter correlationIdFilter() {
        return (exchange, chain) -> {
            String existing = exchange.getRequest().getHeaders().getFirst(CORRELATION_HEADER);
            String correlationId = (existing == null || existing.isBlank())
                    ? java.util.UUID.randomUUID().toString()
                    : existing;
            exchange.getResponse().getHeaders().set(CORRELATION_HEADER, correlationId);
            return chain.filter(exchange.mutate()
                    .request(builder -> builder.header(CORRELATION_HEADER, correlationId))
                    .build());
        };
    }
}
