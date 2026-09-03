package com.hms.interop.repo;

import com.hms.interop.domain.Hl7Exchange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface Hl7ExchangeRepository extends JpaRepository<Hl7Exchange, UUID> {

    /**
     * The message log, newest first, optionally narrowed to the ones that went wrong.
     *
     * <p>The failures filter is not a convenience. An interface that has been running for a week
     * has tens of thousands of accepted messages and a dozen that matter, and a screen that makes
     * somebody page through the former to find the latter is a screen nobody uses twice.
     *
     * <p>{@code :failuresOnly} is a boolean rather than a nullable filter, because this platform
     * learned what an untyped null does to PostgreSQL: it is inferred as {@code bytea}, and the
     * query fails on a function that does not exist for it.
     */
    @Query("""
            select e from Hl7Exchange e
             where (:failuresOnly = false
                    or e.error is not null
                    or e.ackCode in ('AE', 'AR'))
             order by e.receivedAt desc
            """)
    Page<Hl7Exchange> log(@Param("failuresOnly") boolean failuresOnly, Pageable pageable);

    /** "Did you get MSG00042?" — the question this table is asked most, asked by control id. */
    List<Hl7Exchange> findByControlIdOrderByReceivedAtDesc(String controlId);
}
