package com.hms.pharmacy.repo;

import com.hms.pharmacy.domain.PrescriptionItem;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrescriptionItemRepository extends JpaRepository<PrescriptionItem, UUID> {
}
