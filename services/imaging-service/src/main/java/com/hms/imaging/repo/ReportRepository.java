package com.hms.imaging.repo;

import com.hms.imaging.domain.ImagingReport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<ImagingReport, UUID> {

    Optional<ImagingReport> findByStudyId(UUID studyId);
}
