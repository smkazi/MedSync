package com.hms.patient.service;

import com.hms.patient.repo.PatientRepository;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues medical record numbers of the form {@code MRN-2026-000042}.
 *
 * <p>The counter comes from a PostgreSQL sequence rather than {@code max(mrn) + 1}: sequences are
 * concurrency-safe and non-transactional, so two simultaneous registrations can never be assigned
 * the same MRN, and a rolled-back registration simply leaves a gap.
 */
@Service
public class MrnGenerator {

    private final PatientRepository patients;
    private final String prefix;

    public MrnGenerator(PatientRepository patients, @Value("${hms.patient.mrn-prefix:MRN}") String prefix) {
        this.patients = patients;
        this.prefix = prefix;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next() {
        long sequence = patients.nextMrnSequence();
        return "%s-%d-%06d".formatted(prefix, LocalDate.now().getYear(), sequence);
    }
}
