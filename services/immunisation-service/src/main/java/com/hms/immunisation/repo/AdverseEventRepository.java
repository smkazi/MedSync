package com.hms.immunisation.repo;

import com.hms.immunisation.domain.AdverseEvent;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdverseEventRepository extends JpaRepository<AdverseEvent, UUID> {

    List<AdverseEvent> findByImmunisationIdOrderByOnsetOnAsc(UUID immunisationId);

    List<AdverseEvent> findByImmunisationIdInOrderByOnsetOnAsc(Collection<UUID> immunisationIds);
}
