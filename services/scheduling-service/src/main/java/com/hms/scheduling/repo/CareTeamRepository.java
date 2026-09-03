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

    /**
     * Whether this user is on the care team of <em>any</em> of this patient's encounters.
     *
     * <p>The patient-level question, derived from encounter-level membership rather than stored
     * again. A clinician looking after somebody is on an encounter of theirs, and that is what
     * makes them entitled to the rest of the patient's clinical record — their laboratory orders,
     * their prescriptions — without a second table saying the same thing in different words and
     * eventually disagreeing.
     */
    @Query("""
            select count(t) > 0 from CareTeamMember t, Encounter e
             where t.encounterId = e.id
               and e.patientId = :patientId
               and t.userId = :userId
               and (t.expiresAt is null or t.expiresAt > :now)
            """)
    boolean isOnAnyEncounterFor(@Param("patientId") UUID patientId, @Param("userId") UUID userId,
                                @Param("now") Instant now);
}
