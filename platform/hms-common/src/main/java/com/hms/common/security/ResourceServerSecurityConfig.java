package com.hms.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless resource-server security shared by every business service: validate the bearer
 * token against identity-service's JWKS, expose health and docs, deny everything else.
 *
 * <p>identity-service is the token <em>issuer</em>, not a resource server, so it opts out with
 * {@code hms.security.mode=issuer} and supplies its own chain.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnProperty(name = "hms.security.mode", havingValue = "resource-server", matchIfMissing = true)
public class ResourceServerSecurityConfig {

    /** Paths that must stay reachable without a token (health checks, OpenAPI, device ingest hooks). */
    @Value("${hms.security.public-paths:}")
    private String[] publicPaths = new String[0];

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    for (String path : publicPaths) {
                        if (path != null && !path.isBlank()) {
                            auth.requestMatchers(path.trim()).permitAll();
                        }
                    }
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtRolesConverter())));
        return http.build();
    }
}
