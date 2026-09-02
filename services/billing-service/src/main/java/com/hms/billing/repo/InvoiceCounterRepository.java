package com.hms.billing.repo;

import com.hms.billing.domain.InvoiceCounter;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Issues invoice numbers.
 *
 * <p>One statement, the same shape as the OPD token counter and
 * {@code UserRepository.recordFailedLogin}: {@code SELECT max+1} then insert is a lost update, and
 * two invoices sharing a number is worse than two patients sharing a queue token — a number is what
 * a patient quotes back and what an auditor traces.
 *
 * <p><strong>No {@code @Modifying}, deliberately.</strong> An {@code INSERT ... RETURNING} does
 * return a result and Spring Data's modifying path expects none, so with the annotation PostgreSQL
 * answers "A result was returned when none was expected". Learned on the queue counter in an
 * earlier slice; written down here so it is not learned twice.
 */
public interface InvoiceCounterRepository extends JpaRepository<InvoiceCounter, String> {

    @Query(value = """
            INSERT INTO invoice_counters (series, next_number)
            VALUES (:series, 2)
            ON CONFLICT (series)
            DO UPDATE SET next_number = invoice_counters.next_number + 1
            RETURNING next_number - 1
            """, nativeQuery = true)
    int issueNext(@Param("series") String series);

    @Query(value = "SELECT next_number FROM invoice_counters WHERE series = :series",
            nativeQuery = true)
    List<Integer> peek(@Param("series") String series);
}
