package com.hms.admissions.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Who is in a bed, and since when.
 *
 * <p>One table for both casualty and in-patient care, with one partial unique index over
 * {@code bed_id WHERE released_at IS NULL}. That index is the control: two clinicians allocating
 * the last bed at the same instant both pass any "is it free?" check the application could make,
 * and one of them then loses the insert. Application code cannot win that race and the database
 * cannot lose it.
 *
 * <p>Released rather than deleted. "Who was in bed 4 last Tuesday" is a real question after an
 * infection-control incident, and a row deleted on discharge cannot answer it.
 */
@Entity
@Table(name = "bed_occupancy")
public class BedOccupancy extends BaseEntity {

    @Column(name = "bed_id", nullable = false, updatable = false)
    private UUID bedId;

    @Column(name = "bed_code", nullable = false, length = 24, updatable = false)
    private String bedCode;

    @Column(name = "room_code", nullable = false, length = 24, updatable = false)
    private String roomCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "occupant_type", nullable = false, length = 16, updatable = false)
    private AdmissionEnums.OccupantType occupantType;

    @Column(name = "occupant_id", nullable = false, updatable = false)
    private UUID occupantId;

    @Column(name = "since", nullable = false, updatable = false)
    private Instant since = Instant.now();

    @Column(name = "released_at")
    private Instant releasedAt;

    protected BedOccupancy() {
    }

    public BedOccupancy(UUID bedId, String bedCode, String roomCode,
                        AdmissionEnums.OccupantType occupantType, UUID occupantId) {
        this.bedId = bedId;
        this.bedCode = bedCode;
        this.roomCode = roomCode;
        this.occupantType = occupantType;
        this.occupantId = occupantId;
    }

    /** Idempotent: releasing twice keeps the first time, which is when the bed actually freed. */
    public void release() {
        if (releasedAt == null) {
            releasedAt = Instant.now();
        }
    }

    public boolean isCurrent() {
        return releasedAt == null;
    }

    public UUID getBedId() {
        return bedId;
    }

    public String getBedCode() {
        return bedCode;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public AdmissionEnums.OccupantType getOccupantType() {
        return occupantType;
    }

    public UUID getOccupantId() {
        return occupantId;
    }

    public Instant getSince() {
        return since;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
