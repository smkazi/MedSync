package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A period a clinician is unavailable, overriding their weekly pattern. */
@Entity
@Table(name = "schedule_blackouts")
public class ScheduleBlackout extends BaseEntity {

    @Column(name = "clinician_id", nullable = false)
    private UUID clinicianId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "reason", length = 255)
    private String reason;

    protected ScheduleBlackout() {
    }

    public ScheduleBlackout(UUID clinicianId, Instant startsAt, Instant endsAt, String reason) {
        this.clinicianId = clinicianId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.reason = reason;
    }

    public UUID getClinicianId() {
        return clinicianId;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public String getReason() {
        return reason;
    }

    public boolean covers(Instant from, Instant to) {
        return startsAt.isBefore(to) && endsAt.isAfter(from);
    }
}
