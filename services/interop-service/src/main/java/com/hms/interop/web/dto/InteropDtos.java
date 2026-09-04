package com.hms.interop.web.dto;

import com.hms.interop.domain.InteropEnums.ConsentStatus;
import com.hms.interop.domain.InteropEnums.DisclosureKind;
import com.hms.interop.domain.InteropEnums.HiType;
import com.hms.interop.domain.InteropEnums.PurposeCode;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
     * Recording that a notifiable-disease line list named these patients.
     *
     * <p>The only disclosure kind another service may register, and the request has no {@code kind}
     * field at all: an endpoint that took one would be an endpoint through which any service could
     * write any disclosure it liked, including a consented share with no consent behind it. What
     * kind of release this is, is a property of the endpoint.
     *
     * <p>No {@code releasedBy} either. It comes from the caller's own forwarded token, because a
     * body field naming who released a record is a body field somebody eventually fills in with a
     * name that is not theirs — and this register is the thing an investigation reads.
     *
     * <p>{@code subjects} is one entry per patient the list named. One row per person rather than a
     * single run-level row, because {@code disclosures.patient_id} is NOT NULL and
     * {@code idx_disclosure_patient} is what answers a patient asking who has seen their record: a
     * run-level row would need a fabricated patient id and would be invisible to every patient on
     * the list.
     */
    public record RecordPublicHealthDisclosureRequest(
            @NotBlank @Size(max = 160) String recipient,
            @NotEmpty List<@Valid DisclosedSubject> subjects) {
    }

    /**
     * What was written.
     *
     * <p>Ids rather than the rows themselves: the caller is scheduling-service, which is about to
     * produce the file and has no reason to read back the register it just wrote into.
     */
    public record PublicHealthDisclosureResponse(List<UUID> disclosureIds, String recipient,
                                                 int patients) {

        public PublicHealthDisclosureResponse {
            disclosureIds = List.copyOf(disclosureIds);
        }
    }

    /** One patient a line list named, and how many of their rows it carried. */
    public record DisclosedSubject(
            @NotNull UUID patientId,
            @NotBlank @Size(max = 24) String patientMrn,
            @Min(1) int rowCount) {
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

    // ---- HL7 v2 -------------------------------------------------------------

    /**
     * A message handed to the platform over HTTP rather than a socket.
     *
     * <p>The message is a string and not a structure, deliberately: this endpoint exists to take
     * exactly what a sender would have put on the wire, so that a system without MLLP — a test
     * harness, a middleware that speaks HTTP, an engineer with curl — exercises the same parser and
     * produces the same acknowledgement.
     */
    public record Hl7InboundRequest(@NotBlank String message) {
    }

    /**
     * One message that crossed the boundary.
     *
     * @param raw   what was on the wire, kept because the messages worth asking about are the ones
     *              that did not parse
     * @param error null when nothing went wrong, and the first field an interface engineer reads
     */
    public record Hl7ExchangeResponse(UUID id, String direction, String messageType,
                                      String controlId, String sendingApplication,
                                      String sendingFacility, String receivingApplication,
                                      String receivingFacility, Instant messageAt, String ackCode,
                                      String ackText, String error, String transport, String peer,
                                      Instant receivedAt, String raw, String ackRaw) {
    }

    /** The patient fields an outbound message carries. */
    public record Hl7PatientView(String mrn, String familyName, String givenName,
                                 String dateOfBirth, String sex, String phone) {
    }

    /** One released order, and the results on it. */
    public record Hl7OrderView(String placerOrderNumber, String accessionNumber, String panelCode,
                               String panelName, Instant collectedAt, Instant verifiedAt,
                               List<Hl7ResultView> results) {

        public Hl7OrderView {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    public record Hl7ResultView(String code, String name, String value, String units,
                                String referenceRange, String abnormalFlag) {
    }

    /**
     * Sends a message somewhere.
     *
     * <p>The destination is in the request rather than configured, because a hospital sends to more
     * than one place — a referring practice, a public-health registry, a partner laboratory — and a
     * single configured endpoint would make the second one a redeployment.
     */
    public record Hl7SendRequest(@NotBlank String host,
                                 @Min(1) @Max(65535) int port,
                                 @NotBlank String receivingApplication,
                                 @NotBlank String receivingFacility,
                                 @NotBlank String messageType,
                                 @Valid @NotNull Hl7PatientView patient,
                                 @Valid Hl7OrderView order) {
    }

    public record MessageResponse(String message) {
    }
}
