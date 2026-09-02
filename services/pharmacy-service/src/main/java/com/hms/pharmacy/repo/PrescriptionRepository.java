package com.hms.pharmacy.repo;

import com.hms.pharmacy.domain.Prescription;
import com.hms.pharmacy.domain.PharmacyEnums.PrescriptionStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrescriptionRepository extends JpaRepository<Prescription, UUID> {

    List<Prescription> findByPatientIdOrderByIssuedAtDesc(UUID patientId);

    List<Prescription> findByEncounterIdOrderByIssuedAtDesc(UUID encounterId);

    /** The dispensing queue: what the pharmacy still has work to do on, oldest first. */
    @Query("""
            select distinct p from Prescription p
              join p.items i
             where p.status in :statuses
               and i.quantityDispensed < i.quantity
             order by p.issuedAt
            """)
    List<Prescription> queue(@Param("statuses") Collection<PrescriptionStatus> statuses);
}
