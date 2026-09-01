package com.hms.scheduling.repo;

import com.hms.scheduling.domain.ClinicianSchedule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicianScheduleRepository extends JpaRepository<ClinicianSchedule, UUID> {

    List<ClinicianSchedule> findByClinicianIdAndActiveTrue(UUID clinicianId);

    List<ClinicianSchedule> findByClinicianIdAndDayOfWeekAndActiveTrue(UUID clinicianId, int dayOfWeek);
}
