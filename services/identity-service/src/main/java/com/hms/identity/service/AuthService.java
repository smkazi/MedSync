package com.hms.identity.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.NotFoundException;
import com.hms.identity.domain.RefreshToken;
import com.hms.identity.domain.User;
import com.hms.identity.repo.UserRepository;
import com.hms.identity.web.dto.AuthDtos;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The login, refresh and logout flows.
 *
 * <p>Failure responses are deliberately uniform: an unknown username and a wrong password both
 * raise {@link BadCredentialsException} so the endpoint cannot be used to enumerate accounts.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final LoginAttemptService loginAttempts;
    private final AuditService audit;

    /**
     * A real hash of a throwaway password, verified when the username is unknown so that a
     * non-existent account costs the same time as a wrong password.
     */
    private final String timingDecoyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens,
                       LoginAttemptService loginAttempts, AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.loginAttempts = loginAttempts;
        this.audit = audit;
        this.timingDecoyHash = passwordEncoder.encode("timing-decoy-not-a-real-password");
    }

    @Transactional
    public AuthDtos.TokenResponse login(String username, String rawPassword, String userAgent) {
        User user = users.findByUsernameIgnoreCase(username).orElse(null);
        if (user == null) {
            // Spend comparable time on an unknown user so response timing does not leak existence.
            passwordEncoder.matches(rawPassword, timingDecoyHash);
            audit.record("LOGIN_FAILED", "User", username, "unknown username");
            throw new BadCredentialsException("Invalid username or password");
        }
        if (user.isLocked()) {
            audit.record("LOGIN_BLOCKED", "User", user.getId(), "account locked until " + user.getLockedUntil());
            throw new LockedException("Account is temporarily locked after repeated failed logins");
        }
        if (!user.isActive()) {
            audit.record("LOGIN_BLOCKED", "User", user.getId(), "account disabled");
            throw new DisabledException("Account is disabled");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            // Committed separately: this method throws, and a rolled-back counter would mean
            // the lockout threshold is never reached.
            loginAttempts.recordFailure(user.getId());
            audit.record("LOGIN_FAILED", "User", user.getId(), "bad password");
            throw new BadCredentialsException("Invalid username or password");
        }

        // Stamped by a targeted statement rather than by mutating the entity: users is
        // optimistically locked, and two simultaneous sign-ins for one account would otherwise
        // collide on the version column and fail a login that was perfectly valid.
        Instant loggedInAt = loginAttempts.recordSuccess(user.getId());
        TokenService.TokenPair pair = tokens.issueFor(user, userAgent);
        audit.record("LOGIN_SUCCEEDED", "User", user.getId(), "roles " + user.roleCodes());
        log.info("User {} logged in", user.getUsername());
        return response(pair, user, loggedInAt);
    }

    @Transactional
    public AuthDtos.TokenResponse refresh(String rawRefreshToken, String userAgent) {
        RefreshToken consumed = tokens.consumeForRotation(rawRefreshToken);
        User user = users.findById(consumed.getUserId())
                .orElseThrow(() -> new NotFoundException("User for this refresh token no longer exists"));
        if (!user.isActive()) {
            tokens.revokeFamily(consumed.getFamilyId(), "user-disabled");
            throw new DisabledException("Account is disabled");
        }
        TokenService.TokenPair pair = tokens.issueFor(user, consumed.getFamilyId(), userAgent);
        audit.record("TOKEN_REFRESHED", "User", user.getId(), "family " + consumed.getFamilyId());
        return response(pair, user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        tokens.find(rawRefreshToken).ifPresent(token ->
                audit.record("LOGOUT", "User", token.getUserId(), "refresh token revoked"));
        tokens.revoke(rawRefreshToken);
    }

    @Transactional
    public void changeOwnPassword(UUID userId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElseThrow(() -> NotFoundException.of("User", userId));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BadRequestException("New password must differ from the current password");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        // Changing a password invalidates every existing session for that user.
        int revoked = tokens.revokeAllForUser(userId, "password-changed");
        audit.record("PASSWORD_CHANGED", "User", userId, revoked + " session(s) revoked");
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserResponse currentUser(UUID userId) {
        return UserMapper.toResponse(users.findById(userId)
                .orElseThrow(() -> NotFoundException.of("User", userId)));
    }

    private AuthDtos.TokenResponse response(TokenService.TokenPair pair, User user) {
        return response(pair, user, user.getLastLoginAt());
    }

    /**
     * @param loggedInAt the sign-in timestamp to report. Passed in on the login path because the
     *                   timestamp is written by a bulk update, which by design does not refresh
     *                   the managed entity - reading it back off {@code user} would report the
     *                   previous sign-in.
     */
    private AuthDtos.TokenResponse response(TokenService.TokenPair pair, User user, Instant loggedInAt) {
        AuthDtos.UserResponse mapped = UserMapper.toResponse(user);
        return new AuthDtos.TokenResponse(pair.accessToken(), pair.refreshToken(), "Bearer",
                pair.expiresInSeconds(),
                new AuthDtos.UserResponse(mapped.id(), mapped.username(), mapped.email(), mapped.fullName(),
                        mapped.active(), mapped.mustChangePassword(), mapped.roles(), loggedInAt));
    }
}
