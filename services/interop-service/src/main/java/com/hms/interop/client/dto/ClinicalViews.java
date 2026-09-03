package com.hms.interop.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What this service reads of another service's responses, and nothing more.
 *
 * <p>Local views rather than shared DTOs, and the reason is the same one that keeps each service's
 * schema to itself: a shared response type would make patient-service's DTO a published contract
 * that cannot change without a coordinated release, and this service needs six fields out of
 * thirty. {@code AllergyClient} in pharmacy-service established the pattern with one record.
 *
 * <p>Every view ignores unknown properties, deliberately and explicitly. Jackson 3 does not fail
 * on them by default, so the annotation is documentation as much as configuration: a clinical
 * service adding a field must not break the export, and a field this service does not name is a
 * field it does not disclose — which is the direction an interop module's failure mode should
 * point in.
 *
 * <p>The annotation comes from {@code com.fasterxml.jackson.annotation}, which is the one package
 * Jackson 3 did not rename — the databind and core packages are {@code tools.jackson.*} everywhere
 * else in this repository, and the mismatch is Jackson's rather than ours.
 */
public final class ClinicalViews {

    private ClinicalViews() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PatientView(UUID id, String mrn, String firstName, String lastName,
                              LocalDate dateOfBirth, String sex, String phone, String email,
                              String city, String state, String country, boolean active,
                              boolean deceased) {

        public String fullName() {
            return "%s %s".formatted(firstName == null ? "" : firstName,
                    lastName == null ? "" : lastName).trim();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NoteView(UUID id, int revision, String subjective, String objective,
                           String assessment, String plan, String author, boolean signed,
                           Instant signedAt, String signedBy) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VitalsView(UUID id, Instant recordedAt, String recordedBy, Integer heartRate,
                             Integer systolicBp, Integer diastolicBp, Integer respiratoryRate,
                             BigDecimal temperatureC, Integer oxygenSaturation, BigDecimal weightKg,
                             BigDecimal heightCm) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DiagnosisView(UUID id, String icd10Code, String description, String category) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EncounterView(UUID id, UUID patientId, String patientMrn, UUID clinicianId,
                                String departmentCode, String encounterType, Instant startedAt,
                                Instant endedAt, String status, List<NoteView> notes,
                                List<VitalsView> vitals, List<DiagnosisView> diagnoses) {

        public EncounterView {
            notes = notes == null ? List.of() : List.copyOf(notes);
            vitals = vitals == null ? List.of() : List.copyOf(vitals);
            diagnoses = diagnoses == null ? List.of() : List.copyOf(diagnoses);
        }

        /**
         * The signed note, if there is one.
         *
         * <p>Only signed notes are exported, and that is a clinical rule rather than a filter for
         * tidiness: an unsigned note is a draft nobody has stood behind, and sending one to another
         * hospital as part of a record hands them an opinion whose author has not finished forming
         * it. The latest revision wins, because an amendment supersedes what it amends.
         */
        public NoteView signedNote() {
            return notes.stream()
                    .filter(NoteView::signed)
                    .max(java.util.Comparator.comparingInt(NoteView::revision))
                    .orElse(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabResultView(UUID id, String parameter, String displayName, String value,
                                String unit, BigDecimal normalLow, BigDecimal normalHigh,
                                String referenceRange, String flag, boolean abnormal, String status,
                                String verifiedBy, Instant verifiedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabOrderItemView(UUID id, String testCode, String testName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabOrderView(UUID id, UUID patientId, String patientMrn, String orderedBy,
                               UUID encounterId, String priority, String status,
                               Instant orderedAt, List<LabOrderItemView> items,
                               List<LabResultView> results) {

        public LabOrderView {
            items = items == null ? List.of() : List.copyOf(items);
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrescriptionItemView(UUID id, String drugCode, String drugName, String dose,
                                       String frequency, int durationDays, int quantity,
                                       String instructions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrescriptionView(UUID id, UUID encounterId, UUID patientId, String patientMrn,
                                   String prescriberName, String status, Instant issuedAt,
                                   List<PrescriptionItemView> items) {

        public PrescriptionView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
