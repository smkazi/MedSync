package com.hms.scheduling.repo;

import com.hms.scheduling.domain.ScheduleBlackout;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleBlackoutRepository extends JpaRepository<ScheduleBlackout, UUID> {

    @Query("""
            select b from ScheduleBlackout b
            where b.clinicianId = :clinicianId
              and b.startsAt < :to and b.endsAt > :from
            """)
    List<ScheduleBlackout> findOverlapping(@Param("clinicianId") UUID clinicianId,
                                           @Param("from") Instant from, @Param("to") Instant to);
}
