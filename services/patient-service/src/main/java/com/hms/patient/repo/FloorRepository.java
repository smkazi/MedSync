package com.hms.patient.repo;

import com.hms.patient.domain.Floor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FloorRepository extends JpaRepository<Floor, UUID> {

    Optional<Floor> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    /** Ordered by level so a directory reads bottom-up, the way a lift panel does. */
    List<Floor> findByActiveTrueOrderByLevelAsc();
}
