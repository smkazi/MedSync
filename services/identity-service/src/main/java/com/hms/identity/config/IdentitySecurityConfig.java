package com.hms.identity.config;

import com.hms.common.security.JwtRolesConverter;
import com.hms.identity.service.KeyService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * identity-service is the token issuer, so it configures its own chain instead of the shared
 * resource-server one: login and JWKS must be reachable without a token, and it verifies its own
 * tokens against the in-memory JWKS rather than calling itself over HTTP.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class IdentitySecurityConfig {

    private final String issuer;
    private final String audience;

    public IdentitySecurityConfig(@Value("${hms.jwt.issuer:http://localhost:8081}") String issuer,
                                  @Value("${hms.jwt.audience:hms}") String audience) {
        this.issuer = issuer;
        this.audience = audience;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/auth/login", "/auth/refresh", "/auth/logout").permitAll()
                        .requestMatchers("/.well-known/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtRolesConverter())));
        return http.build();
    }

    /**
     * Verifies tokens against the live in-process JWKS, so a key rotation takes effect without a
     * restart and without a network round trip to itself.
     */
    @Bean
    public JwtDecoder jwtDecoder(KeyService keys) {
        JWKSource<SecurityContext> jwkSource = (selector, context) -> selector.select(keys.publicJwkSet());
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                audience,
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                Set.of("sub", "exp", "iat")));
        return new NimbusJwtDecoder(processor);
    }
}
