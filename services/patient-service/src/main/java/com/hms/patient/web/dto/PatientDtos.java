package com.hms.patient.web.dto;

import com.hms.patient.domain.AllergySeverity;
import com.hms.patient.domain.Sex;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PatientDtos {

    private PatientDtos() {
    }

    /** Registration payload. MRN is issued by the service, never supplied by the caller. */
    public record CreatePatientRequest(
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName,
            @NotNull @Past(message = "date of birth must be in the past") LocalDate dateOfBirth,
            @NotNull Sex sex,
            @Size(max = 8) String bloodGroup,
            @Pattern(regexp = "^$|^[+0-9 ()-]{6,32}$", message = "phone contains invalid characters") String phone,
            @Email String email,
            @Size(max = 160) String addressLine1,
            @Size(max = 160) String addressLine2,
            @Size(max = 80) String city,
            @Size(max = 80) String state,
            @Size(max = 16) String postalCode,
            @Size(max = 80) String country,
            @Size(max = 64) String nationalId,
            @Size(max = 120) String insuranceProvider,
            @Size(max = 64) String insurancePolicyNo,
            @Size(max = 160) String emergencyContactName,
            @Pattern(regexp = "^$|^[+0-9 ()-]{6,32}$") String emergencyContactPhone,
            @Size(max = 2000) String notes,
            /**
             * Set true to register despite a duplicate warning. Boxed deliberately: Jackson 3
             * refuses to map an absent JSON field onto a primitive, so an optional flag must be
             * nullable.
             */
            Boolean forceDuplicate) {

        /** True only when the caller explicitly asked to bypass duplicate detection. */
        public boolean isForced() {
            return Boolean.TRUE.equals(forceDuplicate);
        }
    }

    public record UpdatePatientRequest(
            @Size(max = 80) String firstName,
            @Size(max = 80) String lastName,
            @Past LocalDate dateOfBirth,
            Sex sex,
            @Size(max = 8) String bloodGroup,
            @Pattern(regexp = "^$|^[+0-9 ()-]{6,32}$") String phone,
            @Email String email,
            @Size(max = 160) String addressLine1,
            @Size(max = 160) String addressLine2,
            @Size(max = 80) String city,
            @Size(max = 80) String state,
            @Size(max = 16) String postalCode,
            @Size(max = 80) String country,
            @Size(max = 120) String insuranceProvider,
            @Size(max = 160) String emergencyContactName,
            @Pattern(regexp = "^$|^[+0-9 ()-]{6,32}$") String emergencyContactPhone,
            @Size(max = 2000) String notes,
            Boolean active,
            Boolean deceased) {
    }

    /**
     * Patient as returned to clients. National id and insurance policy number are intentionally
     * absent — they are stored encrypted and served only by the dedicated identifiers endpoint,
     * so they never leak into list responses, logs or browser caches.
     */
    public record PatientResponse(UUID id, String mrn, String firstName, String lastName, String fullName,
                                  LocalDate dateOfBirth, int age, Sex sex, String bloodGroup, String phone,
                                  String email, String addressLine1, String addressLine2, String city, String state,
                                  String postalCode, String country, String insuranceProvider,
                                  String emergencyContactName, String emergencyContactPhone, String notes,
                                  boolean active, boolean deceased, boolean hasCriticalAllergy,
                                  List<AllergyResponse> allergies, Instant createdAt, Instant updatedAt) {
    }

    /** Summary row for search results and pick-lists. */
    public record PatientSummary(UUID id, String mrn, String fullName, LocalDate dateOfBirth, int age, Sex sex,
                                 String phone, boolean active, boolean hasCriticalAllergy) {
    }

    /** The encrypted identifiers, released only to authorised roles and audited on every read. */
    public record PatientIdentifiers(UUID id, String mrn, String nationalId, String insurancePolicyNo) {
    }

    public record AddAllergyRequest(@NotBlank @Size(max = 120) String substance,
                                    @Size(max = 255) String reaction,
                                    @NotNull AllergySeverity severity) {
    }

    public record AllergyResponse(UUID id, String substance, String reaction, AllergySeverity severity,
                                  boolean critical, String recordedBy, Instant recordedAt) {
    }

    /** Returned with 409 when a registration looks like a duplicate and force was not set. */
    public record DuplicateWarning(String message, List<PatientSummary> candidates) {
    }

    public record CreateStaffRequest(
            @NotBlank @Size(max = 32) String employeeNo,
            @NotBlank @Size(max = 160) String fullName,
            @NotBlank @Size(max = 64) String designation,
            UUID userId,
            @Size(max = 16) String departmentCode,
            @Size(max = 120) String specialty,
            @Size(max = 64) String licenseNo,
            @Pattern(regexp = "^$|^[+0-9 ()-]{6,32}$") String phone,
            @Email String email) {
    }

    public record UpdateStaffRequest(@Size(max = 160) String fullName, @Size(max = 64) String designation,
                                     UUID userId, @Size(max = 16) String departmentCode,
                                     @Size(max = 120) String specialty, @Size(max = 64) String licenseNo,
                                     @Pattern(regexp = "^$|^[+0-9 ()-]{6,32}$") String phone,
                                     @Email String email, Boolean active) {
    }

    public record StaffResponse(UUID id, UUID userId, String employeeNo, String fullName, String designation,
                                String departmentCode, String departmentName, String specialty, String licenseNo,
                                String phone, String email, boolean active) {
    }

    public record DepartmentResponse(UUID id, String code, String name, String description, boolean active) {
    }

    public record CreateDepartmentRequest(@NotBlank @Size(max = 16) String code,
                                          @NotBlank @Size(max = 120) String name,
                                          @Size(max = 500) String description) {
    }

    public record MessageResponse(String message) {
    }
}
