package com.hms.imaging.repo;

import com.hms.imaging.domain.ImagingEnums;
import com.hms.imaging.domain.ImagingOrder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<ImagingOrder, UUID> {

    Optional<ImagingOrder> findByAccessionNo(String accessionNo);

    List<ImagingOrder> findByPatientIdOrderByOrderedAtDesc(UUID patientId);

    List<ImagingOrder> findByEncounterIdOrderByOrderedAtDesc(UUID encounterId);

    /**
     * The modality worklist: what has been asked for and not yet acquired.
     *
     * <p>Ordered by priority before time, which is the whole reason it is not a queue — a STAT
     * head CT asked for a minute ago goes ahead of a routine knee film booked this morning.
     * {@code priority} is stored as its enum name, so the ordering is spelled out here rather
     * than left to alphabetical accident (URGENT would sort before STAT, and ROUTINE before
     * both).
     *
     * <p>Hits {@code idx_imaging_worklist}, which is partial on exactly these three statuses: a
     * modality asks this question every few minutes and must not pay for every study ever
     * ordered.
     */
    @Query("""
            select o from ImagingOrder o
             where o.status in ('ORDERED', 'SCHEDULED', 'IN_PROGRESS')
               and (:modality = '%' or upper(o.modality) = upper(:modality))
             order by case o.priority
                          when com.hms.imaging.domain.ImagingEnums$Priority.STAT then 0
                          when com.hms.imaging.domain.ImagingEnums$Priority.URGENT then 1
                          else 2
                      end,
                      coalesce(o.scheduledFor, o.orderedAt)
            """)
    List<ImagingOrder> worklist(@Param("modality") String modality);

    /** The radiologist's queue: acquired and unread, oldest first — nobody's images should wait. */
    List<ImagingOrder> findByStatusOrderByOrderedAtAsc(ImagingEnums.OrderStatus status);

    /**
     * The next accession number, from a sequence.
     *
     * <p>A sequence rather than {@code max + 1} for the reason the laboratory's is: two orders
     * placed in the same moment must never be handed the same number, and here that would put
     * two patients' images in one study. The number leaves the platform written into every
     * image the modality produces, so it is the one identifier that cannot be corrected later.
     */
    @Query(value = "select nextval('imaging_accession_seq')", nativeQuery = true)
    long nextAccessionSequence();
}
