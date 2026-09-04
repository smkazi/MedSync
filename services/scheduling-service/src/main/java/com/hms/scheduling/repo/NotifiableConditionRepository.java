package com.hms.scheduling.repo;

import com.hms.scheduling.domain.NotifiableCondition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotifiableConditionRepository extends JpaRepository<NotifiableCondition, UUID> {

    List<NotifiableCondition> findByActiveTrueOrderByIcd10CodeAsc();

    Optional<NotifiableCondition> findByIcd10Code(String icd10Code);
}
