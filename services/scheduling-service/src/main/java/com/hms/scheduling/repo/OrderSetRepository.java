package com.hms.scheduling.repo;

import com.hms.scheduling.domain.OrderSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderSetRepository extends JpaRepository<OrderSet, UUID> {

    Optional<OrderSet> findByCode(String code);

    /**
     * The sets a clinician may pick from, optionally narrowed to a department.
     *
     * <p>A department filter includes the general sets rather than excluding them: "fever, first
     * line" belongs to everybody, and a cardiology clinic that could not see it would type the same
     * three orders by hand.
     */
    @Query("""
            select s from OrderSet s
             where s.active = true
               and (:department = '' or s.departmentCode is null or s.departmentCode = :department)
             order by s.name
            """)
    List<OrderSet> available(@Param("department") String department);

    List<OrderSet> findAllByOrderByNameAsc();
}
