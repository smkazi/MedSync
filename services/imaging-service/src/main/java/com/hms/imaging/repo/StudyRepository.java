package com.hms.imaging.repo;

import com.hms.imaging.domain.ImagingStudy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudyRepository extends JpaRepository<ImagingStudy, UUID> {

    Optional<ImagingStudy> findByStudyInstanceUid(String studyInstanceUid);

    List<ImagingStudy> findByOrderIdOrderByReceivedAtDesc(UUID orderId);

    List<ImagingStudy> findByPatientIdOrderByReceivedAtDesc(UUID patientId);

    /**
     * Everything that arrived and matched nothing, newest first.
     *
     * <p>This is a work list rather than a report: an image that came in for nobody is a thing
     * somebody has to resolve, and a platform that swallowed it would lose a study that exists
     * on a scanner's disk either way. Hits the partial index.
     */
    Page<ImagingStudy> findByOrderIdIsNullOrderByReceivedAtDesc(Pageable pageable);
}
