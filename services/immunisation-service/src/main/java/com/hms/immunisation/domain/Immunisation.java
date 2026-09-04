package com.hms.immunisation.domain;

import com.hms.common.jpa.BaseEntity;
import com.hms.immunisation.domain.ImmunisationEnums.ImmunisationSource;
import com.hms.immunisation.domain.ImmunisationEnums.Route;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One dose, wherever it happened.
 *
 * <p>Two named static factories rather than a constructor, the shape {@code Administration} uses in
 * the pharmacy — and here the two differ in what they are allowed to claim rather than only in what
 * they set. {@link #givenHere} has a lot, a route, a site and a name; {@link #historical} has none
 * of those and carries a sentence saying what was seen instead. The database agrees:
 * {@code chk_lot_iff_given_here} is a biconditional, so a historical dose carrying a lot number and
 * a here-given dose without one are both unrepresentable rather than merely discouraged.
 *
 * <p>Everything is {@code updatable = false}. A dose is a thing that happened; correcting a
 * mis-typed one is a delete and a re-record, which leaves a trail, rather than an edit that
 * silently rewrites what the register said yesterday.
 */
@Entity
@Table(name = "immunisations")
public class Immunisation extends BaseEntity {

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, updatable = false, length = 24)
    private String patientMrn;

    @Column(name = "encounter_id", updatable = false)
    private UUID encounterId;

    @Column(name = "product_code", nullable = false, updatable = false, length = 32)
    private String productCode;

    /** Snapshotted, not joined: renaming a catalogue entry must not rewrite a record from last year. */
    @Column(name = "product_name", nullable = false, updatable = false, length = 160)
    private String productName;

    @Column(name = "lot_id", updatable = false)
    private UUID lotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 28)
    private ImmunisationSource source;

    /**
     * The clinical date, and the date every interval in every schedule is measured against.
     *
     * <p>A {@code LocalDate} and not an {@code Instant}. A schedule says "28 days after dose 1", a
     * vaccination card says a date, and an interval between two dates has no zone in it — which is
     * what makes {@code ImmunisationScheduleCalculator} zone-free by construction rather than by
     * discipline.
     */
    @Column(name = "given_on", nullable = false, updatable = false)
    private LocalDate givenOn;

    @Column(name = "given_on_estimated", nullable = false, updatable = false)
    private boolean givenOnEstimated;

    @Enumerated(EnumType.STRING)
    @Column(name = "route", updatable = false, length = 24)
    private Route route;

    @Column(name = "site", updatable = false, length = 32)
    private String site;

    @Column(name = "given_by", updatable = false, length = 120)
    private String givenBy;

    @Column(name = "evidence", updatable = false, length = 500)
    private String evidence;

    @Column(name = "recorded_at", nullable = false, updatable = false, insertable = false)
    private Instant recordedAt;

    @Column(name = "recorded_by", nullable = false, updatable = false, length = 120)
    private String recordedBy;

    protected Immunisation() {
    }

    /** A dose given in this hospital, out of a lot this hospital holds. */
    public static Immunisation givenHere(UUID patientId, String patientMrn, UUID encounterId,
                                         VaccineProduct product, VaccineLot lot, LocalDate givenOn,
                                         String site, String givenBy, String recordedBy) {
        Immunisation dose = new Immunisation();
        dose.patientId = patientId;
        dose.patientMrn = patientMrn;
        dose.encounterId = encounterId;
        dose.productCode = product.getCode();
        dose.productName = product.getName();
        dose.lotId = lot.getId();
        dose.source = ImmunisationSource.ADMINISTERED_HERE;
        dose.givenOn = givenOn;
        // Never estimated: this is a date the platform observed.
        dose.givenOnEstimated = false;
        // The product's route, not a typed one — see VaccineProduct.
        dose.route = product.getRoute();
        dose.site = site;
        dose.givenBy = givenBy;
        dose.recordedBy = recordedBy;
        return dose;
    }

    /**
     * A dose given somewhere else.
     *
     * <p>No lot, deliberately, and the database agrees rather than trusting this method: a recalled
     * lot is traced through this register, and a lot number somebody typed off the top of their
     * head would make that trace confidently wrong — which is worse than incomplete, because
     * nobody goes looking further after a confident answer.
     */
    public static Immunisation historical(UUID patientId, String patientMrn, VaccineProduct product,
                                          LocalDate givenOn, boolean dateEstimated,
                                          ImmunisationSource source, String evidence,
                                          String recordedBy) {
        if (source == ImmunisationSource.ADMINISTERED_HERE) {
            throw new IllegalArgumentException(
                    "A dose given here is recorded through givenHere, which has a lot number");
        }
        Immunisation dose = new Immunisation();
        dose.patientId = patientId;
        dose.patientMrn = patientMrn;
        dose.productCode = product.getCode();
        dose.productName = product.getName();
        dose.source = source;
        dose.givenOn = givenOn;
        dose.givenOnEstimated = dateEstimated;
        dose.evidence = evidence;
        dose.recordedBy = recordedBy;
        return dose;
    }

    /** True when this platform witnessed the dose, rather than being told about it. */
    public boolean wasGivenHere() {
        return source == ImmunisationSource.ADMINISTERED_HERE;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientMrn() {
        return patientMrn;
    }

    public UUID getEncounterId() {
        return encounterId;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getProductName() {
        return productName;
    }

    public UUID getLotId() {
        return lotId;
    }

    public ImmunisationSource getSource() {
        return source;
    }

    public LocalDate getGivenOn() {
        return givenOn;
    }

    public boolean isGivenOnEstimated() {
        return givenOnEstimated;
    }

    public Route getRoute() {
        return route;
    }

    public String getSite() {
        return site;
    }

    public String getGivenBy() {
        return givenBy;
    }

    public String getEvidence() {
        return evidence;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getRecordedBy() {
        return recordedBy;
    }
}
