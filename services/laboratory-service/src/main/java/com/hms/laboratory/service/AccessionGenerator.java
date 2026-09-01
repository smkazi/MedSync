package com.hms.laboratory.service;

import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.hms.laboratory.repo.LabOrderRepository;

/**
 * Issues accession numbers of the form {@code L2026-000042} — the identifier the lab prints on the
 * tube and the analyzer sends back.
 *
 * <p>Drawn from a PostgreSQL sequence for the same reason MRNs are: two tubes labelled at the same
 * moment must never share a number, and a sequence is concurrency-safe where {@code max + 1} is not.
 */
@Service
public class AccessionGenerator {

    private final LabOrderRepository orders;
    private final String prefix;

    public AccessionGenerator(LabOrderRepository orders,
                              @Value("${hms.laboratory.accession-prefix:L}") String prefix) {
        this.orders = orders;
        this.prefix = prefix;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next() {
        long sequence = orders.nextAccessionSequence();
        return "%s%d-%06d".formatted(prefix, LocalDate.now().getYear(), sequence);
    }
}
