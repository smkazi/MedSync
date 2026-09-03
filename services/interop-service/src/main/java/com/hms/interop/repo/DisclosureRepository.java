package com.hms.interop.repo;

import com.hms.interop.domain.Disclosure;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisclosureRepository extends JpaRepository<Disclosure, UUID> {

    /** Everything ever released about one patient, newest first. What a patient asks to see. */
    List<Disclosure> findByPatientIdOrderByReleasedAtDesc(UUID patientId);

    List<Disclosure> findByConsentIdOrderByReleasedAtDesc(UUID consentId);
}
