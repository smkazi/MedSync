package com.hms.patient.repo;

import com.hms.patient.domain.Room;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    /**
     * A room is never useful without its floor — "Room GF-GEN" tells nobody where to go — and
     * usually not without its department either, so both are fetched with it. Reading them lazily
     * after the transaction closed is what produced a LazyInitializationException on every GET
     * earlier in this project.
     */
    @EntityGraph(attributePaths = {"floor", "department", "roomType"})
    Optional<Room> findDetailByCodeIgnoreCase(String code);

    @EntityGraph(attributePaths = {"floor", "department", "roomType"})
    Optional<Room> findDetailById(UUID id);

    boolean existsByCodeIgnoreCase(String code);

    @EntityGraph(attributePaths = {"floor", "department", "roomType"})
    List<Room> findByActiveTrueOrderByCodeAsc();

    /**
     * Directory search.
     *
     * <p>Every filter is an always-present LIKE pattern or a boolean rather than a nullable value:
     * a bare {@code :param is null} check sends an untyped null that PostgreSQL infers as
     * {@code bytea}, and {@code lower(bytea)} does not exist. See {@code QueryPatterns}.
     *
     * <p>The department predicate is {@code :departmentCode = '%' or ...} and not
     * {@code ... or r.department is null}. The shape matters: the outer join leaves
     * {@code department} null for every non-clinical room, so an "or is null" would have made a
     * lobby and a plant room match a filter for the paediatric clinic. That exact bug shipped in
     * the user-role filter and in {@code StaffRepository.search} before being found by a test.
     *
     * @param pattern        lower-cased {@code %term%} over code and name, or {@code %} for all
     * @param departmentCode exact department code, or {@code %} for no filter
     * @param roomTypeCode   exact room type code, or {@code %} for no filter
     */
    @EntityGraph(attributePaths = {"floor", "department", "roomType"})
    @Query(value = """
            select r from Room r
            where (lower(r.code) like :pattern or lower(r.name) like :pattern)
              and (:departmentCode = '%' or r.department.code like :departmentCode)
              and (:roomTypeCode = '%' or r.roomType.code like :roomTypeCode)
              and (:floorCode = '%' or r.floor.code like :floorCode)
              and (:includeInactive = true or r.active = true)
            """,
            countQuery = """
            select count(r) from Room r
            where (lower(r.code) like :pattern or lower(r.name) like :pattern)
              and (:departmentCode = '%' or r.department.code like :departmentCode)
              and (:roomTypeCode = '%' or r.roomType.code like :roomTypeCode)
              and (:floorCode = '%' or r.floor.code like :floorCode)
              and (:includeInactive = true or r.active = true)
            """)
    Page<Room> search(@Param("pattern") String pattern,
                      @Param("departmentCode") String departmentCode,
                      @Param("roomTypeCode") String roomTypeCode,
                      @Param("floorCode") String floorCode,
                      @Param("includeInactive") boolean includeInactive,
                      Pageable pageable);
}
