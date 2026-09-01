package com.hms.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtRolesConverterTest {

    private final JwtRolesConverter converter = new JwtRolesConverter();

    private Jwt jwtWithClaims(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("11111111-2222-3333-4444-555555555555")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600));
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    void mapsRolesToPrefixedAuthorities() {
        var authentication = converter.convert(jwtWithClaims(Map.of("roles", List.of("ADMIN", "DOCTOR"))));

        assertThat(authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_DOCTOR");
    }

    @Test
    void doesNotDoublePrefixRolesThatAlreadyCarryIt() {
        var authentication = converter.convert(jwtWithClaims(Map.of("roles", List.of("ROLE_NURSE"))));

        assertThat(authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .containsExactly("ROLE_NURSE");
    }

    @Test
    void tokenWithoutRolesClaimGetsNoAuthorities() {
        var authentication = converter.convert(jwtWithClaims(Map.of("preferred_username", "nobody")));

        assertThat(authentication.getAuthorities()).isEmpty();
    }

    @Test
    void blankRolesAreIgnored() {
        var authentication = converter.convert(jwtWithClaims(Map.of("roles", List.of("ADMIN", "  ", ""))));

        assertThat(authentication.getAuthorities()).hasSize(1);
    }

    @Test
    void principalNameIsTheSubject() {
        var authentication = converter.convert(jwtWithClaims(Map.of("roles", List.of("ADMIN"))));

        assertThat(authentication.getName()).isEqualTo("11111111-2222-3333-4444-555555555555");
    }
}
