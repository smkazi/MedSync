package com.hms.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.identity.domain.User;
import com.hms.identity.repo.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Sign-in bookkeeping, against the real database, because that is where it now lives.
 *
 * <p>These tests exist because a load profile found a defect no unit test could have: two
 * simultaneous sign-ins for one account both mutated the optimistically locked {@code users} row,
 * so one of them failed its commit and returned 500 on a login that was entirely valid. The
 * counter had a quieter version of the same fault - two parallel guesses both read the same count
 * and both wrote count+1, so a burst could be counted once, which is precisely the burst a
 * lockout threshold is for.
 *
 * <p>Both are now single statements. The tests below run them concurrently, which is the only way
 * either bug is visible.
 */
@SpringBootTest
@ActiveProfiles("test")
class LoginAttemptServiceTest {

    @Autowired
    private LoginAttemptService loginAttempts;

    @Autowired
    private UserRepository users;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    /** A throwaway account, so a test that locks one out cannot leak that into another test. */
    private User disposableUser() {
        String username = "attempt-" + UUID.randomUUID().toString().substring(0, 8);
        return users.save(new User(username, username + "@hms.local",
                passwordEncoder.encode("Irrelevant!2026"), "Attempt Tester"));
    }

    /** Runs {@code task} on {@code n} threads at once and returns once all of them are done. */
    private <T> List<T> inParallel(int n, Callable<T> task) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> futures = pool.invokeAll(java.util.Collections.nCopies(n, task));
            List<T> results = new java.util.ArrayList<>(n);
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    @Test
    @DisplayName("a failure counts exactly once, and locks the account on the threshold")
    void countsFailuresAndLocks() {
        User user = disposableUser();

        for (int attempt = 1; attempt < User.MAX_FAILED_ATTEMPTS; attempt++) {
            loginAttempts.recordFailure(user.getId());
            User reloaded = users.findById(user.getId()).orElseThrow();
            assertThat(reloaded.isLocked())
                    .as("still unlocked after %d failure(s)", attempt)
                    .isFalse();
            assertThat(reloaded.getFailedLoginAttempts()).isEqualTo(attempt);
        }

        loginAttempts.recordFailure(user.getId());

        User locked = users.findById(user.getId()).orElseThrow();
        assertThat(locked.isLocked()).isTrue();
        assertThat(locked.getLockedUntil()).isAfter(Instant.now());
        // The counter resets when the lock goes on; the lock, not the count, is what keeps the
        // account out from here.
        assertThat(locked.getFailedLoginAttempts()).isZero();
    }

    @Test
    @DisplayName("concurrent failures are all counted - none is lost to a read-modify-write race")
    void concurrentFailuresAreNotLost() throws Exception {
        User user = disposableUser();
        int burst = User.MAX_FAILED_ATTEMPTS - 1;

        inParallel(burst, () -> {
            loginAttempts.recordFailure(user.getId());
            return null;
        });

        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getFailedLoginAttempts())
                .as("every one of %d simultaneous failures must be counted", burst)
                .isEqualTo(burst);
        assertThat(reloaded.isLocked()).isFalse();
    }

    @Test
    @DisplayName("a burst past the threshold locks the account rather than failing a write")
    void concurrentFailuresPastTheThresholdLock() throws Exception {
        User user = disposableUser();

        inParallel(User.MAX_FAILED_ATTEMPTS + 3, () -> {
            loginAttempts.recordFailure(user.getId());
            return null;
        });

        assertThat(users.findById(user.getId()).orElseThrow().isLocked()).isTrue();
    }

    @Test
    @DisplayName("simultaneous successful sign-ins all succeed - no optimistic-lock collision")
    void concurrentSuccessesDoNotCollide() throws Exception {
        User user = disposableUser();
        loginAttempts.recordFailure(user.getId());

        // Before the fix this threw ObjectOptimisticLockingFailureException on all but one thread,
        // which the web layer turned into a 500.
        List<Instant> stamps = inParallel(8, () -> loginAttempts.recordSuccess(user.getId()));

        assertThat(stamps).hasSize(8).doesNotContainNull();
        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getLastLoginAt()).isNotNull();
        assertThat(reloaded.getFailedLoginAttempts()).isZero();
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("a success clears an existing lock")
    void successClearsTheLock() {
        User user = disposableUser();
        for (int i = 0; i < User.MAX_FAILED_ATTEMPTS; i++) {
            loginAttempts.recordFailure(user.getId());
        }
        assertThat(users.findById(user.getId()).orElseThrow().isLocked()).isTrue();

        loginAttempts.recordSuccess(user.getId());

        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isLocked()).isFalse();
        assertThat(reloaded.getLockedUntil()).isNull();
    }
}
