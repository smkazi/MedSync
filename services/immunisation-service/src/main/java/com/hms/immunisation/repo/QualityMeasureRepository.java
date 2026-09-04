package com.hms.immunisation.repo;

import com.hms.immunisation.domain.QualityMeasure;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QualityMeasureRepository extends JpaRepository<QualityMeasure, UUID> {

    Optional<QualityMeasure> findByCode(String code);

    List<QualityMeasure> findByActiveTrueOrderByCodeAsc();
}
