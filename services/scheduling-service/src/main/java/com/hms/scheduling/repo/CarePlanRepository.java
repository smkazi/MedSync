package com.hms.scheduling.repo;

import com.hms.scheduling.domain.CarePlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarePlanRepository extends JpaRepository<CarePlan, UUID> {

    Optional<CarePlan> findByEncounterId(UUID encounterId);

    List<CarePlan> findByPatientIdOrderByCreatedAtDesc(UUID patientId);
}
