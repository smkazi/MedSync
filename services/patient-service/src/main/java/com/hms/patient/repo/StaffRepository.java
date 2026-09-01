package com.hms.patient.repo;

import com.hms.patient.domain.Staff;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StaffRepository extends JpaRepository<Staff, UUID> {

    Optional<Staff> findByEmployeeNo(String employeeNo);

    Optional<Staff> findByUserId(UUID userId);

    boolean existsByEmployeeNo(String employeeNo);

    /** A staff row is always shown with its department, so the department is fetched with it. */
    @EntityGraph(attributePaths = "department")
    Optional<Staff> findDetailById(UUID id);

    /**
     * Staff search, optionally narrowed to one department.
     *
     * <p>The department predicate is {@code :departmentCode = '%' or ...} rather than
     * {@code ... or s.department is null}, and the shape matters. The join leaves
     * {@code department} null for every staff member who has not been assigned to one, so an
     * "or is null" made those rows match <em>every</em> department filter: asking for the
     * paediatric team returned all the unassigned staff too. The same defect shipped in the user
     * role filter in identity-service and in the room search here; comparing the pattern to
     * {@code '%'} says "no filter was supplied", which is what was meant.
     */
    @EntityGraph(attributePaths = "department")
    @Query(value = """
            select s from Staff s
            where lower(s.fullName) like :pattern
              and (:departmentCode = '%' or s.department.code like :departmentCode)
              and (:includeInactive = true or s.active = true)
            """,
            countQuery = """
            select count(s) from Staff s
            where lower(s.fullName) like :pattern
              and (:departmentCode = '%' or s.department.code like :departmentCode)
              and (:includeInactive = true or s.active = true)
            """)
    Page<Staff> search(@Param("pattern") String pattern,
                       @Param("departmentCode") String departmentCode,
                       @Param("includeInactive") boolean includeInactive,
                       Pageable pageable);
}
