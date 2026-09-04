package com.hms.immunisation.repo;

import com.hms.immunisation.domain.QualityMeasureAntigen;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QualityMeasureAntigenRepository
        extends JpaRepository<QualityMeasureAntigen, UUID> {

    List<QualityMeasureAntigen> findByMeasureCodeOrderByAntigenCodeAsc(String measureCode);
}
