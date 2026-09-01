package com.hms.identity.service;

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

    private final RefreshTokenRepository refreshTokens;
    private final RefreshTokenRevoker revoker;
    private final KeyService keys;
    private final String issuer;
    private final String audience;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;

    public TokenService(RefreshTokenRepository refreshTokens, RefreshTokenRevoker revoker, KeyService keys,
                        @Value("${hms.jwt.issuer:http://localhost:8081}") String issuer,
                        @Value("${hms.jwt.audience:hms}") String audience,
                        @Value("${hms.jwt.access-token-ttl:PT15M}") Duration accessTokenTtl,
                        @Value("${hms.jwt.refresh-token-ttl:P30D}") Duration refreshTokenTtl) {
        this.refreshTokens = refreshTokens;
        this.revoker = revoker;
        this.keys = keys;
        this.issuer = issuer;
        this.audience = audience;
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
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
     * Consumes a refresh token and returns the stored record it matched, revoking it so the caller
     * can issue a replacement in the same family.
     *
     * @throws BadCredentialsException if the token is unknown, expired, or already used -
     *         one indistinguishable response for all three, so a caller cannot probe which
     */
    @Transactional
    public RefreshToken consumeForRotation(String rawRefreshToken) {
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
            throw new BadCredentialsException("Refresh token is not valid");
        }
        if (stored.isExpired()) {
            revoker.revokeOne(stored.getId(), "expired");
            throw new BadCredentialsException("Refresh token is not valid");
        }
        stored.revoke("rotated");
        return stored;
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

    /** Builds and signs the access token. Claims here are what every other service authorises on. */
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
                .claim("roles", List.copyOf(user.roleCodes()))
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
