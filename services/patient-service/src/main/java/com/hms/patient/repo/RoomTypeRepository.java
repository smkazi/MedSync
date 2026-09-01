package com.hms.patient.repo;

import com.hms.patient.domain.RoomType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomTypeRepository extends JpaRepository<RoomType, String> {

    /** Ordered for a pick-list: display_order first, so the clinical types lead. */
    List<RoomType> findByActiveTrueOrderByDisplayOrderAscCodeAsc();

    Optional<RoomType> findByCodeIgnoreCase(String code);

    /**
     * Types whose rooms may carry appointments. Read from the data rather than matched against a
     * list of constants, so a new schedulable type needs no code change here.
     */
    List<RoomType> findByActiveTrueAndSchedulableTrueOrderByDisplayOrderAsc();

    List<RoomType> findByActiveTrueAndBedAllocatedTrueOrderByDisplayOrderAsc();
}
