package com.hms.laboratory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One named cut-off used by the derived morphology narrative.
 *
 * <p>The <em>shape</em> of the sentence is code — size, then chromia, then anisocytosis — because
 * each clause carries behaviour and a new clause needs new logic. The numbers are configuration, so
 * a laboratory retunes them for its population without a deployment.
 *
 * <p>Note these are a third set of numbers, distinct from both the reference interval and the
 * interpretive alert level: a red cell is called microcytic below MCV 76, the microcytosis comment
 * fires below 70, and the reference interval starts at 80. Three numbers, three purposes.
 */
@Entity
@Table(name = "morphology_thresholds")
public class MorphologyThreshold {

    @Id
    @Column(name = "code", nullable = false, length = 32, updatable = false)
    private String code;

    @Column(name = "threshold", nullable = false, precision = 14, scale = 4)
    private BigDecimal threshold;

    @Column(name = "note", nullable = false, length = 200)
    private String note = "";

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected MorphologyThreshold() {
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getCode() {
        return code;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    /**
     * Retunes the cut-off.
     *
     * <p>The only mutator on this class, and the class Javadoc promised it years before it existed:
     * a laboratory retunes these for its population without a deployment. Until now it could not —
     * there was no setter, no service method and no endpoint, so the one number that decides
     * whether a film reads "microcytic" was changeable by nobody short of a migration.
     *
     * <p>The note stays read-only on purpose. It is the word that appears verbatim on a signed
     * report, so retuning a number and rewriting a report's wording are different acts, and only
     * the first was asked for.
     */
    public void setThreshold(BigDecimal threshold) {
        this.threshold = threshold;
    }

    public String getNote() {
        return note;
    }
}
