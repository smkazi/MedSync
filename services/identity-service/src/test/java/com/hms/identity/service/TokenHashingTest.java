package com.hms.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenHashingTest {

    @Test
    void hashIsStableHexOfFixedLength() {
        String hash = TokenService.sha256Hex("a-refresh-token");
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(TokenService.sha256Hex("a-refresh-token")).isEqualTo(hash);
    }

    @Test
    void differentTokensHashDifferently() {
        assertThat(TokenService.sha256Hex("token-a")).isNotEqualTo(TokenService.sha256Hex("token-b"));
    }

    @Test
    void hashDoesNotContainThePlaintext() {
        String secret = "super-secret-refresh-token";
        assertThat(TokenService.sha256Hex(secret)).doesNotContain(secret);
    }
}
