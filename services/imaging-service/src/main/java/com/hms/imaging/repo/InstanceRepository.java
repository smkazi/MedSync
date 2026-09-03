package com.hms.imaging.repo;

import com.hms.imaging.domain.ImagingInstance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstanceRepository extends JpaRepository<ImagingInstance, UUID> {

    Optional<ImagingInstance> findBySopInstanceUid(String sopInstanceUid);

    List<ImagingInstance> findBySeriesIdOrderByInstanceNumberAsc(UUID seriesId);

    long countBySeriesId(UUID seriesId);
}
