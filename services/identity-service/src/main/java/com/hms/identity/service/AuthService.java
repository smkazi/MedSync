package com.hms.identity.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.NotFoundException;
import com.hms.identity.domain.RefreshToken;
import com.hms.identity.domain.User;
import com.hms.identity.repo.UserRepository;
import com.hms.identity.web.dto.AuthDtos;
import java.time.Instant;
import java.util.Objects;
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
            // Recorded under the name as typed, with no actor id, because there is no account to
            // point at. A hundred of these in a row under a hundred different names is what
            // credential stuffing looks like on this report.
            audit.recordAs("LOGIN_FAILED", "User", username, "unknown username", username, null);
            throw new BadCredentialsException("Invalid username or password");
        }
        // Stated rather than assumed, exactly as TokenService.signAccessToken does: a user loaded
        // from the repository always has an id, and the alternative to saying so is the string
        // "null" in the actor column of an audit row nobody can then trace.
        UUID userId = Objects.requireNonNull(user.getId(), "a persisted user must have an id");
        if (user.isLocked()) {
            audit.recordAs("LOGIN_BLOCKED", "User", userId, "account locked until " + user.getLockedUntil(),
                    user.getUsername(), userId.toString());
            throw new LockedException("Account is temporarily locked after repeated failed logins");
        }
        if (!user.isActive()) {
            audit.recordAs("LOGIN_BLOCKED", "User", userId, "account disabled",
                    user.getUsername(), userId.toString());
            throw new DisabledException("Account is disabled");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            // Committed separately: this method throws, and a rolled-back counter would mean
            // the lockout threshold is never reached.
            loginAttempts.recordFailure(userId);
            audit.recordAs("LOGIN_FAILED", "User", userId, "bad password",
                    user.getUsername(), userId.toString());
            throw new BadCredentialsException("Invalid username or password");
        }

        // Stamped by a targeted statement rather than by mutating the entity: users is
        // optimistically locked, and two simultaneous sign-ins for one account would otherwise
        // collide on the version column and fail a login that was perfectly valid.
        Instant loggedInAt = loginAttempts.recordSuccess(userId);
        TokenService.TokenPair pair = tokens.issueFor(user, userAgent);
        audit.recordAs("LOGIN_SUCCEEDED", "User", userId, "roles " + user.roleCodes(),
                user.getUsername(), userId.toString());
        log.info("User {} logged in", user.getUsername());
        return response(pair, user, loggedInAt);
    }

    @Transactional
    public AuthDtos.TokenResponse refresh(String rawRefreshToken, String userAgent) {
        // Every check first, the rotation last. Each refusal below revokes tokens in its own
        // transaction so the revocation survives the rejection that follows it, and that only
        // works while this transaction holds no lock on those rows -- see TokenService.
        RefreshToken presented = tokens.findForRotation(rawRefreshToken);
        User user = users.findById(presented.getUserId())
                .orElseThrow(() -> new NotFoundException("User for this refresh token no longer exists"));
        if (!user.isActive()) {
            tokens.revokeFamily(presented.getFamilyId(), "user-disabled");
            throw new DisabledException("Account is disabled");
        }
        // After the account is known -- a portal session times out sooner than a clinical one.
        tokens.enforceSessionBounds(presented, user.isPortalAccount());

        tokens.markRotated(presented);
        TokenService.TokenPair pair = tokens.issueFor(user, presented.getFamilyId(), userAgent);
        UUID userId = Objects.requireNonNull(user.getId(), "a persisted user must have an id");
        audit.recordAs("TOKEN_REFRESHED", "User", userId, "family " + presented.getFamilyId(),
                user.getUsername(), userId.toString());
        return response(pair, user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        tokens.find(rawRefreshToken).ifPresent(token -> audit.recordAs("LOGOUT", "User", token.getUserId(),
                "refresh token revoked",
                users.findById(token.getUserId()).map(User::getUsername).orElse(null),
                token.getUserId().toString()));
        tokens.revoke(rawRefreshToken);
    }

    /**
     * Changes the caller's own password.
     *
     * <p>A wrong current password is reported as a wrong current password, unlike everywhere else
     * in this class. The uniform "invalid username or password" exists so login cannot be used to
     * enumerate accounts, and {@code GlobalExceptionHandler} enforces it by flattening every
     * {@link BadCredentialsException} to that sentence — which meant an authenticated user who
     * mistyped their current password was told their username might be wrong. There is nothing to
     * enumerate here: the account is already known, because the caller is signed in as it.
     *
     * <p>The attempt still counts toward the lockout. Someone holding a stolen access token can
     * already act as the user; what a password gets them is persistence past the token's fifteen
     * minutes, so guessing it is worth the same rate limit that guessing at the login form gets.
     */
    @Transactional
    public void changeOwnPassword(UUID userId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElseThrow(() -> NotFoundException.of("User", userId));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            loginAttempts.recordFailure(user.getId());
            audit.record("PASSWORD_CHANGE_FAILED", "User", user.getId(), "wrong current password");
            throw new BadRequestException("Current password is incorrect");
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
