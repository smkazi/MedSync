package com.hms.patient.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PatientTest {

    private Patient patient() {
        return new Patient("MRN-2026-000001", "Meera", "Nair", LocalDate.of(1988, 4, 12), Sex.FEMALE);
    }

    @Test
    void ageIsWholeYearsCompleted() {
        Patient young = new Patient("MRN-1", "A", "B", LocalDate.now().minusYears(30).plusDays(1), Sex.MALE);
        assertThat(young.age())
                .as("a birthday tomorrow means the year is not yet complete")
                .isEqualTo(29);

        Patient exact = new Patient("MRN-2", "A", "B", LocalDate.now().minusYears(30), Sex.MALE);
        assertThat(exact.age()).isEqualTo(30);
    }

    @Test
    void fullNameJoinsGivenAndFamilyName() {
        assertThat(patient().fullName()).isEqualTo("Meera Nair");
    }

    @Test
    void chartFlagsOnlySevereAndLifeThreateningAllergies() {
        Patient subject = patient();
        subject.addAllergy(new PatientAllergy(subject, "Dust", "Sneezing", AllergySeverity.MILD, "nurse"));
        assertThat(subject.hasCriticalAllergy()).isFalse();

        subject.addAllergy(new PatientAllergy(subject, "Penicillin", "Anaphylaxis",
                AllergySeverity.LIFE_THREATENING, "dr.rao"));
        assertThat(subject.hasCriticalAllergy()).isTrue();
    }

    @Test
    void severeCountsAsCritical() {
        Patient subject = patient();
        subject.addAllergy(new PatientAllergy(subject, "Sulfa", "Rash", AllergySeverity.SEVERE, "nurse"));
        assertThat(subject.hasCriticalAllergy()).isTrue();
    }

    @Test
    void patientStartsActiveAndAlive() {
        assertThat(patient().isActive()).isTrue();
        assertThat(patient().isDeceased()).isFalse();
    }
}
