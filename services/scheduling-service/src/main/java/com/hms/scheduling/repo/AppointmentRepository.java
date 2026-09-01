package com.hms.scheduling.repo;

import com.hms.scheduling.domain.Appointment;
import com.hms.scheduling.domain.SchedulingEnums;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /**
     * Appointments occupying a clinician's time in a window.
     *
     * <p>Used to render availability, not to prevent double-booking — that is the database's
     * exclusion constraint, because a query-then-insert loses to a concurrent booking.
     */
    @Query("""
            select a from Appointment a
            where a.clinicianId = :clinicianId
              and a.startsAt < :to and a.endsAt > :from
              and a.status not in (com.hms.scheduling.domain.SchedulingEnums$AppointmentStatus.CANCELLED,
                                   com.hms.scheduling.domain.SchedulingEnums$AppointmentStatus.NO_SHOW)
            order by a.startsAt
            """)
    List<Appointment> findOccupying(@Param("clinicianId") UUID clinicianId,
                                    @Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            select a from Appointment a
            where (a.patientMrn like :mrn)
              and (a.status in :statuses)
              and a.startsAt >= :from and a.startsAt < :to
            """,
            countQuery = """
            select count(a) from Appointment a
            where (a.patientMrn like :mrn)
              and (a.status in :statuses)
              and a.startsAt >= :from and a.startsAt < :to
            """)
    Page<Appointment> search(@Param("mrn") String mrn,
                             @Param("statuses") List<SchedulingEnums.AppointmentStatus> statuses,
                             @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    List<Appointment> findByPatientIdOrderByStartsAtDesc(UUID patientId);

    /**
     * Booked appointments whose time has passed and who never checked in — the candidates a
     * clinic marks as no-shows at the end of a session.
     */
    @Query("""
            select a from Appointment a
            where a.status = com.hms.scheduling.domain.SchedulingEnums$AppointmentStatus.BOOKED
              and a.endsAt < :cutoff
            order by a.startsAt
            """)
    List<Appointment> findLapsed(@Param("cutoff") Instant cutoff);

    /** A patient's attendance history, for the no-show features. */
    @Query("""
            select count(a) from Appointment a
            where a.patientId = :patientId
              and a.status in (com.hms.scheduling.domain.SchedulingEnums$AppointmentStatus.COMPLETED,
                               com.hms.scheduling.domain.SchedulingEnums$AppointmentStatus.NO_SHOW)
            """)
    long countAttendanceHistory(@Param("patientId") UUID patientId);

    long countByPatientIdAndStatus(UUID patientId, SchedulingEnums.AppointmentStatus status);
}
