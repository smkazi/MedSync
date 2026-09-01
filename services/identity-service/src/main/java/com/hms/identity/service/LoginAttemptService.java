package com.hms.identity.service;

import com.hms.identity.repo.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records failed sign-in attempts in their own transaction.
 *
 * <p>A rejected login throws, which would roll back the attempt counter written on the same
 * transaction — meaning the lockout threshold could never be reached and brute-force protection
 * would silently do nothing. Committing separately is what makes the lockout real.
 */
@Service
public class LoginAttemptService {

    private final UserRepository users;

    public LoginAttemptService(UserRepository users) {
        this.users = users;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID userId) {
        users.findById(userId).ifPresent(user -> {
            user.recordFailedLogin();
            users.save(user);
        });
    }
}
