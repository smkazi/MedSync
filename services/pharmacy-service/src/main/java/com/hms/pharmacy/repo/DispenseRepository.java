package com.hms.pharmacy.repo;

import com.hms.pharmacy.domain.Dispense;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispenseRepository extends JpaRepository<Dispense, UUID> {

    List<Dispense> findByPrescriptionItemIdOrderByDispensedAt(UUID prescriptionItemId);

    /** Everything that came out of one batch — the question a recall asks. */
    List<Dispense> findByBatchIdOrderByDispensedAt(UUID batchId);
}
