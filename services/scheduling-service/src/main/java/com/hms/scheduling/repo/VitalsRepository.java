package com.hms.scheduling.repo;

import com.hms.scheduling.domain.VitalsRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VitalsRepository extends JpaRepository<VitalsRecord, UUID> {

    List<VitalsRecord> findByEncounterIdOrderByRecordedAtDesc(UUID encounterId);
}
