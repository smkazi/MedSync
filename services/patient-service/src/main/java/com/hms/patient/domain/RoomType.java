package com.hms.patient.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

/**
 * What a room is for — configuration, not code.
 *
 * <p>This was an enum. It should not have been. Adding a dialysis unit, a physiotherapy room or a
 * radiology suite to a hospital whose building differs from the one this was seeded from would have
 * meant a new enum constant, a recompile, a redeploy, and a migration to widen a CHECK constraint —
 * for what is in the end a row of reference data. Worse, the behaviour each type implied lived in a
 * {@code switch} in the service, so extending the taxonomy meant editing the logic that consumes
 * it: the Open/Closed Principle violated in the most literal way available.
 *
 * <p>So the taxonomy is rows and the behaviour is columns. Three flags, deliberately independent
 * because the three questions are:
 *
 * <ul>
 *   <li>{@link #isClinical()} — are patients seen or treated here? Governs whether beds and
 *       clinical filters apply at all.</li>
 *   <li>{@link #isBedAllocated()} — is space handed out as a bed rather than a calendar slot? True
 *       for casualty and for wards, where arrivals are unscheduled or stays last days.</li>
 *   <li>{@link #isSchedulable()} — may rooms of this type carry appointments?</li>
 * </ul>
 *
 * <p>A type can be clinical without being schedulable; a casualty bay is exactly that. The
 * combinations that would misbehave are refused by CHECK constraints on the table rather than
 * trusted to whoever edits the row next, because a bay marked schedulable would let a booked
 * outpatient be sent to a resuscitation position.
 *
 * <p>Identity is the {@code code}, not a surrogate UUID: this is a small vocabulary that other
 * services and the UI refer to by name, and a generated id would add a lookup to every reference
 * for no benefit. That is also why this does not extend {@code BaseEntity}.
 */
@Entity
@Table(name = "room_types")
public class RoomType {

    @Id
    @Column(name = "code", nullable = false, updatable = false, length = 24)
    private String code;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "clinical", nullable = false)
    private boolean clinical;

    @Column(name = "bed_allocated", nullable = false)
    private boolean bedAllocated;

    @Column(name = "schedulable", nullable = false)
    private boolean schedulable;

    @Column(name = "display_order", nullable = false)
    private short displayOrder = 100;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RoomType() {
    }

    public RoomType(String code, String name, boolean clinical, boolean bedAllocated,
                    boolean schedulable) {
        this.code = code;
        this.name = name;
        this.clinical = clinical;
        this.bedAllocated = bedAllocated;
        this.schedulable = schedulable;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /** Whether patients are seen or treated here. */
    public boolean isClinical() {
        return clinical;
    }

    public void setClinical(boolean clinical) {
        this.clinical = clinical;
    }

    /**
     * Whether space here is handed out as a bed rather than booked as a calendar slot.
     *
     * <p>Casualty arrivals are unscheduled and in-patients stay for days; neither belongs on a
     * fifteen-minute calendar.
     */
    public boolean isBedAllocated() {
        return bedAllocated;
    }

    public void setBedAllocated(boolean bedAllocated) {
        this.bedAllocated = bedAllocated;
    }

    /** Whether rooms of this type may carry appointments. */
    public boolean isSchedulable() {
        return schedulable;
    }

    public void setSchedulable(boolean schedulable) {
        this.schedulable = schedulable;
    }

    public short getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(short displayOrder) {
        this.displayOrder = displayOrder;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof RoomType that && Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(code);
    }

    @Override
    public String toString() {
        return code;
    }
}
