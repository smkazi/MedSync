package com.hms.patient.repo;

import com.hms.patient.domain.AllergySeverity;
import com.hms.patient.domain.Patient;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Optional<Patient> findByMrn(String mrn);

    boolean existsByMrn(String mrn);

    /**
     * Loads a patient together with their allergies in one query. The chart view always renders
     * allergies, so fetching them lazily would only guarantee a second round trip — or, once the
     * transaction has closed, a {@code LazyInitializationException}.
     */
    @EntityGraph(attributePaths = "allergies")
    Optional<Patient> findDetailById(UUID id);

    @EntityGraph(attributePaths = "allergies")
    Optional<Patient> findDetailByMrn(String mrn);

    /**
     * Registration desk search across name, MRN and phone.
     *
     * <p>{@code pattern} is always a LIKE pattern ({@code %} = no filter) rather than a nullable
     * parameter; see {@link com.hms.common.data.QueryPatterns} for why.
     */
    @Query(value = """
            select p from Patient p
            where (lower(p.firstName) like :pattern
                   or lower(p.lastName) like :pattern
                   or lower(concat(p.firstName, ' ', p.lastName)) like :pattern
                   or lower(p.mrn) like :pattern
                   or coalesce(p.phone, '') like :pattern)
              and (:includeInactive = true or p.active = true)
            """,
            countQuery = """
            select count(p) from Patient p
            where (lower(p.firstName) like :pattern
                   or lower(p.lastName) like :pattern
                   or lower(concat(p.firstName, ' ', p.lastName)) like :pattern
                   or lower(p.mrn) like :pattern
                   or coalesce(p.phone, '') like :pattern)
              and (:includeInactive = true or p.active = true)
            """)
    Page<Patient> search(@Param("pattern") String pattern,
                         @Param("includeInactive") boolean includeInactive,
                         Pageable pageable);

    /**
     * Which of these patients carry an allergy at one of the given severities.
     *
     * <p>Search rows show a critical-allergy marker. Asking each patient entity for it would issue
     * one query per row; this answers a whole page in a single query.
     */
    @Query("""
            select distinct a.patient.id from PatientAllergy a
            where a.patient.id in :patientIds and a.severity in :severities
            """)
    Set<UUID> findIdsWithAllergySeverity(@Param("patientIds") Collection<UUID> patientIds,
                                         @Param("severities") Collection<AllergySeverity> severities);

    /**
     * Candidate duplicates for a new registration: same surname and date of birth, which is how
     * the same person most often gets registered twice at a busy front desk.
     */
    @Query("""
            select p from Patient p
            where lower(p.lastName) = lower(:lastName)
              and p.dateOfBirth = :dateOfBirth
              and p.active = true
            """)
    List<Patient> findPotentialDuplicates(@Param("lastName") String lastName,
                                          @Param("dateOfBirth") LocalDate dateOfBirth);

    @Query(value = "select nextval('mrn_seq')", nativeQuery = true)
    long nextMrnSequence();
}
