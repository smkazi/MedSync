package com.hms.imaging.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A study that arrived: one examination's worth of images, as the modality named it.
 *
 * <p>{@code orderId} is nullable and that is the design. A study whose accession number matches no
 * order is kept, flagged and reported rather than discarded or attached to the closest-looking
 * patient: filing images against the wrong person is worse than filing them against nobody, and the
 * images exist whatever this platform makes of them.
 */
@Entity
@Table(name = "imaging_studies")
public class ImagingStudy extends BaseEntity {

    @Column(name = "study_instance_uid", nullable = false, length = 64, updatable = false)
    private String studyInstanceUid;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "accession_no", length = 24)
    private String accessionNo;

    @Column(name = "patient_id")
    private UUID patientId;

    @Column(name = "patient_mrn", length = 24)
    private String patientMrn;

    @Column(name = "modality", length = 16)
    private String modality;

    @Column(name = "study_description", length = 160)
    private String studyDescription;

    @Column(name = "study_date")
    private LocalDate studyDate;

    @Column(name = "institution", length = 120)
    private String institution;

    @Column(name = "referring_physician", length = 160)
    private String referringPhysician;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    protected ImagingStudy() {
    }

    public ImagingStudy(String studyInstanceUid) {
        this.studyInstanceUid = studyInstanceUid;
    }

    /**
     * Attaches this study to the order whose accession number it carries.
     *
     * <p>Copies the patient identity from the order rather than from the DICOM header. The header's
     * patient id is whatever was typed at the modality console, and a mistyped one is the single
     * most common way a study ends up on the wrong chart; the order's is the platform's own. The
     * header values are still parsed and are what the reconciliation screen shows for a study that
     * matched nothing, because there they are all there is.
     */
    public void matchTo(ImagingOrder order) {
        this.orderId = order.getId();
        this.patientId = order.getPatientId();
        this.patientMrn = order.getPatientMrn();
    }

    public boolean isUnmatched() {
        return orderId == null;
    }

    public String getStudyInstanceUid() {
        return studyInstanceUid;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getAccessionNo() {
        return accessionNo;
    }

    public void setAccessionNo(String accessionNo) {
        this.accessionNo = accessionNo;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientMrn() {
        return patientMrn;
    }

    public String getModality() {
        return modality;
    }

    public void setModality(String modality) {
        this.modality = modality;
    }

    public String getStudyDescription() {
        return studyDescription;
    }

    public void setStudyDescription(String studyDescription) {
        this.studyDescription = studyDescription;
    }

    public LocalDate getStudyDate() {
        return studyDate;
    }

    public void setStudyDate(LocalDate studyDate) {
        this.studyDate = studyDate;
    }

    public String getInstitution() {
        return institution;
    }

    public void setInstitution(String institution) {
        this.institution = institution;
    }

    public String getReferringPhysician() {
        return referringPhysician;
    }

    public void setReferringPhysician(String referringPhysician) {
        this.referringPhysician = referringPhysician;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
