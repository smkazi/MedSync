package com.hms.imaging.domain;

import com.hms.common.error.ConflictException;
import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * What the radiologist said about a study.
 *
 * <p>One report per study, enforced by a unique constraint. Two reports on one study is two answers
 * to the same question, and the wrong one will be the one somebody reads.
 *
 * <p>Signing is release, exactly as verifying is in the laboratory: there is no second step, and
 * from the moment it is signed this is what another clinician treats from. Which is why an
 * amendment does not overwrite — the superseded text is kept, because a report that was acted on is
 * part of the record whether or not it was later corrected.
 */
@Entity
@Table(name = "imaging_reports")
public class ImagingReport extends BaseEntity {

    @Column(name = "study_id", nullable = false, updatable = false)
    private UUID studyId;

    @Column(name = "findings", nullable = false)
    private String findings;

    @Column(name = "impression", nullable = false)
    private String impression;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ImagingEnums.ReportStatus status = ImagingEnums.ReportStatus.DRAFT;

    @Column(name = "reported_by", nullable = false, length = 64)
    private String reportedBy;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt = Instant.now();

    @Column(name = "signed_by", length = 64)
    private String signedBy;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "amended_from")
    private String amendedFrom;

    @Column(name = "amended_reason", length = 500)
    private String amendedReason;

    protected ImagingReport() {
    }

    public ImagingReport(UUID studyId, String findings, String impression, String reportedBy) {
        this.studyId = studyId;
        this.findings = findings;
        this.impression = impression;
        this.reportedBy = reportedBy;
    }

    public boolean isDraft() {
        return status == ImagingEnums.ReportStatus.DRAFT;
    }

    public boolean isReleased() {
        return status != ImagingEnums.ReportStatus.DRAFT;
    }

    /** Rewrites a draft. Refused once signed, which is what {@link #amend} is for. */
    public void reviseDraft(String newFindings, String newImpression, String author) {
        if (isReleased()) {
            throw new ConflictException(
                    "This report is signed. Correcting it is an amendment, which keeps the text"
                            + " that was signed and records why it changed.");
        }
        this.findings = newFindings;
        this.impression = newImpression;
        this.reportedBy = author;
        this.reportedAt = Instant.now();
    }

    public void sign(String signatory) {
        if (isReleased()) {
            throw new ConflictException("This report has already been signed by " + signedBy);
        }
        this.status = ImagingEnums.ReportStatus.SIGNED;
        this.signedBy = signatory;
        this.signedAt = Instant.now();
    }

    /**
     * Supersedes a signed report, keeping what it said.
     *
     * <p>The previous findings and impression are folded into {@code amendedFrom} as one block
     * rather than into two columns: it is a historical artefact to be read, not fields to be
     * queried, and a schema that invited querying them would invite treating the old answer as an
     * answer.
     */
    public void amend(String newFindings, String newImpression, String reason, String signatory) {
        if (!isReleased()) {
            throw new ConflictException(
                    "A draft is not amended, it is edited. Only a signed report can be amended.");
        }
        this.amendedFrom = "Findings: %s%n%nImpression: %s%n%n(signed by %s at %s)"
                .formatted(findings, impression, signedBy, signedAt);
        this.amendedReason = reason;
        this.findings = newFindings;
        this.impression = newImpression;
        this.status = ImagingEnums.ReportStatus.AMENDED;
        this.signedBy = signatory;
        this.signedAt = Instant.now();
    }

    public UUID getStudyId() {
        return studyId;
    }

    public String getFindings() {
        return findings;
    }

    public String getImpression() {
        return impression;
    }

    public ImagingEnums.ReportStatus getStatus() {
        return status;
    }

    public String getReportedBy() {
        return reportedBy;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public String getSignedBy() {
        return signedBy;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public String getAmendedFrom() {
        return amendedFrom;
    }

    public String getAmendedReason() {
        return amendedReason;
    }
}
