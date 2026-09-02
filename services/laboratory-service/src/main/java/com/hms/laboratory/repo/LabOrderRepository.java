package com.hms.laboratory.repo;

import com.hms.laboratory.domain.Analyzer;
import com.hms.laboratory.domain.DeviceMessage;
import com.hms.laboratory.domain.HistogramRecord;
import com.hms.laboratory.domain.LabEnums;
import com.hms.laboratory.domain.LabOrder;
import com.hms.laboratory.domain.LabResult;
import com.hms.laboratory.domain.LabTestCatalogEntry;
import com.hms.laboratory.domain.ReferenceRange;
import com.hms.laboratory.domain.Specimen;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LabOrderRepository extends JpaRepository<LabOrder, UUID> {

    /**
     * Loads an order with its ordered tests.
     *
     * <p>Only one collection is fetched in the query: Hibernate refuses to fetch two list
     * associations at once (MultipleBagFetchException), and fetching both as a join would produce a
     * cartesian product. Specimens are read lazily within the same transaction, where mapping to
     * DTOs also happens.
     */
    @EntityGraph(attributePaths = "items")
    Optional<LabOrder> findDetailById(UUID id);

    @Query(value = """
            select o from LabOrder o
            where (o.patientMrn like :mrn)
              and (o.status in :statuses)
            """,
            countQuery = """
            select count(o) from LabOrder o
            where (o.patientMrn like :mrn)
              and (o.status in :statuses)
            """)
    Page<LabOrder> search(@Param("mrn") String mrn,
                          @Param("statuses") List<LabEnums.OrderStatus> statuses,
                          Pageable pageable);

    List<LabOrder> findByPatientIdOrderByOrderedAtDesc(UUID patientId);

    /**
     * The orders raised from one encounter.
     *
     * <p>A separate query rather than a nullable filter threaded through {@link #search}: the chart
     * asks this one question and nothing else asks it, and a worklist that could accidentally be
     * narrowed to a single encounter is a worklist a technician can lose tubes in.
     */
    List<LabOrder> findByEncounterIdOrderByOrderedAtDesc(UUID encounterId);

    /**
     * Finds the open order a device message belongs to, matched on the accession number the
     * lab labelled the tube with, newest first.
     */
    @Query("""
            select s.order from Specimen s
            where s.accessionNo = :accessionNo
              and s.order.status <> com.hms.laboratory.domain.LabEnums$OrderStatus.CANCELLED
            order by s.createdAt desc
            """)
    List<LabOrder> findOpenOrdersByAccession(@Param("accessionNo") String accessionNo);

    /**
     * Fallback match by patient MRN when the analyzer sent a patient id rather than the
     * accession number, restricted to orders still awaiting results.
     */
    @Query("""
            select o from LabOrder o
            where o.patientMrn = :mrn
              and o.status in (com.hms.laboratory.domain.LabEnums$OrderStatus.ORDERED,
                               com.hms.laboratory.domain.LabEnums$OrderStatus.COLLECTED,
                               com.hms.laboratory.domain.LabEnums$OrderStatus.IN_PROGRESS)
            order by o.orderedAt desc
            """)
    List<LabOrder> findAwaitingResultsByMrn(@Param("mrn") String mrn);

    @Query(value = "select nextval('accession_seq')", nativeQuery = true)
    long nextAccessionSequence();
}
