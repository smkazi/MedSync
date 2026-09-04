package com.hms.immunisation.repo;

import com.hms.immunisation.domain.Immunisation;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImmunisationRepository extends JpaRepository<Immunisation, UUID> {

    List<Immunisation> findByPatientIdOrderByGivenOnAsc(UUID patientId);

    /**
     * Every dose for a whole cohort, in one statement.
     *
     * <p>The read the due list and every measure make. One query for the cohort rather than one per
     * child: a birth cohort is a few thousand children, and a query per child is a few thousand
     * round trips to answer one screen.
     */
    @Query("""
            select i from Immunisation i
            where i.patientId in :patientIds
            order by i.patientId asc, i.givenOn asc
            """)
    List<Immunisation> forCohort(@Param("patientIds") Collection<UUID> patientIds);

    /** Who received a vial of a recalled lot. */
    List<Immunisation> findByLotIdOrderByGivenOnAsc(UUID lotId);
}
