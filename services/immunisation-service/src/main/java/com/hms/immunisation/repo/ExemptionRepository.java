package com.hms.immunisation.repo;

import com.hms.immunisation.domain.ImmunisationExemption;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExemptionRepository extends JpaRepository<ImmunisationExemption, UUID> {

    List<ImmunisationExemption> findByPatientIdOrderByRecordedAtAsc(UUID patientId);

    List<ImmunisationExemption> findByPatientIdIn(Collection<UUID> patientIds);
}
