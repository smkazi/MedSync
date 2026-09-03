package com.hms.imaging.web.dto;

import com.hms.imaging.domain.ImagingEnums;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The radiology API's shapes. */
public final class ImagingDtos {

    private ImagingDtos() {
    }

    /**
     * Ordering an examination.
     *
     * <p>{@code clinicalQuestion} is {@code @NotBlank} and has a floor, and that is a clinical
     * decision rather than a validation habit: a radiologist reporting a film with no question is
     * guessing at what they were asked, and "?" is what a free-text box collects when it does not
     * insist. Twenty characters is roughly a sentence.
     */
    public record CreateOrderRequest(
            @NotNull UUID patientId,
            @NotBlank @Size(max = 24) String patientMrn,
            UUID encounterId,
            @NotBlank @Size(max = 32) String procedureCode,
            @NotBlank @Size(min = 20, max = 1000) String clinicalQuestion,
            ImagingEnums.Priority priority,
            Instant scheduledFor) {

        public ImagingEnums.Priority priorityOrDefault() {
            return priority == null ? ImagingEnums.Priority.ROUTINE : priority;
        }
    }

    public record ScheduleRequest(@NotNull Instant scheduledFor) {
    }

    public record CancelRequest(@NotBlank @Size(max = 255) String reason) {
    }

    /** Writing or revising a draft. */
    public record ReportRequest(
            @NotBlank @Size(max = 20000) String findings,
            @NotBlank @Size(max = 20000) String impression) {
    }

    /**
     * Amending a signed report.
     *
     * <p>The reason is required and has the same floor as a break-glass reason, for the same
     * reason: an amendment to a report somebody may already have treated from is an act that has to
     * explain itself, and "typo" is what a box collects when it does not ask for a sentence.
     */
    public record AmendRequest(
            @NotBlank @Size(max = 20000) String findings,
            @NotBlank @Size(max = 20000) String impression,
            @NotBlank @Size(min = 20, max = 500) String reason) {
    }

    public record OrderResponse(
            UUID id,
            UUID patientId,
            String patientMrn,
            UUID encounterId,
            String modality,
            String bodyPart,
            String procedureCode,
            String procedureName,
            String clinicalQuestion,
            boolean contrast,
            ImagingEnums.Priority priority,
            ImagingEnums.OrderStatus status,
            String orderedBy,
            Instant orderedAt,
            String accessionNo,
            Instant scheduledFor,
            String cancelledReason,
            List<StudyResponse> studies) {
    }

    /** The worklist row a modality and a radiographer read. Narrower than the order on purpose. */
    public record WorklistEntry(
            UUID id,
            String accessionNo,
            String patientMrn,
            String patientSex,
            LocalDate patientBirthDate,
            String modality,
            String procedureCode,
            String procedureName,
            boolean contrast,
            ImagingEnums.Priority priority,
            ImagingEnums.OrderStatus status,
            Instant scheduledFor) {
    }

    public record StudyResponse(
            UUID id,
            String studyInstanceUid,
            UUID orderId,
            String accessionNo,
            String patientMrn,
            String modality,
            String studyDescription,
            LocalDate studyDate,
            String institution,
            String referringPhysician,
            Instant receivedAt,
            List<SeriesResponse> series,
            ReportResponse report) {
    }

    public record SeriesResponse(
            UUID id,
            String seriesInstanceUid,
            Integer seriesNumber,
            String modality,
            String seriesDescription,
            String bodyPart,
            long instanceCount,
            /**
             * Whether the pixels were stored anywhere.
             *
             * <p>Reported rather than left to be inferred from a null URI, because "no archive is
             * configured" and "this series arrived empty" are different facts and a screen that
             * cannot tell them apart will describe one as the other.
             */
            boolean stored) {
    }

    public record ReportResponse(
            UUID id,
            UUID studyId,
            String findings,
            String impression,
            ImagingEnums.ReportStatus status,
            String reportedBy,
            Instant reportedAt,
            String signedBy,
            Instant signedAt,
            String amendedFrom,
            String amendedReason) {
    }

    public record ProcedureResponse(
            String code,
            String name,
            String modality,
            String bodyPart,
            int minutes,
            boolean contrast) {
    }

    /**
     * What an upload did.
     *
     * <p>{@code matched} is the field that matters. A study whose accession number named no order
     * is registered anyway and says so here, because the alternative — refusing the upload — loses
     * a study that exists on the scanner's disk either way, and guessing at a patient is worse than
     * both.
     */
    public record IngestResponse(
            UUID studyId,
            String studyInstanceUid,
            String accessionNo,
            boolean matched,
            UUID orderId,
            boolean stored,
            String storageUri,
            String message) {
    }
}
