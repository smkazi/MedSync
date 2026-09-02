package com.hms.pharmacy.web.dto;

import com.hms.pharmacy.domain.PharmacyEnums;
import com.hms.pharmacy.service.AllergyChecker;
import com.hms.pharmacy.service.InteractionChecker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PharmacyDtos {

    private PharmacyDtos() {
    }

    // ---- formulary -----------------------------------------------------------

    public record FormularyResponse(UUID id, String code, String name, String form, String strength,
                                    String unit, String label, boolean controlled, boolean active,
                                    List<String> ingredients, int unitsInStock,
                                    LocalDate earliestExpiry) {

        public FormularyResponse {
            ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        }
    }

    /**
     * A new formulary entry.
     *
     * @param ingredients what it contains, and the field that makes every check in this service
     *                    work. Required and non-empty: an entry with no ingredients passes every
     *                    allergy and interaction check by having nothing to match, which is worse
     *                    than not being in the formulary at all.
     */
    public record CreateFormularyRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 32) String form,
            @NotBlank @Size(max = 48) String strength,
            @NotBlank @Size(max = 24) String unit,
            Boolean controlled,
            @NotEmpty List<@NotBlank @Size(max = 64) String> ingredients) {
    }

    public record UpdateFormularyRequest(@Size(max = 160) String name, Boolean active) {
    }

    // ---- interactions --------------------------------------------------------

    public record InteractionResponse(UUID id, String ingredientA, String ingredientB,
                                      PharmacyEnums.InteractionSeverity severity, String effect,
                                      String management, String source) {
    }

    /**
     * A pairing, recorded or corrected.
     *
     * @param management what to do instead. Required, because a warning with no action attached is
     *                   one clinicians learn to dismiss — "these interact" gets clicked through and
     *                   "monitor INR weekly for the first month" does not.
     */
    public record UpsertInteractionRequest(
            @NotBlank @Size(max = 64) String ingredientA,
            @NotBlank @Size(max = 64) String ingredientB,
            @NotNull PharmacyEnums.InteractionSeverity severity,
            @NotBlank @Size(max = 255) String effect,
            @NotBlank @Size(max = 255) String management,
            @Size(max = 120) String source) {
    }

    // ---- prescribing ---------------------------------------------------------

    public record PrescribeItemRequest(
            @NotBlank @Size(max = 32) String drugCode,
            @NotBlank @Size(max = 48) String dose,
            @NotBlank @Size(max = 48) String frequency,
            @NotNull @Min(1) Integer durationDays,
            @NotNull @Min(1) Integer quantity,
            @Size(max = 500) String instructions) {
    }

    /**
     * A whole prescription, checked as a whole.
     *
     * <p>The items are one request rather than several because an interaction is a property of a
     * set: three medicines posted separately would each be checked against nothing.
     *
     * @param overrideReason why a warning was accepted. Required only when the check comes back
     *                       overridable, which is a rule the service applies — a form cannot know
     *                       in advance whether it will be needed.
     */
    public record PrescribeRequest(
            UUID encounterId,
            @NotNull UUID patientId,
            @NotBlank @Size(max = 24) String patientMrn,
            @NotEmpty List<@Valid PrescribeItemRequest> items,
            @Size(max = 500) String overrideReason) {
    }

    public record PrescriptionItemResponse(UUID id, String drugCode, String drugName, String dose,
                                          String frequency, int durationDays, int quantity,
                                          String instructions, int quantityDispensed,
                                          int outstanding,
                                          List<AdministrationResponse> administrations) {

        public PrescriptionItemResponse {
            administrations = administrations == null ? List.of() : List.copyOf(administrations);
        }
    }

    public record PrescriptionResponse(UUID id, UUID encounterId, UUID patientId, String patientMrn,
                                       UUID prescriberId, String prescriberName,
                                       PharmacyEnums.PrescriptionStatus status,
                                       String overrideReason, Instant issuedAt, Instant cancelledAt,
                                       List<PrescriptionItemResponse> items) {

        public PrescriptionResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * What the checks found, whether or not they blocked anything.
     *
     * <p>Its own endpoint as well as part of the refusal, so a prescribing screen can show the
     * warnings before somebody presses the button rather than only after being refused.
     */
    public record SafetyCheckResponse(PharmacyEnums.CheckOutcome outcome,
                                      List<AllergyChecker.Finding> allergies,
                                      List<InteractionChecker.Finding> interactions,
                                      String message) {

        public SafetyCheckResponse {
            allergies = allergies == null ? List.of() : List.copyOf(allergies);
            interactions = interactions == null ? List.of() : List.copyOf(interactions);
        }
    }

    public record CheckRequest(@NotNull UUID patientId,
                               @NotEmpty List<@NotBlank @Size(max = 32) String> drugCodes) {
    }

    // ---- stock and dispensing ------------------------------------------------

    public record StockBatchResponse(UUID id, String drugCode, String drugName, String batchNo,
                                     LocalDate expiresOn, int quantityOnHand, LocalDate receivedOn,
                                     boolean expired, long daysToExpiry) {
    }

    public record ReceiveStockRequest(
            @NotBlank @Size(max = 32) String drugCode,
            @NotBlank @Size(max = 48) String batchNo,
            @NotNull LocalDate expiresOn,
            @NotNull @Min(1) Integer quantity) {
    }

    /**
     * Hand over some of one prescribed item.
     *
     * @param batchId which batch to take it from. Optional, and normally omitted: the service picks
     *                first-expiry-first, which is what a pharmacy should do and what a picker
     *                choosing by hand at the end of a shift will not always do. Naming a batch is
     *                for the case where the shelf disagrees with the system.
     */
    public record DispenseRequest(@NotNull UUID prescriptionItemId,
                                  @NotNull @Min(1) Integer quantity,
                                  UUID batchId) {
    }

    public record DispenseResponse(UUID id, UUID prescriptionItemId, String drugName, String batchNo,
                                   LocalDate expiresOn, int quantity, String dispensedBy,
                                   Instant dispensedAt, int outstanding) {
    }

    // ---- administration ------------------------------------------------------

    /**
     * A dose given at the bedside, against two scans.
     *
     * @param patientScan the wristband. Checked against the prescription's MRN, and the whole point
     *                    of the loop: a right medicine given to the wrong patient is the error this
     *                    field exists to catch.
     * @param drugScan    the medicine's own label, checked against the item's drug code.
     */
    public record AdministerRequest(@NotNull UUID prescriptionItemId,
                                    @NotNull Instant scheduledFor,
                                    @NotBlank @Size(max = 64) String patientScan,
                                    @NotBlank @Size(max = 64) String drugScan) {
    }

    /** A dose that was not given, and why. */
    public record NotGivenRequest(@NotNull UUID prescriptionItemId,
                                  @NotNull Instant scheduledFor,
                                  @NotNull PharmacyEnums.AdministrationStatus status,
                                  @NotBlank @Size(max = 255) String reason) {
    }

    public record AdministrationResponse(UUID id, UUID prescriptionItemId, Instant scheduledFor,
                                         Instant administeredAt, String administeredBy,
                                         PharmacyEnums.AdministrationStatus status,
                                         String refusalReason) {
    }

    public record MessageResponse(String message) {
    }
}
