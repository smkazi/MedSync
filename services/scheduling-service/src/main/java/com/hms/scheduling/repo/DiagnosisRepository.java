package com.hms.scheduling.repo;

import com.hms.scheduling.domain.Diagnosis;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiagnosisRepository extends JpaRepository<Diagnosis, UUID> {

    List<Diagnosis> findByEncounterIdOrderByCategoryAsc(UUID encounterId);

    boolean existsByEncounterIdAndIcd10Code(UUID encounterId, String icd10Code);

    /**
     * How many cases of each notifiable code were diagnosed in a period.
     *
     * <p><strong>The aggregate cannot leak an identifier because it never selects one.</strong> That
     * is a better guarantee than a mapper that leaves fields out: a field somebody adds to a DTO
     * later is a disclosure, and a column somebody adds to this select is a compile error in the
     * projection below. The rule the surveillance report rests on, expressed as a query rather than
     * as a review comment.
     *
     * <p>An <em>equality</em> join on the code list, which is what lets {@code idx_diagnoses_code}
     * serve it. A LIKE against prefixes would both widen the answer invisibly and put the query back
     * on a sequential scan of every diagnosis the hospital has ever recorded.
     *
     * <p>Counts <em>distinct patients</em> rather than rows. One patient diagnosed twice with the
     * same condition in a fortnight is one case: an incidence figure that counted the second visit
     * would report an outbreak made of follow-ups.
     *
     * <p>The period is bounded on the encounter's start instant, and the caller resolves the day
     * boundary in the hospital's zone. That is why scheduling had to join the {@code HMS_ZONE} chain
     * before this existed: a notifiable week running Monday-to-Sunday UTC in an IST hospital puts
     * five and a half hours of every Sunday into the next week's return.
     *
     * <p>Navigates {@code d.encounter} without a getter for it, deliberately. JPQL walks the mapped
     * attribute; adding {@code getEncounter()} to {@link Diagnosis} would hand every holder of a
     * diagnosis the encounter and through it every note and vital hanging off it.
     */
    @Query("""
            select d.icd10Code as icd10Code, count(distinct d.encounter.patientId) as cases
              from Diagnosis d
             where d.icd10Code in :codes
               and d.encounter.startedAt >= :from
               and d.encounter.startedAt < :to
             group by d.icd10Code
             order by count(distinct d.encounter.patientId) desc, d.icd10Code asc
            """)
    List<NotifiableCount> notifiableCounts(@Param("codes") Collection<String> codes,
                                           @Param("from") Instant from, @Param("to") Instant to);

    /**
     * The two values {@link #notifiableCounts} answers.
     *
     * <p>An interface rather than an {@code Object[]}, for the reason {@code InvoiceRepository}
     * records: the array version compiled, ran, and threw an index-out-of-bounds at runtime because
     * the shape it comes back in is not the shape it looks like. A projection makes the columns
     * names the compiler can check — and here it does a second job, which is that there is nowhere
     * in it to put a patient id.
     */
    interface NotifiableCount {

        String getIcd10Code();

        long getCases();
    }
}
