package com.hms.patient.repo;

import com.hms.patient.domain.PatientAllergy;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientAllergyRepository extends JpaRepository<PatientAllergy, UUID> {

    List<PatientAllergy> findByPatientIdOrderBySeverityDesc(UUID patientId);
}
