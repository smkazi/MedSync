package com.hms.scheduling.repo;

import com.hms.scheduling.domain.ClinicalNote;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicalNoteRepository extends JpaRepository<ClinicalNote, UUID> {

    List<ClinicalNote> findByEncounterIdOrderByRevisionAsc(UUID encounterId);

    Optional<ClinicalNote> findFirstByEncounterIdOrderByRevisionDesc(UUID encounterId);
}
