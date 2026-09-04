package com.hms.immunisation.repo;

import com.hms.immunisation.domain.ImmunisationSchedule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRepository extends JpaRepository<ImmunisationSchedule, UUID> {

    Optional<ImmunisationSchedule> findByCode(String code);

    List<ImmunisationSchedule> findByActiveTrueOrderByCodeAsc();
}
