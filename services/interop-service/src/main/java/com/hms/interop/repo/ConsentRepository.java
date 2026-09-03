package com.hms.interop.repo;

import com.hms.interop.domain.ConsentArtefact;
import com.hms.interop.domain.InteropEnums.ConsentStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsentRepository extends JpaRepository<ConsentArtefact, UUID> {

    Optional<ConsentArtefact> findByArtefactId(String artefactId);

    List<ConsentArtefact> findByPatientIdOrderByCreatedAtDesc(UUID patientId);

    List<ConsentArtefact> findByStatusInOrderByCreatedAtDesc(Collection<ConsentStatus> statuses);

    /**
     * Marks lapsed grants as expired.
     *
     * <p>Housekeeping, not a control: the authorisation check compares {@code expiresAt} against
     * the clock on every call, so a row this has not caught up with is still refused. That ordering
     * matters — a platform whose consent enforcement depended on a scheduled job having run would
     * be a platform where a missed job is a disclosure.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ConsentArtefact c
               set c.status = 'EXPIRED'
             where c.status = 'GRANTED'
               and c.expiresAt < :now
            """)
    int markLapsed(@Param("now") Instant now);
}
