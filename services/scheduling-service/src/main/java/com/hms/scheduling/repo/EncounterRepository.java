package com.hms.scheduling.repo;

import com.hms.scheduling.domain.Encounter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EncounterRepository extends JpaRepository<Encounter, UUID> {

    /** The chart view always renders the notes, so they are fetched with the encounter. */
    @EntityGraph(attributePaths = "notes")
    Optional<Encounter> findDetailById(UUID id);

    Optional<Encounter> findByAppointmentId(UUID appointmentId);

    List<Encounter> findByPatientIdOrderByStartedAtDesc(UUID patientId);
}
