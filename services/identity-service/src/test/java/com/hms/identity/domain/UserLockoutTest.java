package com.hms.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The parts of {@link User} that are still plain in-memory state.
 *
 * <p>The failure counter and the lockout are NOT tested here, and that is the point: they are
 * applied by single SQL statements rather than by mutating this entity, so an entity-level test
 * would be blessing code the login path no longer runs. They are covered by
 * {@link com.hms.identity.service.LoginAttemptServiceTest}, against the real database, and by the
 * end-to-end lockout assertion in {@code AuthFlowIntegrationTest}.
 */
class UserLockoutTest {

    private User newUser() {
        return new User("tester", "tester@hms.local", "{argon2}hash", "Test User");
    }

    @Test
    void isNotLockedInitially() {
        assertThat(newUser().isLocked()).isFalse();
    }

    @Test
    void changingPasswordClearsTheForcedChangeFlag() {
        User user = newUser();
        user.setMustChangePassword(true);

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
