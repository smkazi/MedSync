package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One person on one encounter's care team, and how they came to be on it.
 *
 * <p>This is what the chart guard reads. {@code Roles.CHART_READ} still says which roles may look
 * at a clinical record at all; membership here says whose record. The two are different questions
 * and neither substitutes for the other.
 *
 * <p>Deliberately not a relationship to {@link Encounter}: the guard's query is by encounter id and
 * user id and never wants the encounter loaded, and this table is read on the way in to every chart
 * read on the platform. A {@code @ManyToOne} would put a join on the hottest path here in exchange
 * for nothing.
 */
@Entity
@Table(name = "encounter_care_team")
public class CareTeamMember extends BaseEntity {

    /** How somebody came to be on the team. Recorded, because these are not the same act. */
    public enum MemberRole {
        /** The encounter's own clinician, enrolled by the platform when it opened. */
        TREATING_CLINICIAN,
        /**
         * Whoever opened the encounter, when that is somebody other than its clinician — the nurse
         * at the desk starting a walk-in for a doctor. Enrolled by the platform too: opening an
         * encounter for a patient is itself an identified clinical act at the point of care, and
         * making that person break the glass to see the record they just created would teach
         * everybody that break-glass is a formality.
         */
        OPENED_THE_ENCOUNTER,
        /**
         * Somebody who joined by doing something clinical on this encounter — recording obs,
         * writing a note, applying an order set. The ward nurse's route in, and the reason there
         * is one: nurses appear in {@code encounters.clinician_id} nowhere, so without this every
         * nurse would break the glass on every patient they were sent to, and a control everybody
         * trips over every hour is a control everybody learns to ignore.
         */
        PROVIDED_CARE,
        /** Somebody who joined by recording a reason. Cover, a handover, an emergency. */
        BREAK_GLASS
    }

    @Column(name = "encounter_id", nullable = false, updatable = false)
    private UUID encounterId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "member_role", nullable = false, length = 32)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private MemberRole memberRole;

    /**
     * Why, in the words of whoever joined. Clinical free text lives here and not in the audit
     * record's {@code detail}, which the platform's own rule says must never carry any: "query
     * sepsis, patient unresponsive" is a clinical observation, and this is the clinical schema.
     */
    @Column(name = "reason")
    private String reason;

    @Column(name = "joined_by")
    private UUID joinedBy;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    /** Null for the treating clinician, who does not lapse off their own encounter. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    protected CareTeamMember() {
    }

    private CareTeamMember(UUID encounterId, UUID userId, MemberRole memberRole, String reason,
                           UUID joinedBy, Instant expiresAt) {
        this.encounterId = encounterId;
        this.userId = userId;
        this.memberRole = memberRole;
        this.reason = reason;
        this.joinedBy = joinedBy;
        this.expiresAt = expiresAt;
    }

    /** The encounter's own clinician, enrolled by the platform. No reason, and no expiry. */
    public static CareTeamMember treatingClinician(UUID encounterId, UUID clinicianId) {
        return new CareTeamMember(encounterId, clinicianId, MemberRole.TREATING_CLINICIAN,
                null, null, null);
    }

    /** Whoever opened the encounter, when that is not its clinician. Also no reason and no expiry. */
    public static CareTeamMember opener(UUID encounterId, UUID userId) {
        return new CareTeamMember(encounterId, userId, MemberRole.OPENED_THE_ENCOUNTER,
                null, null, null);
    }

    /** Somebody who recorded something clinical on this encounter. No reason asked, none needed. */
    public static CareTeamMember providedCare(UUID encounterId, UUID userId) {
        return new CareTeamMember(encounterId, userId, MemberRole.PROVIDED_CARE, null, null, null);
    }

    /** Somebody who broke the glass: a reason, an actor, and a time when it lapses. */
    public static CareTeamMember breakGlass(UUID encounterId, UUID userId, String reason,
                                            Instant expiresAt) {
        return new CareTeamMember(encounterId, userId, MemberRole.BREAK_GLASS, reason, userId,
                expiresAt);
    }

    public UUID getEncounterId() {
        return encounterId;
    }

    public UUID getUserId() {
        return userId;
    }

    public MemberRole getMemberRole() {
        return memberRole;
    }

    public String getReason() {
        return reason;
    }

    public UUID getJoinedBy() {
        return joinedBy;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** Whether this membership is still good. An expired row is treated as absent. */
    public boolean isCurrent() {
        return expiresAt == null || expiresAt.isAfter(Instant.now());
    }
}
