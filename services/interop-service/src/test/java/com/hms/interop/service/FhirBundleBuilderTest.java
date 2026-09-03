package com.hms.interop.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.interop.client.dto.ClinicalViews.DiagnosisView;
import com.hms.interop.client.dto.ClinicalViews.EncounterView;
import com.hms.interop.client.dto.ClinicalViews.LabOrderItemView;
import com.hms.interop.client.dto.ClinicalViews.LabOrderView;
import com.hms.interop.client.dto.ClinicalViews.LabResultView;
import com.hms.interop.client.dto.ClinicalViews.NoteView;
import com.hms.interop.client.dto.ClinicalViews.PatientView;
import com.hms.interop.client.dto.ClinicalViews.PrescriptionItemView;
import com.hms.interop.client.dto.ClinicalViews.PrescriptionView;
import com.hms.interop.client.dto.ClinicalViews.VitalsView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bundles, asserted structurally with nothing running.
 *
 * <p>This is what makes "these are FHIR R4 bundles" a claim with evidence: the builder is pure, so
 * the shape a receiving system will parse can be checked without a database, a clock or a network.
 * What it cannot check is conformance to the specification's own profiles — nothing here runs an R4
 * validator, and the README says so rather than letting a green test read as certification.
 */
class FhirBundleBuilderTest {

    private final FhirBundleBuilder builder = new FhirBundleBuilder("A test establishment", "T-1");

