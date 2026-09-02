package com.hms.apitests.journey;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.RequiresRunningStack;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * One patient, from the front desk to a signed note and a lab order, across four services and the
 * gateway.
 *
 * <p>Ordered on purpose. This is not a set of independent unit tests pretending to be a suite -
 * it is a single journey, and each step depends on the last exactly as the real workflow does.
 * The value is in the seams: the appointment carries a risk score produced by a Python service,
 * the encounter is opened against an appointment owned by another service, and the lab order
 * refers to a patient this service has never stored. Every one of those crosses a process
 * boundary that no in-process test exercises.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("clinical journey across the gateway")
class ClinicalJourneyIT extends RequiresRunningStack {

    private static Fixtures.Patient patient;
    private static Fixtures.Clinician clinician;
    private static String appointmentId;
    private static String encounterId;
    private static String labOrderId;

    @Test
    @Order(1)
    @DisplayName("the front desk registers a patient and it is findable by search and by MRN")
    void registerPatient() {
        patient = Fixtures.registerPatient(Api.RECEPTIONIST, "Journey");
        assertThat(patient.mrn()).isNotBlank();

        given().spec(Api.as(Api.DOCTOR))
                .queryParam("q", patient.mrn())
                .when().get("/patients")
                .then().statusCode(200)
                .body("totalElements", equalTo(1))
                .body("content[0].mrn", equalTo(patient.mrn()));

        given().spec(Api.as(Api.DOCTOR))
                .when().get("/patients/by-mrn/{mrn}", patient.mrn())
                .then().statusCode(200)
                .body("id", equalTo(patient.id()));
    }

    @Test
    @Order(2)
    @DisplayName("booking returns a no-show risk score produced by the AI service")
    void bookAppointment() {
        clinician = Fixtures.clinician();

        JsonPath booked = given().spec(Api.as(Api.RECEPTIONIST))
                .body(bookingBody(0))
                .when().post("/appointments")
                .then().statusCode(201)
                .body("status", equalTo("BOOKED"))
                .body("patientMrn", equalTo(patient.mrn()))
                .extract().jsonPath();

        appointmentId = booked.getString("id");

        // The score is allowed to be absent - the circuit breaker returns null rather than
        // failing the booking when the AI service is down, and that is a designed outcome, not a
        // defect. What must hold is that when a score IS returned it is a sane one.
        // Read through get() rather than getDouble(): the typed accessor unboxes, so it throws a
        // NullPointerException on the very absence this block exists to tolerate. That is exactly
        // what happened the first time the suite ran with the AI service down - a designed
        // outcome reported as a test error.
        Number scored = booked.get("noShowRisk.score");
        if (scored != null) {
            double score = scored.doubleValue();
            assertThat(score).isBetween(0.0d, 1.0d);
            assertThat(booked.getString("noShowRisk.band")).isIn("LOW", "MEDIUM", "HIGH");
        }
    }

    @Test
    @Order(3)
    @DisplayName("the double-booking guard rejects an overlap and allows the adjacent slot")
    void doubleBookingIsRejected() {
        // Same clinician, same minute: the (clinician_id, tstzrange) exclusion constraint must
        // refuse this, and must do so as a 409 rather than a 500.
        given().spec(Api.as(Api.RECEPTIONIST))
                .body(bookingBody(0))
                .when().post("/appointments")
                .then().statusCode(409);

        // Immediately after the first appointment ends: no overlap, so this must succeed.
        given().spec(Api.as(Api.RECEPTIONIST))
                .body(bookingBody(15))
                .when().post("/appointments")
                .then().statusCode(201);
    }

    @Test
    @Order(4)
    @DisplayName("check-in and encounter open move the appointment through its status machine")
    void openEncounter() {
        given().spec(Api.as(Api.RECEPTIONIST))
                .when().post("/appointments/{id}/check-in", appointmentId)
                .then().statusCode(200)
                .body("status", equalTo("CHECKED_IN"))
                .body("checkedInAt", notNullValue());

        encounterId = given().spec(Api.as(Api.DOCTOR))
                .body(Map.of(
                        "appointmentId", appointmentId,
                        "patientId", patient.id(),
                        "patientMrn", patient.mrn(),
                        "clinicianId", clinician.id(),
                        "departmentCode", clinician.departmentCode(),
                        "encounterType", "OUTPATIENT"))
                .when().post("/encounters")
                .then().statusCode(201)
                .body("patientMrn", equalTo(patient.mrn()))
                .extract().jsonPath().getString("id");
    }

    @Test
    @Order(5)
    @DisplayName("vitals and a SOAP note are recorded, and the AI service summarises the note")
    void chartTheEncounter() {
        given().spec(Api.as(Api.NURSE))
                .body(Map.of("heartRate", 92, "systolicBp", 138, "diastolicBp", 88,
                        "temperatureC", 37.8, "respiratoryRate", 18, "oxygenSaturation", 97))
                .when().post("/encounters/{id}/vitals", encounterId)
                .then().statusCode(201);

        String subjective = "Patient reports three days of productive cough and intermittent fever. "
                + "No chest pain, no shortness of breath at rest.";
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of(
                        "subjective", subjective,
                        "objective", "Temp 37.8, HR 92, BP 138/88. Coarse crackles at the right base.",
                        "assessment", "Community-acquired pneumonia, likely bacterial.",
                        "plan", "Amoxicillin 500mg TDS for 5 days. Review in 72 hours."))
                .when().put("/encounters/{id}/note", encounterId)
                .then().statusCode(200)
                .body("revision", equalTo(1));

