package com.hms.identity.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.SessionExpiredException;
import com.hms.common.security.CurrentUser;
import com.hms.identity.domain.RefreshToken;
import com.hms.identity.domain.User;
import com.hms.identity.repo.RefreshTokenRepository;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints access tokens and manages refresh-token rotation.
 *
 * <p>Access tokens are short-lived RS256 JWTs that every other service validates offline against
 * the JWKS — no service ever calls identity on the request path. Refresh tokens are opaque random
 * strings kept only as SHA-256 hashes, rotated on every use, with reuse of an already-rotated
 * token treated as theft: the whole rotation family is revoked.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int REFRESH_TOKEN_BYTES = 32;

    /**
     * Set on a token minted for an account that has not yet changed its initial password. It tells
     * a client why its session can do nothing; the enforcement is the empty {@code roles} claim
     * beside it, not this flag, so a client that ignores it gains nothing.
     */
    public static final String PASSWORD_CHANGE_REQUIRED_CLAIM = "pwd_change_required";

    private final RefreshTokenRepository refreshTokens;
    private final RefreshTokenRevoker revoker;
    private final KeyService keys;
    private final AuditService audit;
    private final String issuer;
    private final String audience;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final Duration idleTimeout;
    private final Duration portalIdleTimeout;
    private final Duration sessionMaxLifetime;

    public TokenService(RefreshTokenRepository refreshTokens, RefreshTokenRevoker revoker, KeyService keys,
                        AuditService audit,
                        @Value("${hms.jwt.issuer:http://localhost:8081}") String issuer,
                        @Value("${hms.jwt.audience:hms}") String audience,
                        @Value("${hms.jwt.access-token-ttl:PT15M}") Duration accessTokenTtl,
                        @Value("${hms.jwt.refresh-token-ttl:P30D}") Duration refreshTokenTtl,
                        @Value("${hms.jwt.idle-timeout:PT30M}") Duration idleTimeout,
                        @Value("${hms.jwt.portal-idle-timeout:PT15M}") Duration portalIdleTimeout,
                        @Value("${hms.jwt.session-max-lifetime:P7D}") Duration sessionMaxLifetime) {
        this.refreshTokens = refreshTokens;
        this.revoker = revoker;
        this.keys = keys;
        this.audit = audit;
        this.issuer = issuer;
        this.audience = audience;
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
        this.idleTimeout = idleTimeout;
        this.portalIdleTimeout = portalIdleTimeout;
        this.sessionMaxLifetime = sessionMaxLifetime;
    }

    /** A freshly issued access/refresh pair plus the access token's lifetime in seconds. */
    public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    public long accessTokenTtlSeconds() {
        return accessTokenTtl.toSeconds();
    }

    @Transactional
    public TokenPair issueFor(User user, String userAgent) {
        return issueFor(user, UUID.randomUUID(), userAgent);
    }

    @Transactional
    public TokenPair issueFor(User user, UUID familyId, String userAgent) {
        String accessToken = signAccessToken(user);
        String rawRefresh = randomToken();
        refreshTokens.save(new RefreshToken(user.getId(), sha256Hex(rawRefresh), familyId,
                Instant.now().plus(refreshTokenTtl), truncate(userAgent)));
        return new TokenPair(accessToken, rawRefresh, accessTokenTtl.toSeconds());
    }

    /**
     * Finds the stored record a presented refresh token matches, refusing an unknown, replayed or
     * expired one — and <strong>without</strong> marking it rotated.
     *
     * <p>Splitting the lookup from the rotation is not tidiness; it is required for correctness.
     * Every refusal on this path revokes rows in its own transaction, because the method then
     * throws and a revocation inside the caller's transaction would roll back with the rejection,
     * leaving a stolen token usable. That only works while the caller's transaction holds no lock
     * on those rows. Mark the presented token rotated first and the next query flushes that UPDATE,
     * taking a row lock the {@code REQUIRES_NEW} revocation then waits on forever — and PostgreSQL
     * cannot break the tie, because the outer transaction is not waiting on a lock, it is waiting
     * on the application. So: check everything, then rotate.
     *
     * @throws BadCredentialsException if the token is unknown, expired, or already used -
     *         one indistinguishable response for all three, so a caller cannot probe which
     */
    @Transactional
    public RefreshToken findForRotation(String rawRefreshToken) {
        // 401, not 400: the token is a credential, and a client that gets a 400 has no reason to
        // send the user back to sign in. Every rejection below reads the same to the caller, so a
        // spent token cannot be distinguished from a forged one.
        RefreshToken stored = refreshTokens.findByTokenHash(sha256Hex(rawRefreshToken))
                .orElseThrow(() -> new BadCredentialsException("Refresh token is not valid"));

        if (stored.isRevoked()) {
            // A revoked token being presented means it leaked after rotation. Burn the family --
            // in its own transaction, because this method is about to throw.
            int revoked = revoker.revokeFamily(stored.getFamilyId(), "reuse-detected");
            log.warn("Refresh token reuse detected for user {}; revoked {} token(s) in family {}",
                    stored.getUserId(), revoked, stored.getFamilyId());
            // Logged since this class was written and never audited, which meant the one event on
            // this path that looks like theft was invisible to the audit report.
            audit.record("REFRESH_TOKEN_REUSE", "User", stored.getUserId(),
                    "family " + stored.getFamilyId() + "; " + revoked + " token(s) revoked");
            throw new BadCredentialsException("Refresh token is not valid");
        }
        if (stored.isExpired()) {
            revoker.revokeOne(stored.getId(), "expired");
            throw new BadCredentialsException("Refresh token is not valid");
        }
        return stored;
    }

    /**
     * Marks a token rotated, once every check that could refuse the rotation has passed. Called
     * last for the reason {@link #findForRotation} explains at length.
     */
    @Transactional
    public void markRotated(RefreshToken stored) {
        stored.revoke("rotated");
    }

    /**
     * Refuses a rotation whose session has run out of time, and burns the family when it does.
     *
     * <p>Two separate bounds, because they answer different questions and one without the other is
     * not a session timeout:
     *
     * <ul>
     *   <li><strong>Idle.</strong> Rotation inserts a new row and revokes the old one, and
     *       {@code BaseEntity} stamps {@code created_at} on insert and never updates it. So the
     *       consumed token's {@code createdAt} <em>is</em> the moment of last activity, exactly,
     *       and no column had to be added to hold it. A portal account gets a shorter bound: a
     *       patient portal is opened on shared and family machines far more often than a clinical
     *       workstation is.
     *   <li><strong>Absolute.</strong> {@code expiresAt} is re-set to {@code now + refreshTokenTtl}
     *       on <em>every</em> rotation, so the thirty days slide forward and a session refreshed
     *       once a day never expires at all. Measuring from the family's first row is what makes
     *       the phrase "session lifetime" mean something: a stolen refresh token that keeps being
     *       used cannot keep itself alive indefinitely.
     * </ul>
     *
     * <p>Either breach revokes the whole family rather than just the presented token, because the
     * point is to end the session, not to make the client ask again with the other half of it.
     *
     * <p>A negative duration disables a bound; zero refuses everything, which is what the test
     * profile binds it to so the assertions are about the rule rather than about a sleep.
     */
    @Transactional
    public void enforceSessionBounds(RefreshToken consumed, boolean portalAccount) {
        Instant now = Instant.now();
        Duration idle = portalAccount ? portalIdleTimeout : idleTimeout;
        if (exceeds(consumed.getCreatedAt(), now, idle)) {
            int revoked = revoker.revokeFamily(consumed.getFamilyId(), "idle-timeout");
            log.info("Session for user {} timed out after {} idle; revoked {} token(s)",
                    consumed.getUserId(), idle, revoked);
            audit.record("SESSION_EXPIRED", "User", consumed.getUserId(),
                    "idle timeout after " + idle + "; " + revoked + " token(s) revoked");
            throw new SessionExpiredException(
                    "Your session timed out after " + describe(idle) + " without activity. Please sign in again.");
        }
        Instant startedAt = refreshTokens.familyStartedAt(consumed.getFamilyId()).orElse(consumed.getCreatedAt());
        if (exceeds(startedAt, now, sessionMaxLifetime)) {
            int revoked = revoker.revokeFamily(consumed.getFamilyId(), "session-lifetime");
            log.info("Session for user {} reached its {} lifetime; revoked {} token(s)",
                    consumed.getUserId(), sessionMaxLifetime, revoked);
            audit.record("SESSION_EXPIRED", "User", consumed.getUserId(),
                    "absolute lifetime " + sessionMaxLifetime + "; " + revoked + " token(s) revoked");
            throw new SessionExpiredException(
                    "Sessions end after " + describe(sessionMaxLifetime) + " however active they are. "
                            + "Please sign in again.");
        }
    }

    private static boolean exceeds(Instant since, Instant now, Duration bound) {
        if (since == null || bound.isNegative()) {
            return false;
        }
        return Duration.between(since, now).compareTo(bound) >= 0;
    }

    /** A duration in the words a person uses, so the refusal reads as a sentence. */
    private static String describe(Duration duration) {
        long days = duration.toDays();
        if (days > 0) {
            return days + (days == 1 ? " day" : " days");
        }
        long hours = duration.toHours();
        if (hours > 0) {
            return hours + (hours == 1 ? " hour" : " hours");
        }
        long minutes = duration.toMinutes();
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        refreshTokens.findByTokenHash(sha256Hex(rawRefreshToken))
                .ifPresent(token -> token.revoke("logout"));
    }

    @Transactional
    public void revokeFamily(UUID familyId, String reason) {
        revoker.revokeFamily(familyId, reason);
    }

    @Transactional
    public int revokeAllForUser(UUID userId, String reason) {
        List<RefreshToken> active = refreshTokens.findByUserIdAndRevokedAtIsNull(userId);
        active.forEach(token -> token.revoke(reason));
        return active.size();
    }

    public Optional<RefreshToken> find(String rawRefreshToken) {
        return refreshTokens.findByTokenHash(sha256Hex(rawRefreshToken));
    }

    /**
     * Builds and signs the access token. Claims here are what every other service authorises on.
     *
     * <p>An account still on its initial password gets a token with <strong>no roles</strong>. That
     * is the whole gate, and it is deliberately structural rather than a list of blocked paths:
     * every endpoint on this platform outside {@code /auth/**} is behind a {@code @PreAuthorize}
     * naming at least one role, so a role-less token is refused everywhere by the authorisation
     * rules that already exist — in all five services, including ones written later. Refusing the
     * login outright would be simpler and useless: {@code POST /auth/change-password} needs a
     * token, so the only way out of the state would be an administrator.
     *
     * <p>The token is still a real session: {@code /auth/me}, {@code /auth/logout} and
     * {@code /auth/change-password} carry no role requirement, so the account can see who it is,
     * fix its password and sign out. Changing it clears the flag and revokes every session, so the
     * next sign-in mints a token with the roles.
     */
    private String signAccessToken(User user) {
        // Stated rather than assumed: every caller passes a persisted user, and an id is null only
        // before the insert. Without this, an unpersisted user would produce a token with the
        // string "null" as its subject or an NPE five frames from the cause - and a token whose
        // subject is not a real user id is the worst of the two.
        UUID subject = Objects.requireNonNull(user.getId(), "cannot sign a token for an unsaved user");
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject.toString())
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(accessTokenTtl)))
                .jwtID(UUID.randomUUID().toString())
                .claim("preferred_username", user.getUsername())
                .claim("name", user.getFullName())
                .claim("email", user.getEmail())
                .claim("roles", user.isMustChangePassword() ? List.of() : List.copyOf(user.roleCodes()))
                .claim(PASSWORD_CHANGE_REQUIRED_CLAIM, user.isMustChangePassword())
                // Whose record this session may read, for a portal account, and absent entirely for
                // the staff accounts that are most of this table. It is a claim rather than a
                // lookup because it has to be true at every hop: the portal endpoints live in five
                // services, none of which can call back here to ask, and a signed claim is the one
                // thing they can all trust without another round trip. Signed with the roles, so an
                // account cannot be pointed at a different patient without minting a new token.
                .claim(CurrentUser.PATIENT_CLAIM,
                        user.getPatientId() == null ? null : user.getPatientId().toString())
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(keys.activeKey().getKeyID())
                .type(com.nimbusds.jose.JOSEObjectType.JWT)
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new RSASSASigner(keys.activeKey().toRSAPrivateKey()));
        } catch (JOSEException ex) {
            throw new IllegalStateException("Could not sign access token", ex);
        }
        return jwt.serialize();
    }

    private static String randomToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable in this JVM", ex);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
