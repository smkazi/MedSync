package com.hms.scheduling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClinicalNoteTest {

    private Encounter encounter() {
        return new Encounter(UUID.randomUUID(), "MRN-1", UUID.randomUUID(), "GEN",
                SchedulingEnums.EncounterType.OUTPATIENT);
    }

    @Test
    @DisplayName("a new note is unsigned")
    void startsUnsigned() {
        assertThat(new ClinicalNote(encounter(), "dr.rao", 1).isSigned()).isFalse();
    }

    @Test
    @DisplayName("signing records who signed and when")
    void signingIsAttributed() {
        ClinicalNote note = new ClinicalNote(encounter(), "dr.rao", 1);
        note.updateContent("s", null, null, null);

        note.sign("dr.rao");

        assertThat(note.isSigned()).isTrue();
        assertThat(note.getSignedBy()).isEqualTo("dr.rao");
        assertThat(note.getSignedAt()).isNotNull();
    }

    @Test
    @DisplayName("an empty note has no content to attest to")
    void emptyNoteHasNoContent() {
        assertThat(new ClinicalNote(encounter(), "dr.rao", 1).hasContent()).isFalse();

        ClinicalNote blanks = new ClinicalNote(encounter(), "dr.rao", 1);
        blanks.updateContent("  ", "", null, "   ");
        assertThat(blanks.hasContent()).isFalse();
    }

    @Test
    @DisplayName("content in any single section makes a note signable")
    void anySectionCounts() {
        ClinicalNote note = new ClinicalNote(encounter(), "dr.rao", 1);
        note.updateContent(null, null, "Iron deficiency anaemia", null);
        assertThat(note.hasContent()).isTrue();
    }

    @Test
    @DisplayName("an addendum points back at the revision it amends")
    void addendumLinksToItsOriginal() {
        Encounter subject = encounter();
        ClinicalNote original = new ClinicalNote(subject, "dr.rao", 1);
        original.updateContent("first", null, null, null);
        original.sign("dr.rao");

        ClinicalNote addendum = new ClinicalNote(subject, "dr.rao", 2);
        addendum.setAmendsId(original.getId());

        assertThat(addendum.getRevision()).isEqualTo(2);
        assertThat(addendum.getAmendsId()).isEqualTo(original.getId());
        assertThat(original.getSubjective())
                .as("the signed original must remain exactly as it was signed")
                .isEqualTo("first");
    }

    @Test
    @DisplayName("the current note is the highest revision on the encounter")
    void currentNoteIsTheLatestRevision() {
        Encounter subject = encounter();
        subject.addNote(new ClinicalNote(subject, "dr.rao", 1));
        ClinicalNote second = new ClinicalNote(subject, "dr.rao", 2);
        subject.addNote(second);

        assertThat(subject.currentNote()).isSameAs(second);
    }

    @Test
    @DisplayName("closing an encounter records when it ended")
    void closingRecordsTheEndTime() {
        Encounter subject = encounter();
        assertThat(subject.isOpen()).isTrue();

        subject.close();

        assertThat(subject.isOpen()).isFalse();
        assertThat(subject.getEndedAt()).isNotNull();
    }
}
