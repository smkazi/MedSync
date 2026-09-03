package com.hms.scheduling.repo;

import com.hms.scheduling.domain.PatientCareGrant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientCareGrantRepository extends JpaRepository<PatientCareGrant, UUID> {

    /** Whether this user holds a live grant for this patient. */
    @Query("""
            select count(g) > 0 from PatientCareGrant g
             where g.patientId = :patientId
               and g.userId = :userId
               and g.expiresAt > :now
            """)
    boolean hasLiveGrant(@Param("patientId") UUID patientId, @Param("userId") UUID userId,
                         @Param("now") Instant now);

    List<PatientCareGrant> findByPatientIdOrderByGrantedAtDesc(UUID patientId);
}
