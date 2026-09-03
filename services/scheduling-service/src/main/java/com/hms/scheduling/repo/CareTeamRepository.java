package com.hms.scheduling.repo;

import com.hms.scheduling.domain.CareTeamMember;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CareTeamRepository extends JpaRepository<CareTeamMember, UUID> {

    /**
     * The guard's question, asked as a boolean so the row never has to be loaded: is this user on
     * this encounter's team right now.
     *
     * <p>{@code expiresAt is null or expiresAt > :now} rather than a Java-side check on a loaded
     * row, because this runs before every chart read on the platform and the answer is one index
     * lookup. Break-glass cover that has lapsed is absent, not present-and-ignored.
     */
    @Query("""
            select count(m) > 0 from CareTeamMember m
            where m.encounterId = :encounterId
              and m.userId = :userId
              and (m.expiresAt is null or m.expiresAt > :now)
            """)
    boolean isCurrentMember(@Param("encounterId") UUID encounterId, @Param("userId") UUID userId,
                            @Param("now") Instant now);

    /** The team, for the card on the chart. Newest first, so a fresh break-glass is at the top. */
    List<CareTeamMember> findByEncounterIdOrderByJoinedAtDesc(UUID encounterId);

    boolean existsByEncounterIdAndUserId(UUID encounterId, UUID userId);
}