        // The AI summary is a separate service reached through the same gateway and the same
        // token. It has a deterministic fallback, so this must work with no API key configured.
        var summary = given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("note_text", subjective, "patient_age", 41,
                        "patient_sex", "FEMALE", "encounter_type", "OUTPATIENT"))
                .when().post("/ai/notes/summarize")
                .then().statusCode(200)
                .body("result.summary", notNullValue())
                .body("provenance.model", notNullValue())
                // Every AI response carries its disclaimer. A summary that reaches a chart
                // without one is indistinguishable from something a clinician wrote.
                .body("provenance.disclaimer", org.hamcrest.Matchers.containsString(
                        "Clinical decision support only"))
                .extract().jsonPath();

        // The note above says "No chest pain, no shortness of breath". A red-flag extractor that
        // matches substrings reports both - the opposite of what the clinician wrote, and the
        // fastest way to make people stop reading the field. Asserted here because this journey
        // is where it was found.
        assertThat(summary.getList("result.red_flags", String.class))
                .as("a symptom the note explicitly excludes must not be raised as a red flag")
                .doesNotContain("chest pain", "shortness of breath");

        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("text", "community acquired pneumonia", "max_suggestions", 5))
                .when().post("/ai/icd10/suggest")
                .then().statusCode(200)
                .body("suggestions.size()", org.hamcrest.Matchers.greaterThan(0))
                .body("suggestions[0].code", notNullValue())
                .body("provenance.disclaimer", notNullValue());
    }

    @Test
    @Order(6)
    @DisplayName("a signed note is immutable: further edits become a new revision, not an overwrite")
    void signAndAmendTheNote() {
        given().spec(Api.as(Api.DOCTOR))
                .when().post("/encounters/{id}/note/sign", encounterId)
                .then().statusCode(200)
                .body("signedAt", notNullValue());

        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("plan", "Amoxicillin 500mg TDS for 7 days after review of sputum culture."))
                .when().put("/encounters/{id}/note", encounterId)
                .then().statusCode(200)
                .body("revision", equalTo(2));

        List<Integer> revisions = given().spec(Api.as(Api.DOCTOR))
                .when().get("/encounters/{id}/note/history", encounterId)
                .then().statusCode(200)
                .extract().jsonPath().getList("revision", Integer.class);

        assertThat(revisions)
                .as("both revisions must survive - an amended clinical note never replaces the original")
                .contains(1, 2);
    }

    @Test
    @Order(7)
    @DisplayName("a lab order is raised for a patient this service has never stored")
    void orderLabWork() {
        String testCode = given().spec(Api.as(Api.DOCTOR))
                .when().get("/lab/catalog")
                .then().statusCode(200)
                .extract().jsonPath().getString("[0].code");

        labOrderId = given().spec(Api.as(Api.DOCTOR))
                .body(Map.of(
                        "patientId", patient.id(),
                        "patientMrn", patient.mrn(),
                        "patientSex", "F",
                        "testCodes", List.of(testCode),
                        "priority", "ROUTINE",
                        "clinicalNotes", "Query pneumonia"))
                .when().post("/lab/orders")
                .then().statusCode(201)
                .body("patientMrn", equalTo(patient.mrn()))
                .extract().jsonPath().getString("id");

        given().spec(Api.as(Api.LAB_TECH))
                .queryParam("mrn", patient.mrn())
                .when().get("/lab/orders")
                .then().statusCode(200)
                .body("content[0].id", equalTo(labOrderId));
    }

    @Test
    @Order(8)
    @DisplayName("an encounter cannot be closed while its latest note revision is unsigned")
    void closeIsRefusedWhileTheAddendumIsUnsigned() {
        // Step 6 left revision 2 as an unsigned addendum. Closing here must be refused: a closed
        // encounter with an unsigned note is a record nobody has attested to.
        given().spec(Api.as(Api.DOCTOR))
                .when().post("/encounters/{id}/close", encounterId)
                .then().statusCode(409);
    }

    @Test
    @Order(9)
    @DisplayName("closing the encounter completes the appointment in the other service")
    void closeTheEncounter() {
        given().spec(Api.as(Api.DOCTOR))
                .when().post("/encounters/{id}/note/sign", encounterId)
                .then().statusCode(200);

        given().spec(Api.as(Api.DOCTOR))
                .when().post("/encounters/{id}/close", encounterId)
                .then().statusCode(200);

        given().spec(Api.as(Api.DOCTOR))
                .when().get("/appointments/{id}", appointmentId)
                .then().statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("encounterId", equalTo(encounterId));
    }

    private Map<String, Object> bookingBody(int minuteOffset) {
        return Map.of(
                "patientId", patient.id(),
                "patientMrn", patient.mrn(),
                "clinicianId", clinician.id(),
                "clinicianName", clinician.fullName(),
                "departmentCode", clinician.departmentCode(),
                "startsAt", Fixtures.slot(minuteOffset).toString(),
                "durationMinutes", 15,
                "priority", "ROUTINE",
                "reason", "API journey");
    }
}
