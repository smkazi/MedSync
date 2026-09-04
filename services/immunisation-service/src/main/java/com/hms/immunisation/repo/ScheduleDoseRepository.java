package com.hms.immunisation.repo;

import com.hms.immunisation.domain.ScheduleDose;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleDoseRepository extends JpaRepository<ScheduleDose, UUID> {

    /**
     * The whole schedule, in antigen and dose order.
     *
     * <p>Read once per due list and handed to the calculator for every child in the cohort. Ordered
     * here rather than in Java because {@code idx_schedule_dose_read} is in exactly this order, and
     * because a calculator that had to sort its own input could be given an unsorted schedule and
     * would quietly count dose 3 as dose 1.
     */
    List<ScheduleDose> findByScheduleCodeOrderByAntigenCodeAscDoseNumberAsc(String scheduleCode);
}
