package com.hms.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private RefreshToken token(Instant expiresAt) {
        return new RefreshToken(UUID.randomUUID(), "hash", UUID.randomUUID(), expiresAt, "junit");
    }

    @Test
    void freshTokenIsUsable() {
        assertThat(token(Instant.now().plus(1, ChronoUnit.DAYS)).isUsable()).isTrue();
    }

    @Test
    void expiredTokenIsNotUsable() {
        RefreshToken expired = token(Instant.now().minus(1, ChronoUnit.MINUTES));
        assertThat(expired.isExpired()).isTrue();
        assertThat(expired.isUsable()).isFalse();
    }

    @Test
    void revokedTokenIsNotUsableAndKeepsFirstReason() {
        RefreshToken subject = token(Instant.now().plus(1, ChronoUnit.DAYS));
        subject.revoke("rotated");
        subject.revoke("reuse-detected");

        assertThat(subject.isRevoked()).isTrue();
        assertThat(subject.isUsable()).isFalse();
        assertThat(subject.getRevokedReason())
                .as("the original revocation reason must survive a later revoke call")
                .isEqualTo("rotated");
    }
}
