package com.hms.interop.web.dto;

import com.hms.interop.domain.InteropEnums.ConsentStatus;
import com.hms.interop.domain.InteropEnums.DisclosureKind;
import com.hms.interop.domain.InteropEnums.HiType;
import com.hms.interop.domain.InteropEnums.PurposeCode;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class InteropDtos {

    private InteropDtos() {
    }

    // ---- consent -------------------------------------------------------------

    /**
     * A consent asked for.
     *
     * @param coversFrom  the clinical period the consent is about, which is not the same thing as
     *                    how long the permission lasts — {@code expiresAt} is that. Conflating the
     *                    two is the classic mistake, and the two fields exist so a screen cannot.
     * @param expiresAt   when the permission itself lapses. Required: a consent with no expiry is
     *                    standing permission to read somebody's record forever.
     */
    public record RequestConsentRequest(
            @NotNull UUID patientId,
            @NotBlank @Size(max = 24) String patientMrn,
            @NotBlank @Size(max = 160) String requester,
            @Size(max = 120) String requesterId,
            @NotNull PurposeCode purposeCode,
            @Size(max = 255) String purposeText,
            @NotEmpty Set<HiType> hiTypes,
            @NotNull LocalDate coversFrom,
            @NotNull LocalDate coversTo,
            @NotNull @FutureOrPresent Instant expiresAt,
            /** The consent manager's id for this artefact, when there is one. */
            @Size(max = 64) String artefactId) {
    }

    /** Whatever the consent manager signed, if anything did. */
    public record GrantConsentRequest(@Size(max = 4000) String signature) {
    }

    public record RevokeConsentRequest(@NotBlank @Size(max = 255) String reason) {
    }

    /**
     * @param live true when this consent would authorise a disclosure right now — granted, not
     *             lapsed, not revoked. Computed rather than stored, so a screen cannot show a
     *             consent as usable because a housekeeping job has not run.
     */
    public record ConsentResponse(UUID id, String artefactId, UUID patientId, String patientMrn,
                                  String requester, String requesterId, PurposeCode purposeCode,
                                  String purposeText, ConsentStatus status, boolean live,
                                  List<HiType> hiTypes, LocalDate coversFrom, LocalDate coversTo,
                                  Instant expiresAt, Instant grantedAt, Instant deniedAt,
                                  Instant revokedAt, String revokedReason, boolean signed) {

        public ConsentResponse {
            hiTypes = hiTypes == null ? List.of() : List.copyOf(hiTypes);
        }
    }

    // ---- exchange ------------------------------------------------------------

    /**
     * A request to send one record to whoever a consent names.
     *
     * <p>The consent is named by its artefact id rather than derived from the patient, and that is
     * deliberate: a patient may have several consents with different requesters, periods and
     * information types, and picking one for the caller would be picking which permission to act
     * under on their behalf.
     */
    public record ShareRequest(
            @NotBlank @Size(max = 64) String artefactId,
            @NotNull HiType hiType,
            /** The encounter, laboratory order or prescription being sent. */
            @NotNull UUID recordId) {
    }

    /**
     * What a share did.
     *
     * @param transmitted whether the bundle actually left the building. False in the default
     *                    configuration, where the ABDM adapter logs what it would have sent — the
     *                    honest state for a deployment with no NHA credentials, reported rather
     *                    than dressed up as success.
     */
    public record ShareResponse(UUID disclosureId, String artefactId, HiType hiType,
                               int resourceCount, int byteCount, boolean transmitted,
                               String gateway, String message) {
    }

    public record DisclosureResponse(UUID id, UUID consentId, String artefactId, UUID patientId,
                                    String patientMrn, HiType hiType, DisclosureKind kind,
                                    String recipient, int resourceCount, int byteCount,
                                    String releasedBy, Instant releasedAt) {
    }

    /**
     * The patient's own accounting of disclosures: what left, to whom, when, and how much.
     *
     * <p>Deliberately without {@code releasedBy}, and that is a decision rather than an omission.
     * On the staff view that field is the point — an administrator asking who released a record
     * needs the name. On the patient's, naming an individual member of staff turns an accounting of
     * disclosures into a complaint aimed at a person, when what the patient is owed is an account
     * of the disclosure: that their consultation note went to this recipient on this date, under
     * this consent. The hospital released it, and the hospital answers for it.
     *
     * <p>Also without {@code patientId} and {@code patientMrn}: there is exactly one patient this
     * can be about, established from a signed claim, so echoing their own identifiers back tells
     * them nothing and adds two fields to every log and cache that need not carry them.
     */
    public record MyDisclosureResponse(UUID id, String artefactId, HiType hiType, DisclosureKind kind,
                                       String recipient, int resourceCount, int byteCount,
                                       Instant releasedAt) {
    }

    public record MessageResponse(String message) {
    }
}
