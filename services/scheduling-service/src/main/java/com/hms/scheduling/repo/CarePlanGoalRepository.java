package com.hms.scheduling.repo;

import com.hms.scheduling.domain.CarePlanGoal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarePlanGoalRepository extends JpaRepository<CarePlanGoal, UUID> {
}
