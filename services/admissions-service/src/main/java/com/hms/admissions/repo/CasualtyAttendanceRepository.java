package com.hms.admissions.repo;

import com.hms.admissions.domain.AdmissionEnums;
import com.hms.admissions.domain.CasualtyAttendance;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CasualtyAttendanceRepository extends JpaRepository<CasualtyAttendance, UUID> {

    /**
     * The board.
     *
     * <p>{@code triageAcuity asc, arrivedAt asc} — sickest first, ties broken by who has waited
     * longest. This ordering is the whole clinical point of the module and it is in the query
     * rather than in a comparator on the way out, so no caller can accidentally render the list in
     * arrival order.
     */
    @Query("""
            select a from CasualtyAttendance a
            where a.status in :statuses
            order by a.triageAcuity asc, a.arrivedAt asc
            """)
    List<CasualtyAttendance> board(List<AdmissionEnums.AttendanceStatus> statuses);

    List<CasualtyAttendance> findByPatientIdOrderByArrivedAtDesc(UUID patientId);
}
