package com.hms.identity.service;

import com.hms.identity.domain.RefreshToken;
import com.hms.identity.repo.RefreshTokenRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes refresh tokens in their own transaction.
 *
 * <p>This exists because the revocations that matter most happen on paths that then throw:
 * detecting a replayed refresh token must burn the whole rotation family <em>and</em> reject the
 * request. Revoking inside the caller's transaction would roll the revocation back with the
 * rejection, leaving a stolen token usable. A separate bean is required so the call goes through
 * the transactional proxy rather than being a self-invocation.
 */
@Service
public class RefreshTokenRevoker {

    private final RefreshTokenRepository refreshTokens;

    public RefreshTokenRevoker(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(UUID familyId, String reason) {
        List<RefreshToken> family = refreshTokens.findByFamilyId(familyId);
        family.forEach(token -> token.revoke(reason));
        refreshTokens.saveAll(family);
        return family.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeOne(UUID tokenId, String reason) {
        refreshTokens.findById(tokenId).ifPresent(token -> {
            token.revoke(reason);
            refreshTokens.save(token);
        });
    }
}
