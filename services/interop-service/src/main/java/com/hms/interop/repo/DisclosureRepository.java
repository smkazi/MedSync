package com.hms.interop.repo;

import com.hms.interop.domain.Disclosure;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisclosureRepository extends JpaRepository<Disclosure, UUID> {

    /** Everything ever released about one patient, newest first. What a patient asks to see. */
    List<Disclosure> findByPatientIdOrderByReleasedAtDesc(UUID patientId);

    /**
     * The same register bounded to a period, which is how anybody actually asks the question —
     * "what left in September", not "everything since the platform was installed".
     *
     * <p>Half-open: {@code >= from} and {@code < to}, following the precedent
     * {@code AppointmentService} set for every date range on this platform. A derived query rather
     * than a {@code @Query}, because {@code idx_disclosure_patient (patient_id, released_at DESC)}
     * already covers exactly this predicate and ordering.
     */
    List<Disclosure> findByPatientIdAndReleasedAtGreaterThanEqualAndReleasedAtLessThanOrderByReleasedAtDesc(
            UUID patientId, Instant from, Instant to);
}
