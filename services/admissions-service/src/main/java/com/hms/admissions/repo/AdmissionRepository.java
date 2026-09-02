package com.hms.admissions.repo;

import com.hms.admissions.domain.Admission;
import com.hms.admissions.domain.AdmissionEnums;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdmissionRepository extends JpaRepository<Admission, UUID> {

    List<Admission> findByStatusOrderByAdmittedAtAsc(AdmissionEnums.AdmissionStatus status);

    List<Admission> findByPatientIdOrderByAdmittedAtDesc(UUID patientId);

    /**
     * The census, optionally narrowed to one room.
     *
     * <p>{@code exactOrAny} rather than {@code (:room is null or ...)}: an untyped null makes
     * PostgreSQL infer {@code bytea} for the parameter and the query fails at runtime. The pattern
     * is in {@code QueryPatterns} and this is its string-valued form.
     */
    @Query("""
            select a from Admission a
            where a.status = :status
              and (:roomCode is null or a.roomCode = :roomCode)
            order by a.roomCode asc, a.bedCode asc
            """)
    List<Admission> census(AdmissionEnums.AdmissionStatus status, String roomCode);
}