    private static final UUID PATIENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static PatientView patient() {
        return new PatientView(PATIENT_ID, "MRN-2026-000042", "Asha", "Menon",
                LocalDate.of(1984, 3, 2), "FEMALE", "+91 90000 00000", "asha@example.org",
                "A city", "A state", "India", true, false);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> bundle) {
        return (List<Map<String, Object>>) bundle.get("entry");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resourceOfType(Map<String, Object> bundle, String type) {
        return entries(bundle).stream()
                .map(entry -> (Map<String, Object>) entry.get("resource"))
                .filter(resource -> type.equals(resource.get("resourceType")))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resourcesOfType(Map<String, Object> bundle,
                                                            String type) {
        return entries(bundle).stream()
                .map(entry -> (Map<String, Object>) entry.get("resource"))
                .filter(resource -> type.equals(resource.get("resourceType")))
                .toList();
    }

    // ---- the outpatient consultation ----------------------------------------

    private static EncounterView encounter(NoteView note) {
        return new EncounterView(UUID.fromString("22222222-2222-4222-8222-222222222222"),
                PATIENT_ID, "MRN-2026-000042",
                UUID.fromString("33333333-3333-4333-8333-333333333333"), "GEN", "OUTPATIENT",
                Instant.parse("2026-04-14T09:30:00Z"), Instant.parse("2026-04-14T10:00:00Z"),
                "CLOSED",
                note == null ? List.of() : List.of(note),
                List.of(new VitalsView(UUID.fromString("44444444-4444-4444-8444-444444444444"),
                        Instant.parse("2026-04-14T09:35:00Z"), "nurse.iqbal", 88, 128, 82, 18,
                        new BigDecimal("37.2"), 97, new BigDecimal("64.5"),
                        new BigDecimal("161.0"))),
                List.of(new DiagnosisView(UUID.fromString("55555555-5555-4555-8555-555555555555"),
                        "J06.9", "Acute upper respiratory infection", "PRIMARY")));
    }

    private static NoteView signedNote() {
        return new NoteView(UUID.randomUUID(), 2, "Sore throat for three days.",
                "Pharynx inflamed, no exudate.", "Viral pharyngitis.", "Fluids, review if worse.",
                "dr.rao", true, Instant.parse("2026-04-14T09:55:00Z"), "dr.rao");
    }

    @Test
    @DisplayName("a consultation is a document bundle led by a Composition")
    void consultationIsADocument() {
        Map<String, Object> bundle = builder.opConsultation(patient(), encounter(signedNote()));

        assertThat(bundle.get("resourceType")).isEqualTo("Bundle");
        assertThat(bundle.get("type"))
                .as("a clinical narrative needs an author, a date and a subject, which a bare "
                        + "collection of resources does not have")
                .isEqualTo("document");
        assertThat(bundle.get("timestamp")).isEqualTo("2026-04-14T09:30:00Z");

        Map<String, Object> first = (Map<String, Object>) entries(bundle).get(0).get("resource");
        assertThat(first.get("resourceType"))
                .as("R4 requires the Composition to be the first entry of a document bundle")
                .isEqualTo("Composition");
        assertThat(first.get("status")).isEqualTo("final");
    }

    @Test
    @DisplayName("the note's four sections stay four sections")
    void theNoteKeepsItsSections() {
        Map<String, Object> bundle = builder.opConsultation(patient(), encounter(signedNote()));
        Map<String, Object> composition = resourceOfType(bundle, "Composition");

        List<Map<String, Object>> sections = (List<Map<String, Object>>) composition.get("section");
        assertThat(sections).extracting(section -> section.get("title"))
                .as("what the patient said is not what the examiner found, and a wall of text "
                        + "makes a receiving clinician work out which is which")
                .containsExactly("Subjective", "Objective", "Assessment", "Plan",
                        "Observations and diagnoses");
        assertThat(sections.get(2).toString()).contains("Viral pharyngitis");
    }

    @Test
    @DisplayName("an unsigned note is not exported, and the composition says preliminary")
    void unsignedNotesAreNotSent() {
        NoteView draft = new NoteView(UUID.randomUUID(), 1, "Draft", null, null, null, "dr.rao",
                false, null, null);
        Map<String, Object> bundle = builder.opConsultation(patient(), encounter(draft));
        Map<String, Object> composition = resourceOfType(bundle, "Composition");

        assertThat(composition.get("status")).isEqualTo("preliminary");
        assertThat(composition.toString())
                .as("a draft is an opinion whose author has not finished forming it")
                .doesNotContain("Draft");
    }

    @Test
    @DisplayName("a blood pressure is one Observation with two components, and each other vital its own")
    void vitalsBecomeObservations() {
        Map<String, Object> bundle = builder.opConsultation(patient(), encounter(signedNote()));
        List<Map<String, Object>> observations = resourcesOfType(bundle, "Observation");

        // BP, heart rate, respiratory rate, temperature, saturation, weight, height.
        assertThat(observations).hasSize(7);
        Map<String, Object> bp = observations.stream()
                .filter(o -> o.containsKey("component"))
                .findFirst().orElseThrow();
        assertThat(bp.toString()).contains("8480-6").contains("8462-4").contains("mm[Hg]");
        assertThat(bp.toString()).contains("85354-9");

        assertThat(observations.stream().filter(o -> o.toString().contains("8867-4")).findFirst())
                .as("heart rate, as its own LOINC-coded observation")
                .isPresent();
    }

    @Test
    @DisplayName("the same measurement exported twice carries the same id")
    void observationIdsAreStable() {
        Map<String, Object> first = builder.opConsultation(patient(), encounter(signedNote()));
        Map<String, Object> second = builder.opConsultation(patient(), encounter(signedNote()));

        List<Object> firstIds = resourcesOfType(first, "Observation").stream()
                .map(o -> o.get("id")).toList();
        List<Object> secondIds = resourcesOfType(second, "Observation").stream()
                .map(o -> o.get("id")).toList();
        assertThat(firstIds)
                .as("a random id per export would look like a new observation every time")
                .isEqualTo(secondIds);
    }

    @Test
    @DisplayName("a diagnosis is an ICD-10 coded Condition tied to its encounter")
    void diagnosesBecomeConditions() {
        Map<String, Object> bundle = builder.opConsultation(patient(), encounter(signedNote()));
        Map<String, Object> condition = resourceOfType(bundle, "Condition");

        assertThat(condition.toString()).contains("http://hl7.org/fhir/sid/icd-10");
        assertThat(condition.toString()).contains("J06.9");
        assertThat(condition.get("encounter").toString())
                .contains("Encounter/22222222-2222-4222-8222-222222222222");
    }

    // ---- the laboratory report ----------------------------------------------

    private static LabOrderView order(LabResultView... results) {
        return new LabOrderView(UUID.fromString("66666666-6666-4666-8666-666666666666"),
                PATIENT_ID, "MRN-2026-000042", "dr.rao", null, "ROUTINE", "VERIFIED",
                Instant.parse("2026-04-14T08:00:00Z"),
                List.of(new LabOrderItemView(UUID.randomUUID(), "CBC", "Complete blood count")),
                List.of(results));
    }

    @Test
    @DisplayName("a report carries its observations, their ranges and their flags")
    void reportCarriesRangesAndFlags() {
        LabResultView low = new LabResultView(UUID.randomUUID(), "HGB", "Haemoglobin", "9.4",
                "g/dL", new BigDecimal("12.0"), new BigDecimal("15.5"), "12.0 - 15.5", "LOW", true,
                "VERIFIED", "dr.pathan", Instant.parse("2026-04-14T11:00:00Z"));
        Map<String, Object> bundle = builder.diagnosticReport(patient(), order(low));

        Map<String, Object> report = resourceOfType(bundle, "DiagnosticReport");
        assertThat(report.get("status")).isEqualTo("final");
        assertThat(report.get("issued")).isEqualTo("2026-04-14T11:00:00Z");
        assertThat(report.toString()).contains("Complete blood count");

        Map<String, Object> observation = resourceOfType(bundle, "Observation");
        assertThat(observation.get("valueQuantity").toString()).contains("9.4").contains("g/dL");
        assertThat(observation.get("referenceRange").toString()).contains("12.0").contains("15.5");
        assertThat(observation.get("interpretation").toString())
                .as("an abnormal result must say so in a code a receiving system can act on")
                .contains("code=L")
                .contains("v3-ObservationInterpretation");
        assertThat(report.get("result").toString()).contains("Observation/" + observation.get("id"));
    }

    @Test
    @DisplayName("a result that is words stays words rather than being coerced to a number")
    void textResultsStayText() {
        LabResultView morphology = new LabResultView(UUID.randomUUID(), "RBC_MORPH",
                "Red cell morphology", "Microcytic, hypochromic", null, null, null, null, null,
                false, "VERIFIED", "dr.pathan", Instant.parse("2026-04-14T11:00:00Z"));
        Map<String, Object> bundle = builder.diagnosticReport(patient(), order(morphology));

        Map<String, Object> observation = resourceOfType(bundle, "Observation");
        assertThat(observation).containsKey("valueString");
        assertThat(observation).doesNotContainKey("valueQuantity");
        assertThat(observation.get("valueString")).isEqualTo("Microcytic, hypochromic");
    }

    @Test
    @DisplayName("a local code is presented as local rather than dressed up as LOINC")
    void localCodesSayTheyAreLocal() {
        LabResultView result = new LabResultView(UUID.randomUUID(), "HGB", "Haemoglobin", "13.0",
                "g/dL", null, null, null, null, false, "VERIFIED", "dr.pathan", Instant.now());
        Map<String, Object> bundle = builder.diagnosticReport(patient(), order(result));

        assertThat(resourceOfType(bundle, "Observation").get("code").toString())
                .as("a receiving system can map a code it knows is local, and cannot unmap one it "
                        + "was told was standard")
                .contains("urn:medsync:lab-parameter");
    }

    // ---- the prescription ----------------------------------------------------

    @Test
    @DisplayName("each prescribed line is a MedicationRequest carrying the dose as written")
    void prescriptionBecomesMedicationRequests() {
        PrescriptionView prescription = new PrescriptionView(UUID.randomUUID(), null, PATIENT_ID,
                "MRN-2026-000042", "dr.rao", "ACTIVE", Instant.parse("2026-04-14T10:05:00Z"),
                List.of(new PrescriptionItemView(UUID.randomUUID(), "PARA500",
                                "Paracetamol 500 mg tablet", "1 tablet", "four times daily", 3, 12,
                                "After food"),
                        new PrescriptionItemView(UUID.randomUUID(), "AMOX500",
                                "Amoxicillin 500 mg capsule", "1 capsule", "three times daily", 5,
                                15, null)));

        Map<String, Object> bundle = builder.prescription(patient(), prescription);
        List<Map<String, Object>> requests = resourcesOfType(bundle, "MedicationRequest");

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).get("status")).isEqualTo("active");
        assertThat(requests.get(0).get("intent")).isEqualTo("order");
        assertThat(requests.get(0).get("dosageInstruction").toString())
                .contains("1 tablet four times daily")
                .contains("After food");
        assertThat(requests.get(0).get("dispenseRequest").toString()).contains("12");
        assertThat(requests.get(0).get("medicationCodeableConcept").toString())
                .as("the formulary's codes are this deployment's own, and pretending they are "
                        + "SNOMED would be worse than saying they are local")
                .contains("urn:medsync:formulary");
    }

