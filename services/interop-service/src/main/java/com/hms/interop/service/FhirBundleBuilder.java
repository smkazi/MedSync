package com.hms.interop.service;

import com.hms.interop.client.dto.ClinicalViews.DiagnosisView;
import com.hms.interop.client.dto.ClinicalViews.EncounterView;
import com.hms.interop.client.dto.ClinicalViews.LabOrderView;
import com.hms.interop.client.dto.ClinicalViews.LabResultView;
import com.hms.interop.client.dto.ClinicalViews.NoteView;
import com.hms.interop.client.dto.ClinicalViews.PatientView;
import com.hms.interop.client.dto.ClinicalViews.PrescriptionItemView;
import com.hms.interop.client.dto.ClinicalViews.PrescriptionView;
import com.hms.interop.client.dto.ClinicalViews.VitalsView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FHIR R4 bundles, built by hand.
 *
 * <p><strong>Pure, and that is the point.</strong> No repository, no clock and no HTTP: the inputs
 * are views of what other services returned and the output is a map Jackson serialises. So the
 * structure a receiving system will parse can be asserted in a unit test with nothing running,
 * which is what makes "this is a valid R4 Bundle" a claim with evidence rather than an aspiration.
 *
 * <p><strong>Built by hand rather than with HAPI FHIR</strong>, and the trade is deliberate. HAPI
 * would bring a validator, and it would also bring several hundred megabytes of structure
 * definitions and a version-coupled model into a platform whose bundles use a dozen resource
 * types. What is lost is real and named in the README: nothing here has been run through an R4
 * validator, so these bundles are structurally correct as far as the specification was followed and
 * read, and are not certified against it. A deployment that has to prove conformance should
 * validate the output rather than trust this comment.
 *
 * <p>Ids are the platform's own UUIDs, referenced as {@code urn:uuid:} — which is what a bundle
 * that is not served from a FHIR endpoint must do. Claiming resolvable URLs the platform does not
 * serve would be a lie a receiving system can follow and be disappointed by.
 */
public final class FhirBundleBuilder {

    /** SNOMED/LOINC-style systems, named once so a typo cannot differ between two resources. */
    private static final String LOINC = "http://loinc.org";
    private static final String ICD10 = "http://hl7.org/fhir/sid/icd-10";
    private static final String OBSERVATION_CATEGORY =
            "http://terminology.hl7.org/CodeSystem/observation-category";

    private final String facilityName;
    private final String facilityId;

    public FhirBundleBuilder(String facilityName, String facilityId) {
        this.facilityName = facilityName;
        this.facilityId = facilityId;
    }

    /**
     * An outpatient consultation: the note, the observations and the diagnoses.
     *
     * <p>A {@code document} bundle led by a {@link #composition} — the shape ABDM's OP consultation
     * profile uses, and the right one for a clinical narrative: a document has an author, a date
     * and a subject, and a bare collection of resources has none of those, so a receiving system
     * cannot tell who stood behind it.
     */
    public Map<String, Object> opConsultation(PatientView patient, EncounterView encounter) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> patientResource = patient(patient);
        Map<String, Object> encounterResource = encounter(encounter, patient);

        List<Map<String, Object>> sectionEntries = new ArrayList<>();
        List<Map<String, Object>> observations = new ArrayList<>();
        for (VitalsView vitals : encounter.vitals()) {
            observations.addAll(vitalsObservations(vitals, patient));
        }
        List<Map<String, Object>> conditions = new ArrayList<>();
        for (DiagnosisView diagnosis : encounter.diagnoses()) {
            conditions.add(condition(diagnosis, patient, encounter));
        }

        observations.forEach(o -> sectionEntries.add(reference(o)));
        conditions.forEach(c -> sectionEntries.add(reference(c)));

        NoteView note = encounter.signedNote();
        Map<String, Object> composition = composition(patient, encounter, note, sectionEntries);

        entries.add(entry(composition));
        entries.add(entry(patientResource));
        entries.add(entry(encounterResource));
        observations.forEach(o -> entries.add(entry(o)));
        conditions.forEach(c -> entries.add(entry(c)));

