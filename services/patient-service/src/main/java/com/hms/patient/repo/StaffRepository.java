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
     * <p>The search covers the name, the employee number and the specialty, which is what the
     * screen's own placeholder has always promised. It matched the full name alone, so looking
     * somebody up by the number on their badge returned nothing at all — the promise was in the
     * placeholder text and nowhere else.
     *
     * <p>The department predicate has to get two things right at once, and it took two goes.
     * Comparing the pattern to {@code '%'} says "no filter was supplied", which is what was meant;
     * writing {@code or s.department is null} instead made unassigned staff match <em>every</em>
     * department filter, so asking for the paediatric team returned all of them too. That was the
     * first mistake, and it shipped in the user-role filter in identity-service as well.
     *
     * <p>The second is why the join below is explicit. As the path {@code s.department.code} it
     * became an <em>inner</em> join, and the {@code '%'} guard cannot rescue a row the join has
     * already dropped — so a staff member with no department was invisible to this search whatever
     * was filtered. It was latent here (every seeded row has a department) and live in the room
     * search, where two thirds of the building was missing. A {@code left join} keeps the row and
     * the guard still works, because {@code d.code like 'CARD'} is null for an unassigned staff
     * member and null is not true.
     */
    @EntityGraph(attributePaths = "department")
    @Query(value = """
            select s from Staff s
            left join s.department d
            where (lower(s.fullName) like :pattern
                   or lower(s.employeeNo) like :pattern
                   or lower(coalesce(s.specialty, '')) like :pattern)
              and (:departmentCode = '%' or d.code like :departmentCode)
              and (:includeInactive = true or s.active = true)
            """,
            countQuery = """
            select count(s) from Staff s
            left join s.department d
            where (lower(s.fullName) like :pattern
                   or lower(s.employeeNo) like :pattern
                   or lower(coalesce(s.specialty, '')) like :pattern)
              and (:departmentCode = '%' or d.code like :departmentCode)
              and (:includeInactive = true or s.active = true)
            """)
    Page<Staff> search(@Param("pattern") String pattern,
                       @Param("departmentCode") String departmentCode,
                       @Param("includeInactive") boolean includeInactive,
                       Pageable pageable);
}
