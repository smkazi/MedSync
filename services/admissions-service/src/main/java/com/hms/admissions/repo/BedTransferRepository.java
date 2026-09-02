package com.hms.admissions.repo;

import com.hms.admissions.domain.BedTransfer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BedTransferRepository extends JpaRepository<BedTransfer, UUID> {

    List<BedTransfer> findByAdmissionIdOrderByMovedAtDesc(UUID admissionId);
}
