package com.hms.interop.domain;

import com.hms.common.jpa.BaseEntity;
import com.hms.interop.domain.InteropEnums.ConsentStatus;
import com.hms.interop.domain.InteropEnums.HiType;
import com.hms.interop.domain.InteropEnums.PurposeCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Permission to disclose a patient's record: what, to whom, for how long, and about which period.
 *
 * <p>Four things have to be true before anything moves, and they are four separate questions this
 * entity answers separately because conflating any two of them is how a consent check becomes
 * decorative:
 *
 * <ol>
 *   <li><strong>Is it granted?</strong> A requested consent is not a granted one, and a denied or
 *       revoked one is a refusal with a reason.</li>
 *   <li><strong>Is it still live?</strong> {@code expiresAt} is when the permission lapses.</li>
 *   <li><strong>Does it cover this kind of information?</strong> A consent for laboratory reports
 *       is not a consent for a prescription.</li>
 *   <li><strong>Does it cover this record's date?</strong> "You may see my records from last year"
 *       is a different sentence from "this permission lasts a year", and {@code coversFrom} /
 *       {@code coversTo} are the first one.</li>
 * </ol>
 *
 * <p>Revocation is a status and a reason on the same row rather than a delete, because the question
 * asked afterwards is "was this data shared lawfully at the time", and a deleted consent cannot
 * answer it.
 */
@Entity
@Table(name = "consent_artefacts")
public class ConsentArtefact extends BaseEntity {

    @Column(name = "artefact_id", nullable = false, length = 64, updatable = false)
    private String artefactId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, length = 24, updatable = false)
    private String patientMrn;

    @Column(name = "requester", nullable = false, length = 160, updatable = false)
    private String requester;

    @Column(name = "requester_id", length = 120, updatable = false)
    private String requesterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose_code", nullable = false, length = 32, updatable = false)
    private PurposeCode purposeCode;

    @Column(name = "purpose_text", length = 255, updatable = false)
    private String purposeText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ConsentStatus status = ConsentStatus.REQUESTED;

    @Column(name = "covers_from", nullable = false, updatable = false)
    private LocalDate coversFrom;

    @Column(name = "covers_to", nullable = false, updatable = false)
    private LocalDate coversTo;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "granted_at")
    private Instant grantedAt;

    @Column(name = "denied_at")
    private Instant deniedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 255)
    private String revokedReason;

    @Column(name = "signature")
    private String signature;

    /**
     * The information types this consent covers.
     *
     * <p>An {@code @ElementCollection} rather than an entity: an HI type has no identity of its own
     * and nothing ever references one. Eagerly fetched because every read of a consent asks what it
     * covers — the check cannot be made without them, so a lazy load here would be a lazy load
     * that always fires.
     */
    @ElementCollection(fetch = FetchType.EAGER, targetClass = HiType.class)
    @CollectionTable(name = "consent_hi_types",
            joinColumns = @JoinColumn(name = "consent_id", nullable = false))
    @Column(name = "hi_type", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private Set<HiType> hiTypes = EnumSet.noneOf(HiType.class);

    protected ConsentArtefact() {
    }

    public ConsentArtefact(String artefactId, UUID patientId, String patientMrn, String requester,
                           String requesterId, PurposeCode purposeCode, String purposeText,
                           LocalDate coversFrom, LocalDate coversTo, Instant expiresAt,
                           Set<HiType> hiTypes) {
        this.artefactId = artefactId;
        this.patientId = patientId;
        this.patientMrn = patientMrn;
        this.requester = requester;
        this.requesterId = requesterId;
        this.purposeCode = purposeCode;
        this.purposeText = purposeText;
        this.coversFrom = coversFrom;
        this.coversTo = coversTo;
        this.expiresAt = expiresAt;
        this.hiTypes = hiTypes.isEmpty() ? EnumSet.noneOf(HiType.class) : EnumSet.copyOf(hiTypes);
    }

    /**
     * Records that the patient said yes.
     *
     * @param signature whatever the consent manager signed, kept verbatim. Optional here because a
     *                  deployment with no ABDM credentials has nothing to keep, and a service that
     *                  demanded one would be unusable in exactly the configuration the README says
     *                  is the honest default.
     */
    public void grant(String signature) {
        this.status = ConsentStatus.GRANTED;
        this.grantedAt = Instant.now();
        this.signature = signature;
    }

    public void deny() {
        this.status = ConsentStatus.DENIED;
        this.deniedAt = Instant.now();
    }

    public void revoke(String reason) {
        this.status = ConsentStatus.REVOKED;
        this.revokedAt = Instant.now();
        this.revokedReason = reason;
    }

    /** Marks a lapsed consent as such, so a list query does not have to compute it per row. */
    public void expire() {
        this.status = ConsentStatus.EXPIRED;
    }

    public boolean hasLapsed(Instant now) {
        return expiresAt.isBefore(now);
    }

    /**
     * Whether this consent authorises disclosing information of a type, dated on a day.
     *
     * <p>Deliberately not called {@code isValid}: validity is four questions and a method with a
     * name that vague invites a caller to check one of them. Each half is asked separately by the
     * service so the refusal can say which one failed, and this is the whole-conjunction form for
     * the cases that only need a yes or no.
     */
    public boolean covers(HiType hiType, LocalDate recordDate, Instant now) {
        return status == ConsentStatus.GRANTED
                && !hasLapsed(now)
                && hiTypes.contains(hiType)
                && !recordDate.isBefore(coversFrom)
                && !recordDate.isAfter(coversTo);
    }

    public String getArtefactId() {
        return artefactId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientMrn() {
        return patientMrn;
    }

    public String getRequester() {
        return requester;
    }

    public String getRequesterId() {
        return requesterId;
    }

    public PurposeCode getPurposeCode() {
        return purposeCode;
    }

    public String getPurposeText() {
        return purposeText;
    }

    public ConsentStatus getStatus() {
        return status;
    }

    public LocalDate getCoversFrom() {
        return coversFrom;
    }

    public LocalDate getCoversTo() {
        return coversTo;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getDeniedAt() {
        return deniedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public String getSignature() {
        return signature;
    }

    /** A copy: a caller that mutated this set would change what a live consent covers. */
    public Set<HiType> getHiTypes() {
        return hiTypes.isEmpty() ? Set.of() : EnumSet.copyOf(hiTypes);
    }
}
