package com.hms.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserLockoutTest {

    private User newUser() {
        return new User("tester", "tester@hms.local", "{argon2}hash", "Test User");
    }

    @Test
    void isNotLockedInitially() {
        assertThat(newUser().isLocked()).isFalse();
    }

    @Test
    void locksOnlyOnceTheThresholdIsReached() {
        User user = newUser();
        for (int attempt = 1; attempt < User.MAX_FAILED_ATTEMPTS; attempt++) {
            user.recordFailedLogin();
            assertThat(user.isLocked())
                    .as("still unlocked after %d failure(s)", attempt)
                    .isFalse();
        }
        user.recordFailedLogin();
        assertThat(user.isLocked()).isTrue();
        assertThat(user.getLockedUntil()).isNotNull();
    }

    @Test
    void successfulLoginClearsFailuresAndLock() {
        User user = newUser();
        for (int i = 0; i < User.MAX_FAILED_ATTEMPTS; i++) {
            user.recordFailedLogin();
        }
        assertThat(user.isLocked()).isTrue();

        user.recordSuccessfulLogin();

        assertThat(user.isLocked()).isFalse();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLastLoginAt()).isNotNull();
    }

    @Test
    void changingPasswordClearsLockAndForcedChangeFlag() {
        User user = newUser();
        user.setMustChangePassword(true);
        user.recordFailedLogin();

        user.changePassword("{argon2}newhash");

        assertThat(user.getPasswordHash()).isEqualTo("{argon2}newhash");
        assertThat(user.isMustChangePassword()).isFalse();
        assertThat(user.isLocked()).isFalse();
    }

    @Test
    void roleCodesReflectAssignedRoles() {
        User user = newUser();
        user.replaceRoles(new java.util.LinkedHashSet<>(java.util.List.of(
                new Role("DOCTOR", "Physician"), new Role("ADMIN", "Administrator"))));
        assertThat(user.roleCodes()).containsExactly("DOCTOR", "ADMIN");
    }
}