    @Test
    @DisplayName("a cancelled prescription says cancelled, never draft")
    void cancelledIsCancelled() {
        PrescriptionView cancelled = new PrescriptionView(UUID.randomUUID(), null, PATIENT_ID,
                "MRN-2026-000042", "dr.rao", "CANCELLED", Instant.now(),
                List.of(new PrescriptionItemView(UUID.randomUUID(), "PARA500", "Paracetamol",
                        "1 tablet", "daily", 1, 1, null)));

        assertThat(resourceOfType(builder.prescription(patient(), cancelled), "MedicationRequest")
                .get("status")).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("every bundle identifies the patient by MRN and never by a national identifier")
    void patientIsIdentifiedByMrnOnly() {
        Map<String, Object> bundle = builder.opConsultation(patient(), encounter(signedNote()));
        Map<String, Object> resource = resourceOfType(bundle, "Patient");

        assertThat(resource.get("identifier").toString()).contains("MRN-2026-000042");
        assertThat(bundle.toString())
                .as("an ABDM push addresses a patient at the gateway; a bundle carrying the ABHA "
                        + "number too would put a national identifier in every payload for nothing")
                .doesNotContain("abha");
        assertThat(resource.get("gender")).isEqualTo("female");
        assertThat(resource.get("birthDate")).isEqualTo("1984-03-02");
    }
}
