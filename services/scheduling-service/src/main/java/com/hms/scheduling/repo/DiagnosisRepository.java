package com.hms.scheduling.repo;

import com.hms.scheduling.domain.Diagnosis;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisRepository extends JpaRepository<Diagnosis, UUID> {

    List<Diagnosis> findByEncounterIdOrderByCategoryAsc(UUID encounterId);

    boolean existsByEncounterIdAndIcd10Code(UUID encounterId, String icd10Code);
}
