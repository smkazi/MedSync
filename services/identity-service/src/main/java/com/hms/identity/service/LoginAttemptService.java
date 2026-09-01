package com.hms.identity.service;

import com.hms.identity.domain.User;
import com.hms.identity.repo.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in bookkeeping: the failure counter, the lockout, and the success timestamp.
 *
 * <p>Two independent reasons this is not done by mutating the {@code User} entity in the calling
 * transaction.
 *
 * <p><b>Failures must survive the rollback.</b> A rejected login throws, which would roll back an
 * attempt counter written on the same transaction — the lockout threshold could never be reached
 * and brute-force protection would silently do nothing. {@code REQUIRES_NEW} is what makes the
 * lockout real.
 *
 * <p><b>Neither may contend on the version column.</b> {@code users} is an optimistically locked
 * entity, so two sign-ins for the same account at the same instant both read version N and both
 * try to write N+1: one wins, the other fails the commit and the caller sees a 500 on a request
 * that was handled correctly. Both methods therefore issue a single targeted statement that
 * leaves the version alone, and the failure counter increments in SQL so a burst of guesses is
 * counted exactly rather than collapsing into one.
 */
@Service
public class LoginAttemptService {

    private final UserRepository users;

    public LoginAttemptService(UserRepository users) {
        this.users = users;
    }

    /** Counts one failed attempt, locking the account if that crosses the threshold. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID userId) {
        users.recordFailedLogin(userId, User.MAX_FAILED_ATTEMPTS, Instant.now().plus(User.LOCK_DURATION));
    }

    /**
     * Stamps a successful sign-in and clears the failure state.
     *
     * <p>Runs in the caller's transaction: this path does not throw, so there is nothing to
     * protect it from, and keeping it inline means a rolled-back login leaves no trace of having
     * succeeded.
     *
     * @return the timestamp written, so the caller can report it without re-reading the row
     */
    @Transactional
    public Instant recordSuccess(UUID userId) {
        Instant at = Instant.now();
        users.markSuccessfulLogin(userId, at);
        return at;
    }
}
