package com.hms.imaging.repo;

import com.hms.imaging.domain.ImagingSeries;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeriesRepository extends JpaRepository<ImagingSeries, UUID> {

    Optional<ImagingSeries> findBySeriesInstanceUid(String seriesInstanceUid);

    List<ImagingSeries> findByStudyIdOrderBySeriesNumberAsc(UUID studyId);
}
