package com.hms.imaging.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One acquisition within a study: a projection, a sequence, a phase. */
@Entity
@Table(name = "imaging_series")
public class ImagingSeries extends BaseEntity {

    @Column(name = "series_instance_uid", nullable = false, length = 64, updatable = false)
    private String seriesInstanceUid;

    @Column(name = "study_id", nullable = false, updatable = false)
    private UUID studyId;

    @Column(name = "series_number")
    private Integer seriesNumber;

    @Column(name = "modality", length = 16)
    private String modality;

    @Column(name = "series_description", length = 160)
    private String seriesDescription;

    @Column(name = "body_part", length = 64)
    private String bodyPart;

    protected ImagingSeries() {
    }

    public ImagingSeries(String seriesInstanceUid, UUID studyId) {
        this.seriesInstanceUid = seriesInstanceUid;
        this.studyId = studyId;
    }

    public String getSeriesInstanceUid() {
        return seriesInstanceUid;
    }

    public UUID getStudyId() {
        return studyId;
    }

    public Integer getSeriesNumber() {
        return seriesNumber;
    }

    public void setSeriesNumber(Integer seriesNumber) {
        this.seriesNumber = seriesNumber;
    }

    public String getModality() {
        return modality;
    }

    public void setModality(String modality) {
        this.modality = modality;
    }

    public String getSeriesDescription() {
        return seriesDescription;
    }

    public void setSeriesDescription(String seriesDescription) {
        this.seriesDescription = seriesDescription;
    }

    public String getBodyPart() {
        return bodyPart;
    }

    public void setBodyPart(String bodyPart) {
        this.bodyPart = bodyPart;
    }
}
