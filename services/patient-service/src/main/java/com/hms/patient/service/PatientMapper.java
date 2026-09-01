package com.hms.patient.service;

import com.hms.patient.domain.Department;
import com.hms.patient.domain.Patient;
import com.hms.patient.domain.PatientAllergy;
import com.hms.patient.domain.Staff;
import com.hms.patient.web.dto.PatientDtos;

/** Entity to DTO translation. Encrypted identifiers are deliberately omitted from the full view. */
public final class PatientMapper {

    private PatientMapper() {
    }

    public static PatientDtos.PatientResponse toResponse(Patient patient) {
        return new PatientDtos.PatientResponse(
                patient.getId(), patient.getMrn(), patient.getFirstName(), patient.getLastName(),
                patient.fullName(), patient.getDateOfBirth(), patient.age(), patient.getSex(),
                patient.getBloodGroup(), patient.getPhone(), patient.getEmail(), patient.getAddressLine1(),
                patient.getAddressLine2(), patient.getCity(), patient.getState(), patient.getPostalCode(),
                patient.getCountry(), patient.getInsuranceProvider(), patient.getEmergencyContactName(),
                patient.getEmergencyContactPhone(), patient.getNotes(), patient.isActive(), patient.isDeceased(),
                patient.hasCriticalAllergy(),
                patient.getAllergies().stream().map(PatientMapper::toResponse).toList(),
                patient.getCreatedAt(), patient.getUpdatedAt());
    }

    /**
     * Summary row. The critical-allergy flag is passed in rather than read off the entity: it is
     * resolved for a whole page in one query, so a list never triggers one query per row.
     */
    public static PatientDtos.PatientSummary toSummary(Patient patient, boolean hasCriticalAllergy) {
        return new PatientDtos.PatientSummary(patient.getId(), patient.getMrn(), patient.fullName(),
                patient.getDateOfBirth(), patient.age(), patient.getSex(), patient.getPhone(), patient.isActive(),
                hasCriticalAllergy);
    }

    public static PatientDtos.AllergyResponse toResponse(PatientAllergy allergy) {
        return new PatientDtos.AllergyResponse(allergy.getId(), allergy.getSubstance(), allergy.getReaction(),
                allergy.getSeverity(), allergy.isCritical(), allergy.getRecordedBy(), allergy.getCreatedAt());
    }

    public static PatientDtos.StaffResponse toResponse(Staff staff) {
        Department department = staff.getDepartment();
        return new PatientDtos.StaffResponse(staff.getId(), staff.getUserId(), staff.getEmployeeNo(),
                staff.getFullName(), staff.getDesignation(),
                department == null ? null : department.getCode(),
                department == null ? null : department.getName(),
                staff.getSpecialty(), staff.getLicenseNo(), staff.getPhone(), staff.getEmail(), staff.isActive());
    }

    public static PatientDtos.DepartmentResponse toResponse(Department department) {
        return new PatientDtos.DepartmentResponse(department.getId(), department.getCode(), department.getName(),
                department.getDescription(), department.isActive());
    }
}
