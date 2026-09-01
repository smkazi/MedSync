package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A SOAP-structured clinical note.
 *
 * <p>Unsigned notes are editable. A signed note is not: it is a legal record of what the clinician
 * asserted at that time, so a correction is a new revision that points back at what it amends.
 * That is why there is no setter for the content once {@code signedAt} is set.
 */
@Entity
@Table(name = "clinical_notes")
public class ClinicalNote extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "encounter_id", nullable = false)
    private Encounter encounter;

    @Column(name = "subjective", columnDefinition = "text")
    private String subjective;

    @Column(name = "objective", columnDefinition = "text")
    private String objective;

    @Column(name = "assessment", columnDefinition = "text")
    private String assessment;

    @Column(name = "plan", columnDefinition = "text")
    private String plan;

    @Column(name = "author", nullable = false, length = 64)
    private String author;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "signed_by", length = 64)
    private String signedBy;

    /** Set on an addendum, pointing at the signed note it corrects. */
    @Column(name = "amends_id")
    private UUID amendsId;

    @Column(name = "revision", nullable = false)
    private int revision = 1;

    protected ClinicalNote() {
    }

    public ClinicalNote(Encounter encounter, String author, int revision) {
        this.encounter = encounter;
        this.author = author;
        this.revision = revision;
    }

    public String getSubjective() {
        return subjective;
    }

    public String getObjective() {
        return objective;
    }

    public String getAssessment() {
        return assessment;
    }

    public String getPlan() {
        return plan;
    }

    public String getAuthor() {
        return author;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public String getSignedBy() {
        return signedBy;
    }

    public UUID getAmendsId() {
        return amendsId;
    }

    public void setAmendsId(UUID amendsId) {
        this.amendsId = amendsId;
    }

    public int getRevision() {
        return revision;
    }

    public boolean isSigned() {
        return signedAt != null;
    }

    /**
     * Replaces the note's content. Only legal while unsigned — the service enforces that, and
     * routes a change to a signed note into a new revision instead.
     */
    public void updateContent(String subjective, String objective, String assessment, String plan) {
        this.subjective = subjective;
        this.objective = objective;
        this.assessment = assessment;
        this.plan = plan;
    }

    public void sign(String signedBy) {
        this.signedAt = Instant.now();
        this.signedBy = signedBy;
    }

    /** Whether this note carries any content at all — an empty note should not be signable. */
    public boolean hasContent() {
        return isNotBlank(subjective) || isNotBlank(objective) || isNotBlank(assessment)
                || isNotBlank(plan);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