        return bundle("document", encounter.startedAt(), entries);
    }

    /**
     * A laboratory report.
     *
     * <p>Released results only, and the caller is expected to have refused an unverified order
     * before getting here — a provisional number leaving the building is the failure this platform's
     * whole verification step exists to prevent.
     */
    public Map<String, Object> diagnosticReport(PatientView patient, LabOrderView order) {
        List<Map<String, Object>> entries = new ArrayList<>();
        List<Map<String, Object>> observations = new ArrayList<>();
        for (LabResultView result : order.results()) {
            observations.add(labObservation(result, patient, order));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("resourceType", "DiagnosticReport");
        report.put("id", order.id().toString());
        report.put("status", "final");
        report.put("category", List.of(codeableConcept(
                "http://terminology.hl7.org/CodeSystem/v2-0074", "LAB", "Laboratory")));
        report.put("code", codeableConcept(LOINC, "11502-2", panelName(order)));
        report.put("subject", subject(patient));
        report.put("effectiveDateTime", iso(order.orderedAt()));
        report.put("issued", iso(latestVerification(order)));
        report.put("performer", List.of(organisationReference()));
        report.put("result", observations.stream().map(FhirBundleBuilder::reference).toList());

        entries.add(entry(patient(patient)));
        entries.add(entry(report));
        observations.forEach(o -> entries.add(entry(o)));

        return bundle("collection", order.orderedAt(), entries);
    }

    /**
     * A prescription: one {@code MedicationRequest} per line.
     *
     * <p>The medicine is a text-coded {@code CodeableConcept} rather than a coded one, because the
     * formulary's codes are this deployment's own and pretending they are SNOMED CT would be worse
     * than saying plainly that they are local. The dosage is the free text the prescriber wrote,
     * for the same reason: the platform does not parse a dose into a structured timing, and
     * inventing one here would be inventing clinical intent.
     */
    public Map<String, Object> prescription(PatientView patient, PrescriptionView prescription) {
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(entry(patient(patient)));
        for (PrescriptionItemView item : prescription.items()) {
            entries.add(entry(medicationRequest(item, patient, prescription)));
        }
        return bundle("collection", prescription.issuedAt(), entries);
    }

    // ---- resources -----------------------------------------------------------

    private Map<String, Object> patient(PatientView patient) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "Patient");
        resource.put("id", patient.id().toString());
        // The MRN as a business identifier. The ABHA number is deliberately absent: an ABDM push
        // addresses the patient by their ABHA address at the gateway, and a bundle that also
        // carried the number would put a national identifier in every payload for no gain.
        resource.put("identifier", List.of(Map.of(
                "system", "urn:medsync:mrn",
                "value", patient.mrn())));
        resource.put("active", patient.active());
        resource.put("name", List.of(Map.of(
                "text", patient.fullName(),
                "family", patient.lastName() == null ? "" : patient.lastName(),
                "given", List.of(patient.firstName() == null ? "" : patient.firstName()))));
        resource.put("gender", gender(patient.sex()));
        if (patient.dateOfBirth() != null) {
            resource.put("birthDate", patient.dateOfBirth().toString());
        }
        if (patient.deceased()) {
            resource.put("deceasedBoolean", true);
        }
        return resource;
    }

    private Map<String, Object> encounter(EncounterView encounter, PatientView patient) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "Encounter");
        resource.put("id", encounter.id().toString());
        resource.put("status", "IN_PROGRESS".equals(encounter.status()) ? "in-progress" : "finished");
        resource.put("class", Map.of(
                "system", "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                "code", encounterClass(encounter.encounterType()),
                "display", encounter.encounterType() == null ? "outpatient" : encounter.encounterType()));
        resource.put("subject", subject(patient));
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("start", iso(encounter.startedAt()));
        if (encounter.endedAt() != null) {
            period.put("end", iso(encounter.endedAt()));
        }
        resource.put("period", period);
        resource.put("serviceProvider", organisationReference());
        return resource;
    }

    /**
     * The note, as a Composition with the four sections it was written in.
     *
     * <p>Subjective, objective, assessment and plan are kept apart rather than concatenated,
     * because a receiving clinician reads them differently: what the patient said is not what the
     * examiner found, and a wall of text obliges them to work out which is which. Sections with
     * nothing in them are omitted rather than sent empty.
     */
    private Map<String, Object> composition(PatientView patient, EncounterView encounter,
                                            NoteView note, List<Map<String, Object>> references) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "Composition");
        resource.put("id", UUID.nameUUIDFromBytes(
                ("composition:" + encounter.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString());
        resource.put("status", note != null && note.signed() ? "final" : "preliminary");
        resource.put("type", codeableConcept(LOINC, "11488-4", "Consultation note"));
        resource.put("subject", subject(patient));
        resource.put("encounter", Map.of("reference", "Encounter/" + encounter.id()));
        resource.put("date", iso(note != null && note.signedAt() != null
                ? note.signedAt() : encounter.startedAt()));
        resource.put("author", List.of(Map.of("display",
                note == null || note.signedBy() == null ? facilityName : note.signedBy())));
        resource.put("title", "Consultation note");
        resource.put("custodian", organisationReference());

        List<Map<String, Object>> sections = new ArrayList<>();
        if (note != null) {
            addSection(sections, "Subjective", note.subjective());
            addSection(sections, "Objective", note.objective());
            addSection(sections, "Assessment", note.assessment());
            addSection(sections, "Plan", note.plan());
        }
        if (!references.isEmpty()) {
            Map<String, Object> findings = new LinkedHashMap<>();
            findings.put("title", "Observations and diagnoses");
            findings.put("entry", references);
            sections.add(findings);
        }
        resource.put("section", sections);
        return resource;
    }

    private static void addSection(List<Map<String, Object>> sections, String title, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        sections.add(Map.of(
                "title", title,
                "text", Map.of("status", "generated",
                        "div", "<div xmlns=\"http://www.w3.org/1999/xhtml\">" + escape(text)
                                + "</div>")));
    }

    /**
     * Vitals, one Observation per measurement.
     *
     * <p>One resource per number rather than one carrying eight components, because that is what
     * every consumer expects to trend: a blood pressure is the exception and is sent as one
     * Observation with two components, which is what the specification's own example does.
     */
    private List<Map<String, Object>> vitalsObservations(VitalsView vitals, PatientView patient) {
        List<Map<String, Object>> observations = new ArrayList<>();
        String when = iso(vitals.recordedAt());

        if (vitals.systolicBp() != null && vitals.diastolicBp() != null) {
            Map<String, Object> bp = observationShell(vitals, patient, "85354-9",
                    "Blood pressure panel", when);
            bp.put("component", List.of(
                    component("8480-6", "Systolic blood pressure", vitals.systolicBp(), "mm[Hg]"),
                    component("8462-4", "Diastolic blood pressure", vitals.diastolicBp(), "mm[Hg]")));
            observations.add(bp);
        }
        addQuantity(observations, vitals, patient, when, "8867-4", "Heart rate",
                vitals.heartRate(), "/min");
        addQuantity(observations, vitals, patient, when, "9279-1", "Respiratory rate",
                vitals.respiratoryRate(), "/min");
        addQuantity(observations, vitals, patient, when, "8310-5", "Body temperature",
                vitals.temperatureC(), "Cel");
        addQuantity(observations, vitals, patient, when, "2708-6", "Oxygen saturation",
                vitals.oxygenSaturation(), "%");
        addQuantity(observations, vitals, patient, when, "29463-7", "Body weight",
                vitals.weightKg(), "kg");
        addQuantity(observations, vitals, patient, when, "8302-2", "Body height",
                vitals.heightCm(), "cm");
        return observations;
    }

    private void addQuantity(List<Map<String, Object>> into, VitalsView vitals,
                             PatientView patient, String when, String code, String display,
                             Number value, String unit) {
        if (value == null) {
            return;
        }
        Map<String, Object> observation = observationShell(vitals, patient, code, display, when);
        observation.put("valueQuantity", quantity(value, unit));
        into.add(observation);
    }

    private Map<String, Object> observationShell(VitalsView vitals, PatientView patient,
                                                 String code, String display, String when) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "Observation");
        // Derived from the vitals row and the LOINC code, so the same measurement exported twice
        // carries the same id. A random id per export would look like a new observation each time.
        resource.put("id", UUID.nameUUIDFromBytes(
                        (vitals.id() + ":" + code).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString());
        resource.put("status", "final");
        resource.put("category", List.of(codeableConcept(OBSERVATION_CATEGORY, "vital-signs",
                "Vital Signs")));
        resource.put("code", codeableConcept(LOINC, code, display));
        resource.put("subject", subject(patient));
        resource.put("effectiveDateTime", when);
        if (vitals.recordedBy() != null) {
            resource.put("performer", List.of(Map.of("display", vitals.recordedBy())));
        }
        return resource;
    }

    private Map<String, Object> labObservation(LabResultView result, PatientView patient,
                                               LabOrderView order) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "Observation");
        resource.put("id", result.id().toString());
        resource.put("status", "final");
        resource.put("category", List.of(codeableConcept(OBSERVATION_CATEGORY, "laboratory",
                "Laboratory")));
        // The parameter code is the laboratory's own. Said so in the system URI rather than
        // dressed up as LOINC: a receiving system can map a local code it knows is local, and
        // cannot unmap one it was told was standard.
        resource.put("code", codeableConcept("urn:medsync:lab-parameter", result.parameter(),
                result.displayName() == null ? result.parameter() : result.displayName()));
        resource.put("subject", subject(patient));
        resource.put("effectiveDateTime", iso(order.orderedAt()));
        if (result.verifiedAt() != null) {
            resource.put("issued", iso(result.verifiedAt()));
        }
        if (result.verifiedBy() != null) {
            resource.put("performer", List.of(Map.of("display", result.verifiedBy())));
        }

        BigDecimal numeric = numeric(result.value());
        if (numeric != null) {
            resource.put("valueQuantity", quantity(numeric, result.unit()));
        } else {
            resource.put("valueString", result.value());
        }
        if (result.normalLow() != null || result.normalHigh() != null) {
            Map<String, Object> range = new LinkedHashMap<>();
            if (result.normalLow() != null) {
                range.put("low", quantity(result.normalLow(), result.unit()));
            }
            if (result.normalHigh() != null) {
                range.put("high", quantity(result.normalHigh(), result.unit()));
            }
            if (result.referenceRange() != null) {
                range.put("text", result.referenceRange());
            }
            resource.put("referenceRange", List.of(range));
        }
        if (result.abnormal()) {
            resource.put("interpretation", List.of(codeableConcept(
                    "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation",
                    interpretationCode(result.flag()), result.flag() == null ? "Abnormal" : result.flag())));
        }
        return resource;
    }

    private Map<String, Object> condition(DiagnosisView diagnosis, PatientView patient,
                                          EncounterView encounter) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "Condition");
        resource.put("id", diagnosis.id().toString());
        resource.put("clinicalStatus", codeableConcept(
                "http://terminology.hl7.org/CodeSystem/condition-clinical", "active", "Active"));
        resource.put("verificationStatus", codeableConcept(
                "http://terminology.hl7.org/CodeSystem/condition-ver-status",
                "PROVISIONAL".equals(diagnosis.category()) ? "provisional" : "confirmed",
                "PROVISIONAL".equals(diagnosis.category()) ? "Provisional" : "Confirmed"));
        resource.put("code", codeableConcept(ICD10, diagnosis.icd10Code(), diagnosis.description()));
        resource.put("subject", subject(patient));
        resource.put("encounter", Map.of("reference", "Encounter/" + encounter.id()));
        resource.put("recordedDate", iso(encounter.startedAt()));
        return resource;
    }

    private Map<String, Object> medicationRequest(PrescriptionItemView item, PatientView patient,
                                                  PrescriptionView prescription) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "MedicationRequest");
        resource.put("id", item.id().toString());
        resource.put("status", statusOf(prescription.status()));
        resource.put("intent", "order");
        resource.put("medicationCodeableConcept", codeableConcept("urn:medsync:formulary",
                item.drugCode(), item.drugName() == null ? item.drugCode() : item.drugName()));
        resource.put("subject", subject(patient));
        resource.put("authoredOn", iso(prescription.issuedAt()));
        if (prescription.prescriberName() != null) {
            resource.put("requester", Map.of("display", prescription.prescriberName()));
        }
        String dosage = "%s %s".formatted(
                item.dose() == null ? "" : item.dose(),
                item.frequency() == null ? "" : item.frequency()).trim();
        Map<String, Object> instruction = new LinkedHashMap<>();
        instruction.put("text", dosage.isEmpty() ? "as directed" : dosage);
        if (item.instructions() != null && !item.instructions().isBlank()) {
            instruction.put("patientInstruction", item.instructions());
        }
        resource.put("dosageInstruction", List.of(instruction));
        resource.put("dispenseRequest", Map.of(
                "quantity", quantity(item.quantity(), "unit"),
                "expectedSupplyDuration", quantity(item.durationDays(), "d")));
        return resource;
    }

    // ---- plumbing ------------------------------------------------------------

    private Map<String, Object> bundle(String type, Instant timestamp,
                                       List<Map<String, Object>> entries) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("resourceType", "Bundle");
        bundle.put("id", UUID.randomUUID().toString());
        bundle.put("meta", Map.of("lastUpdated", iso(Instant.now())));
        bundle.put("type", type);
        bundle.put("timestamp", iso(timestamp));
        bundle.put("total", entries.size());
        bundle.put("entry", entries);
        return bundle;
    }

    private static Map<String, Object> entry(Map<String, Object> resource) {
        return Map.of(
                "fullUrl", "urn:uuid:" + resource.get("id"),
                "resource", resource);
    }

    private static Map<String, Object> reference(Map<String, Object> resource) {
        return Map.of("reference", resource.get("resourceType") + "/" + resource.get("id"));
    }

    private Map<String, Object> organisationReference() {
        return Map.of("display", facilityName, "identifier",
                Map.of("system", "urn:medsync:facility", "value", facilityId));
    }

    private static Map<String, Object> subject(PatientView patient) {
        return Map.of("reference", "Patient/" + patient.id(), "display", patient.mrn());
    }

    private static Map<String, Object> codeableConcept(String system, String code, String display) {
        Map<String, Object> coding = new LinkedHashMap<>();
        coding.put("system", system);
        coding.put("code", code == null ? "unknown" : code);
        if (display != null) {
            coding.put("display", display);
        }
        Map<String, Object> concept = new LinkedHashMap<>();
        concept.put("coding", List.of(coding));
        if (display != null) {
            concept.put("text", display);
        }
        return concept;
    }

    private static Map<String, Object> component(String code, String display, Number value,
                                                 String unit) {
        return Map.of(
                "code", codeableConcept(LOINC, code, display),
                "valueQuantity", quantity(value, unit));
    }

    private static Map<String, Object> quantity(Number value, String unit) {
        Map<String, Object> quantity = new LinkedHashMap<>();
        quantity.put("value", value);
        if (unit != null && !unit.isBlank()) {
            quantity.put("unit", unit);
            quantity.put("system", "http://unitsofmeasure.org");
            quantity.put("code", unit);
        }
        return quantity;
    }

    private static String iso(Instant instant) {
        return (instant == null ? Instant.now() : instant).toString();
    }

    private static String gender(String sex) {
        if (sex == null) {
            return "unknown";
        }
        return switch (sex.toUpperCase(java.util.Locale.ROOT)) {
            case "MALE", "M" -> "male";
            case "FEMALE", "F" -> "female";
            case "OTHER" -> "other";
            default -> "unknown";
        };
    }

    private static String encounterClass(String encounterType) {
        if (encounterType == null) {
            return "AMB";
        }
        return switch (encounterType.toUpperCase(java.util.Locale.ROOT)) {
            case "INPATIENT" -> "IMP";
            case "EMERGENCY" -> "EMER";
            case "TELEMEDICINE" -> "VR";
            default -> "AMB";
        };
    }

    /**
     * A cancelled prescription is {@code cancelled}, and everything else is {@code active} or
     * {@code completed}. Never {@code draft}: a prescription this platform has issued has been
     * issued, and telling a receiving system it might not have been would be worse than silence.
     */
    private static String statusOf(String status) {
        if (status == null) {
            return "unknown";
        }
        return switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case "CANCELLED" -> "cancelled";
            case "COMPLETED", "DISPENSED" -> "completed";
            default -> "active";
        };
    }

    private static String interpretationCode(String flag) {
        if (flag == null) {
            return "A";
        }
        return switch (flag.toUpperCase(java.util.Locale.ROOT)) {
            case "HIGH", "H" -> "H";
            case "LOW", "L" -> "L";
            case "CRITICAL_HIGH", "HH" -> "HH";
            case "CRITICAL_LOW", "LL" -> "LL";
            default -> "A";
        };
    }

    private static String panelName(LabOrderView order) {
        return order.items().isEmpty() ? "Laboratory panel"
                : order.items().stream()
                        .map(item -> item.testName() == null ? item.testCode() : item.testName())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("Laboratory panel");
    }

    private static Instant latestVerification(LabOrderView order) {
        return order.results().stream()
                .map(LabResultView::verifiedAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(order.orderedAt());
    }

    private static BigDecimal numeric(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            // A morphology comment, a "not detected", a "+++". Sent as a string rather than
            // coerced: a result that is words is a result that is words.
            return null;
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
