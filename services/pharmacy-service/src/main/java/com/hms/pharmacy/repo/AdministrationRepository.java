package com.hms.pharmacy.repo;

import com.hms.pharmacy.domain.Administration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdministrationRepository extends JpaRepository<Administration, UUID> {

    List<Administration> findByPrescriptionItemIdOrderByScheduledFor(UUID prescriptionItemId);

    List<Administration> findByPrescriptionItemIdInOrderByScheduledFor(Collection<UUID> itemIds);
}
