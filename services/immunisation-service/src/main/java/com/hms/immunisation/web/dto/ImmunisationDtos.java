package com.hms.immunisation.web.dto;

import com.hms.immunisation.domain.ImmunisationEnums.DueStatus;
import com.hms.immunisation.domain.ImmunisationEnums.ExemptionKind;
import com.hms.immunisation.domain.ImmunisationEnums.ImmunisationSource;
import com.hms.immunisation.domain.ImmunisationEnums.Outcome;
import com.hms.immunisation.domain.ImmunisationEnums.Route;
import com.hms.immunisation.domain.ImmunisationEnums.Seriousness;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The register's API shapes. */
public final class ImmunisationDtos {

    private ImmunisationDtos() {
    }

    // ---- the catalogue -------------------------------------------------------

    public record AntigenResponse(String code, String name, String protectsAgainst, boolean active) {
    }

    public record ProductResponse(String code, String name, String manufacturer, Route route,
                                  int dosesPerVial, boolean active, List<String> antigenCodes) {

        public ProductResponse {
            antigenCodes = List.copyOf(antigenCodes);
        }
    }

    public record CreateAntigenRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 160) String protectsAgainst) {
    }

    /**
     * A new product, and what it contains.
     *
     * <p>{@code antigenCodes} is required and non-empty: a vaccine that protects against nothing is
     * not a vaccine, and a product with an empty contents list would be silently invisible to every
     * coverage question ever asked of it.
     */
    public record CreateProductRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 160) String manufacturer,
            @NotNull Route route,
            @Min(1) int dosesPerVial,
            @NotNull Set<@NotBlank @Size(max = 32) String> antigenCodes) {

        @AssertTrue(message = "A product must contain at least one antigen")
        public boolean isContentsListed() {
            return antigenCodes != null && !antigenCodes.isEmpty();
        }
    }

    // ---- stock ---------------------------------------------------------------

    public record LotResponse(UUID id, String productCode, String productName, String lotNo,
                              LocalDate expiresOn, int quantityOnHand, LocalDate receivedOn,
                              Short vvmStage, String withdrawnReason, boolean usable) {
    }

    public record ReceiveLotRequest(
            @NotBlank @Size(max = 32) String productCode,
            @NotBlank @Size(max = 48) String lotNo,
            @NotNull LocalDate expiresOn,
            @Min(1) int quantity,
            // The vial monitor stage as read at receipt, if anybody read one. Optional, because a
            // deployment without VVM-labelled vaccine has nothing to read -- and a required field
            // nobody can answer is a field somebody types 1 into.
            Short vvmStage) {
    }

    public record WithdrawLotRequest(@NotBlank @Size(max = 255) String reason) {
    }

    // ---- doses ---------------------------------------------------------------

    /**
     * A dose given here.
     *
     * <p>No route: it comes from the product, because the route is a property of the vaccine. No
     * source either — this endpoint <em>is</em> the source, and a body that could name a different
     * one would let a caller record a historical dose through the path that demands a lot.
     */
    public record RecordDoseRequest(
            @NotNull UUID patientId,
            @NotBlank @Size(max = 24) String patientMrn,
            UUID encounterId,
            @NotBlank @Size(max = 32) String productCode,
            // The lot it came out of. Named rather than picked from a list: the nurse is holding
            // the vial, and the label on it is the evidence.
            @NotBlank @Size(max = 48) String lotNo,
            @NotNull LocalDate givenOn,
            @NotBlank @Size(max = 32) String site) {
    }

    public record DoseResponse(UUID id, UUID patientId, String patientMrn, UUID encounterId,
                               String productCode, String productName, List<String> antigenCodes,
                               String lotNo, ImmunisationSource source, LocalDate givenOn,
                               boolean givenOnEstimated, Route route, String site, String givenBy,
                               String evidence, Instant recordedAt, String recordedBy,
                               List<AdverseEventResponse> adverseEvents) {

        public DoseResponse {
            antigenCodes = List.copyOf(antigenCodes);
            adverseEvents = List.copyOf(adverseEvents);
        }
    }

    // ---- adverse events ------------------------------------------------------

    public record ReportAefiRequest(
            @NotNull LocalDate onsetOn,
            @NotBlank @Size(min = 8, max = 1000) String description,
            @NotNull Seriousness seriousness,
            @NotNull Outcome outcome) {
    }

    public record AdverseEventResponse(UUID id, UUID immunisationId, LocalDate onsetOn,
                                       String description, Seriousness seriousness, Outcome outcome,
                                       boolean reportable, String reportedBy, Instant reportedAt) {
    }

    // ---- exemptions ----------------------------------------------------------

    /**
     * Recording why a child will not be vaccinated.
     *
     * <p>{@code antigenCode} null means every antigen. The reason has a twenty-character floor, the
     * same one break-glass sets and for the same reason: this takes a child out of a coverage
     * measure's denominator, and "medical" is what a free-text box collects when it does not insist
     * on a sentence.
     */
    public record RecordExemptionRequest(
            @NotNull UUID patientId,
            @NotBlank @Size(max = 24) String patientMrn,
            @Size(max = 32) String antigenCode,
            @NotNull ExemptionKind kind,
            @NotBlank @Size(min = 20, max = 500) String reason,
            LocalDate expiresOn) {
    }

    public record ExemptionResponse(UUID id, UUID patientId, String antigenCode, ExemptionKind kind,
                                    String reason, LocalDate expiresOn, boolean live,
                                    String recordedBy, Instant recordedAt) {
    }

    // ---- the schedule --------------------------------------------------------

    /**
     * A published schedule and its doses.
     *
     * <p>Readable by anybody signed in, like the catalogue: a national immunisation schedule is a
     * public health document with no patient anywhere in it, and a recording screen that could not
     * read it could not tell a nurse what the child in front of them is due.
     */
    public record ScheduleResponse(String code, String name, int appliesFromAgeDays,
                                   int appliesToAgeDays, String source, boolean active,
                                   List<ScheduleDoseResponse> doses) {

        public ScheduleResponse {
            doses = List.copyOf(doses);
        }
    }

    /** One expected dose. Every number is days from date of birth; see the migration for why. */
    public record ScheduleDoseResponse(String antigenCode, int doseNumber, String label,
                                       int minAgeDays, int dueAgeDays, Integer minIntervalDays,
                                       int graceDays, Integer maxAgeDays) {
    }

    // ---- the due list --------------------------------------------------------

    /**
     * What a birth cohort is due, as at a date.
     *
     * <p>{@code asAt} is on the response rather than implied, because every status in it is a
     * statement about one day: the same cohort read tomorrow gives different answers, and a printed
     * calling list with no date on it is a list nobody can check.
     *
     * @param truncated true when patient-service capped the cohort. Carried rather than dropped —
     *                  the children past the cap are precisely the ones nobody telephones
     */
    public record DueListResponse(String scheduleCode, String scheduleName, LocalDate asAt,
                                  LocalDate bornFrom, LocalDate bornTo, int cohortSize, long total,
                                  boolean truncated, String note, List<PatientDueResponse> children) {

        public DueListResponse {
            children = List.copyOf(children);
        }
    }

    /** One child's position against the schedule. */
    public record PatientDueResponse(UUID patientId, String mrn, String fullName,
                                     LocalDate dateOfBirth, int ageDays, boolean inSchedule,
                                     String note, List<DueResponse> due,
                                     List<UncountedDoseResponse> uncounted) {

        public PatientDueResponse {
            due = List.copyOf(due);
            uncounted = List.copyOf(uncounted);
        }
    }

    /**
     * One antigen's next dose.
     *
     * <p>{@code because} is the sentence a clinician can check the row against, which is the rule
     * {@code AllergyChecker} states as "matched on AMOXICILLIN is checkable and allergy detected is
     * not". {@code refusalRecorded} is present rather than folded into the status: see
     * {@code DueStatus} for why a refusal does not suppress a row.
     */
    public record DueResponse(String antigenCode, int doseNumber, String label, DueStatus status,
                              LocalDate earliestOn, LocalDate dueOn, LocalDate overdueFrom,
                              LocalDate windowClosesOn, int dosesCounted,
                              boolean basedOnEstimatedDate, boolean refusalRecorded,
                              String because) {
    }

    /** A recorded dose that does not advance a series, and the rule that says so. */
    public record UncountedDoseResponse(UUID doseId, String antigenCode, String productCode,
                                        LocalDate givenOn, int doseNumberAttempted, String because) {
    }

    // ---- quality measures ----------------------------------------------------

    /**
     * What a measure asks, in the specification's own words.
     *
     * <p>The three population sentences are transcribed rather than rendered from the parameters
     * beside them, deliberately: a sentence generated from the columns would always agree with the
     * code and would therefore never reveal a disagreement between the code and the specification.
     */
    public record MeasureResponse(String code, String name, String kind, int byAgeDays,
                                  String steward, String specificationVersion,
                                  String initialPopulation, String denominator,
                                  String denominatorExclusion, String numerator,
                                  boolean countsEstimatedDates, boolean active,
                                  List<MeasureAntigenResponse> antigens) {

        public MeasureResponse {
            antigens = List.copyOf(antigens);
        }
    }

    public record MeasureAntigenResponse(String antigenCode, int dosesRequired) {
    }

    /**
     * One period's answer.
     *
     * <p>Carries no patient identifier of any kind, and the calculator behind it never selects one
     * into this shape — which is a better guarantee than a mapper that leaves fields out.
     *
     * @param rate       percent to two places, or <strong>null</strong> when the denominator is
     *                   zero. "No children reached their second birthday in this district last
     *                   month" is not "zero per cent of them were vaccinated", and rendering it as
     *                   0% would put a false failure into a return somebody signs
     * @param bornFrom   the birth range the period implies, echoed so the arithmetic can be checked
     * @param computedAt when this answer was produced. Present because it is <em>not</em> cached: a
     *                   dose entered from a card this morning correctly changes last quarter's rate,
     *                   so two reads a week apart can legitimately differ and the stamp is how a
     *                   reader tells that from an error
     */
    public record MeasureRateResponse(String code, String name, String kind, String steward,
                                      String specificationVersion, String scheduleCode,
                                      LocalDate periodFrom, LocalDate periodTo, LocalDate bornFrom,
                                      LocalDate bornTo, int initialPopulation, int denominator,
                                      int numerator, java.math.BigDecimal rate, boolean truncated,
                                      String note, Instant computedAt) {
    }

    // ---- the patient's register ---------------------------------------------

    /** Everything this service knows about one patient, in one read. */
    public record RegisterResponse(UUID patientId, String patientMrn, List<DoseResponse> doses,
                                   List<ExemptionResponse> exemptions) {

        public RegisterResponse {
            doses = List.copyOf(doses);
            exemptions = List.copyOf(exemptions);
        }
    }
}
