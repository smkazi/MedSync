package com.hms.imaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * The catalogue of what can be ordered: configuration, not code.
 *
 * <p>Keyed by its own code rather than a surrogate id, like the charge items and the payers — a
 * deployment's radiology catalogue is a list it maintains, and a code is what an ordering screen,
 * a modality and a charge list all name the same examination by.
 */
@Entity
@Table(name = "imaging_procedures")
public class ImagingProcedure {

    @Id
    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "modality", nullable = false, length = 16)
    private String modality;

    @Column(name = "body_part", length = 64)
    private String bodyPart;

    @Column(name = "minutes", nullable = false)
    private int minutes = 15;

    @Column(name = "contrast", nullable = false)
    private boolean contrast;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    protected ImagingProcedure() {
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getModality() {
        return modality;
    }

    public String getBodyPart() {
        return bodyPart;
    }

    public int getMinutes() {
        return minutes;
    }

    public boolean isContrast() {
        return contrast;
    }

    public boolean isActive() {
        return active;
    }

    public Long getVersion() {
        return version;
    }
}
