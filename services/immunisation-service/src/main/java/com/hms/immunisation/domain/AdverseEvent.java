package com.hms.immunisation.domain;

import com.hms.common.jpa.BaseEntity;
import com.hms.immunisation.domain.ImmunisationEnums.Outcome;
import com.hms.immunisation.domain.ImmunisationEnums.Seriousness;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An adverse event following immunisation.
 *
 * <p>The description is free text because an AEFI form is free text, and a coded list would refuse
 * the event nobody anticipated — which is the only kind worth reporting.
 *
 * <p>Note what the table does <em>not</em> carry: a constraint that an event cannot precede the
 * dose it followed. PostgreSQL cannot compare against another table's row inside a CHECK; it would
 * need a trigger. So the rule lives in {@code AefiService} and the migration says why it is not
 * beside the others — a {@code CHECK (true)} standing in for it would read as a constraint and
 * enforce nothing.
 */
@Entity
@Table(name = "adverse_events")
public class AdverseEvent extends BaseEntity {

    @Column(name = "immunisation_id", nullable = false, updatable = false)
    private UUID immunisationId;

    @Column(name = "onset_on", nullable = false, updatable = false)
    private LocalDate onsetOn;

    @Column(name = "description", nullable = false, updatable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "seriousness", nullable = false, length = 20)
    private Seriousness seriousness;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 24)
    private Outcome outcome;

    @Column(name = "reported_by", nullable = false, updatable = false, length = 120)
    private String reportedBy;

    @Column(name = "reported_at", nullable = false, updatable = false, insertable = false)
    private Instant reportedAt;

    protected AdverseEvent() {
    }

    public AdverseEvent(UUID immunisationId, LocalDate onsetOn, String description,
                        Seriousness seriousness, Outcome outcome, String reportedBy) {
        this.immunisationId = immunisationId;
        this.onsetOn = onsetOn;
        this.description = description;
        this.seriousness = seriousness;
        this.outcome = outcome;
        this.reportedBy = reportedBy;
    }

    /**
     * True when this must be reported to the authority whatever anybody thinks of it.
     *
     * <p>{@code SERIOUS} is a term of art rather than an intensifier — death, hospitalisation,
     * disability or a congenital anomaly — and a death is reportable even if somebody has recorded
     * the seriousness as something milder.
     */
    public boolean isReportable() {
        return seriousness.atLeast(Seriousness.SERIOUS) || outcome == Outcome.DIED;
    }

    public UUID getImmunisationId() {
        return immunisationId;
    }

    public LocalDate getOnsetOn() {
        return onsetOn;
    }

    public String getDescription() {
        return description;
    }

    public Seriousness getSeriousness() {
        return seriousness;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    /** Follow-up changes an outcome: "recovering" becomes "recovered", or does not. */
    public void updateOutcome(Outcome outcome) {
        this.outcome = outcome;
    }

    public String getReportedBy() {
        return reportedBy;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }
}
