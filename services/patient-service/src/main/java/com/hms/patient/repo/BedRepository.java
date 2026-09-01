package com.hms.patient.repo;

import com.hms.patient.domain.Bed;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BedRepository extends JpaRepository<Bed, UUID> {

    @EntityGraph(attributePaths = {"room", "room.floor", "room.roomType"})
    List<Bed> findByRoomIdAndActiveTrueOrderByCodeAsc(UUID roomId);

    @EntityGraph(attributePaths = {"room", "room.floor", "room.roomType"})
    @Query("select b from Bed b where b.active = true and b.room.active = true order by b.room.code, b.code")
    List<Bed> findAllActive();

    /**
     * Every bed in the rooms of one type — how admissions-service asks for "the casualty beds"
     * without needing to know which rooms make up casualty.
     */
    @EntityGraph(attributePaths = {"room", "room.floor", "room.roomType"})
    @Query("""
            select b from Bed b
            where b.active = true and b.room.active = true
              and b.room.roomType.code in :roomTypeCodes
            order by b.room.code, b.code
            """)
    List<Bed> findActiveByRoomTypes(@Param("roomTypeCodes") List<String> roomTypeCodes);

    boolean existsByRoomIdAndCodeIgnoreCase(UUID roomId, String code);
}
