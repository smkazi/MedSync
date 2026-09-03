package com.hms.identity.repo;

import com.hms.identity.domain.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyId(UUID familyId);

    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);

    /**
     * When the session behind this rotation family began.
     *
     * <p>Rotation inserts a new row and revokes the old one, so the <em>current</em> token's
     * {@code createdAt} is the moment of last activity and says nothing about how old the session
     * is. The family's first row is the sign-in. Without this, "session lifetime" would mean
     * whatever the last refresh reset it to — which is to say nothing at all.
     */
    @Query("select min(t.createdAt) from RefreshToken t where t.familyId = :familyId")
    Optional<Instant> familyStartedAt(@Param("familyId") UUID familyId);
}
