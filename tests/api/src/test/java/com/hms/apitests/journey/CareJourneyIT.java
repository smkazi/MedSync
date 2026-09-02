package com.hms.apitests.journey;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.RequiresRunningStack;
import io.restassured.path.json.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Order sets and care plans through the gateway.
 *
 * <p>This is the only place the order-set saga is exercised for real. scheduling-service's own
 * suite stubs the client that reaches the other two services — it has to, since neither is running
 * beside it — so "applying a set raises a laboratory order in one service and a prescription in
 * another, with the clinician's own token, and either raises both or leaves nothing behind" is
 * proven here and nowhere else.
 */
@DisplayName("order sets and care plans")
class CareJourneyIT extends RequiresRunningStack {

    private static Fixtures.Patient patient;
    private static Fixtures.Clinician clinician;
    private static String appointmentId;

    @BeforeAll
    static void seed() {
        patient = Fixtures.registerPatient(Api.RECEPTIONIST, "Care");
        clinician = Fixtures.clinician();
    }

    /** A fresh open encounter. Order sets and care plans both hang off one. */
    private static String openEncounter(int minuteOffset) {
        Instant startsAt = Fixtures.slot(minuteOffset);
        appointmentId = given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of("patientId", patient.id(), "patientMrn", patient.mrn(),
                        "clinicianId", clinician.id(), "clinicianName", clinician.fullName(),
                        "departmentCode", clinician.departmentCode(),
                        "startsAt", startsAt.toString(), "durationMinutes", 15))
                .when().post("/appointments")
                .then().statusCode(201)
                .extract().jsonPath().getString("id");

        return given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("appointmentId", appointmentId, "patientId", patient.id(),
                        "patientMrn", patient.mrn(), "clinicianId", clinician.id(),
                        "departmentCode", clinician.departmentCode(),
                        "encounterType", "OUTPATIENT"))
                .when().post("/encounters")
                .then().statusCode(201)
                .extract().jsonPath().getString("id");
    }

    // ---- order sets ----------------------------------------------------------

    @Test
    @DisplayName("applying a set raises a real prescription and a real laboratory order")
    void applyingASetFansOutToTwoServices() {
        String encounterId = openEncounter(600);

        JsonPath applied = given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("encounterId", encounterId))
                .when().post("/order-sets/{code}/apply", "FEVER1")
                .then().statusCode(200)
                .body("prescriptionId", notNullValue())
                .body("labOrderId", notNullValue())
                .body("compensated", equalTo(false))
                .extract().jsonPath();

        // Both really exist, in the services that own them, and both name the encounter — which is
        // what lets the chart show what this visit raised.
        given().spec(Api.as(Api.PHARMACIST))
                .when().get("/prescriptions/{id}", applied.getString("prescriptionId"))
                .then().statusCode(200)
                .body("encounterId", equalTo(encounterId))
                .body("items[0].drugCode", equalTo("PARA500"))
                // The dose came from the template, not from a prescriber typing it a second time.
                .body("items[0].frequency", equalTo("four times daily"));

        given().spec(Api.as(Api.LAB_TECH))
                .when().get("/lab/orders/{id}", applied.getString("labOrderId"))
                .then().statusCode(200)
                // Two tests, one order: a panel of bloods is one needle, and separate orders would
                // mean separate specimens and a patient stuck twice.
                .body("items.size()", equalTo(2))
                // The set's most urgent line decides the order's priority. Taking the first or
                // averaging would quietly downgrade an urgent draw.
                .body("priority", equalTo("URGENT"));
    }

    @Test
    @DisplayName("a laboratory-only set raises no prescription at all")
    void aLabOnlySetRaisesOneThing() {
        String encounterId = openEncounter(660);

        JsonPath applied = given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("encounterId", encounterId))
                .when().post("/order-sets/{code}/apply", "ANAEMIA")
                .then().statusCode(200)
                .extract().jsonPath();

        assertThat(applied.getString("prescriptionId")).isNull();
        assertThat(applied.getString("labOrderId")).isNotNull();
    }

    @Test
    @DisplayName("a set the patient is allergic to is refused, and raises nothing on either side")
    void anAllergyRefusesTheWholeSet() {
        // Recorded on this run's own patient, so the refusal is about this test's data.
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("substance", "Ibuprofen", "reaction", "angioedema",
                        "severity", "LIFE_THREATENING"))
                .when().post("/patients/{id}/allergies", patient.id())
                .then().statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(201), org.hamcrest.Matchers.is(409)));

        String encounterId = openEncounter(720);

        // ANALGESIA1 contains ibuprofen. The prescription is raised first precisely so that this
        // refusal leaves nothing behind.
        String detail = given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("encounterId", encounterId))
                .when().post("/order-sets/{code}/apply", "ANALGESIA1")
                .then().statusCode(409)
                .extract().jsonPath().getString("detail");
        assertThat(detail).contains("Ibuprofen");

        // Nothing was raised against the encounter: no prescription, and no laboratory order.
        given().spec(Api.as(Api.PHARMACIST))
                .when().get("/prescriptions?encounterId={id}", encounterId)
                .then().statusCode(200)
                .body("size()", equalTo(0));
        given().spec(Api.as(Api.DOCTOR))
                .when().get("/lab/encounters/{id}/orders", encounterId)
                .then().statusCode(200)
                .body("size()", equalTo(0));
    }

    @Test
    @DisplayName("a nurse may apply a laboratory set and is refused one that prescribes")
    void thePharmacyDecidesWhoMayPrescribe() {
        String forNurse = openEncounter(780);

        given().spec(Api.as(Api.NURSE))
                .body(Map.of("encounterId", forNurse))
                .when().post("/order-sets/{code}/apply", "ANAEMIA")
                .then().statusCode(200);

        // The same nurse, a set containing medicines: refused by pharmacy-service, translated to a
        // 403 here. There is no second role list in scheduling-service that could drift from it.
        String another = openEncounter(840);
        given().spec(Api.as(Api.NURSE))
                .body(Map.of("encounterId", another))
                .when().post("/order-sets/{code}/apply", "FEVER1")
                .then().statusCode(403);
    }

    @Test
    @DisplayName("composing a set is administrative; using one is clinical")
    void whoMayWriteASet() {
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("code", "RXSET" + Fixtures.RUN, "name", "Doctor's set",
                        "items", List.of(Map.of("kind", "LAB", "code", "CBC"))))
                .when().post("/order-sets")
                .then().statusCode(403);

        given().spec(Api.as(Api.ADMIN))
                .body(Map.of("code", "APISET" + Fixtures.RUN, "name", "Suite set",
                        "departmentCode", "GEN",
                        "items", List.of(Map.of("kind", "LAB", "code", "CBC", "priority", "STAT"))))
                .when().post("/order-sets")
                .then().statusCode(201)
                .body("items[0].priority", equalTo("STAT"));
    }

    // ---- care plans ----------------------------------------------------------

    @Test
    @DisplayName("a plan's goals link to the encounter's own diagnoses, and closing forces a decision")
    void aCarePlanIsGoalsWithOutcomes() {
        String encounterId = openEncounter(900);

        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("icd10Code", "J06.9", "description", "Acute upper respiratory infection",
                        "category", "PRIMARY"))
                .when().post("/encounters/{id}/diagnoses", encounterId)
                .then().statusCode(201);

        JsonPath plan = given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("encounterId", encounterId, "title", "Recovery plan",
                        "goals", List.of(
                                Map.of("description", "Afebrile for 24 hours",
                                        "problemCode", "J06.9",
                                        "targetDate", LocalDate.now().plusDays(2).toString()),
                                Map.of("description", "Back at work"))))
                .when().post("/care-plans")
                .then().statusCode(201)
                .body("goals.size()", equalTo(2))
                .extract().jsonPath();

        // A goal filed under a diagnosis nobody made is a goal nobody will review.
        String refusal = given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("description", "HbA1c below 7", "problemCode", "E11.9"))
                .when().post("/care-plans/{id}/goals", plan.getString("id"))
                .then().statusCode(400)
                .extract().jsonPath().getString("detail");
        assertThat(refusal).contains("no diagnosis of E11.9");

        // An open goal blocks completion. That is the point: it makes somebody decide rather than
        // letting "we were going to do that" disappear from a discharge.
        given().spec(Api.as(Api.DOCTOR))
                .when().post("/care-plans/{id}/close?outcome=COMPLETED", plan.getString("id"))
                .then().statusCode(409);

        given().spec(Api.as(Api.NURSE))
                .body(Map.of("status", "MET"))
                .when().patch("/care-plans/goals/{id}", plan.getString("goals[0].id"))
                .then().statusCode(200);
        // And an outcome that is not "met" needs a note, refused without one.
        given().spec(Api.as(Api.NURSE))
                .body(Map.of("status", "ABANDONED"))
                .when().patch("/care-plans/goals/{id}", plan.getString("goals[1].id"))
                .then().statusCode(400);
        given().spec(Api.as(Api.NURSE))
                .body(Map.of("status", "ABANDONED", "progressNote", "Signed off for a further week"))
                .when().patch("/care-plans/goals/{id}", plan.getString("goals[1].id"))
                .then().statusCode(200);

        given().spec(Api.as(Api.DOCTOR))
                .when().post("/care-plans/{id}/close?outcome=COMPLETED", plan.getString("id"))
                .then().statusCode(200)
                .body("status", equalTo("COMPLETED"));
    }

    @Test
    @DisplayName("a care plan is chart content: the front desk and the laboratory cannot read it")
    void aCarePlanIsChartContent() {
        String encounterId = openEncounter(960);
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("encounterId", encounterId, "title", "Plan"))
                .when().post("/care-plans")
                .then().statusCode(201);

        for (String actor : List.of(Api.RECEPTIONIST, Api.LAB_TECH, Api.PHARMACIST)) {
            given().spec(Api.as(actor))
                    .when().get("/care-plans/encounters/{id}", encounterId)
                    .then().statusCode(403);
        }
        given().spec(Api.as(Api.PATHOLOGIST))
                .when().get("/care-plans/encounters/{id}", encounterId)
                .then().statusCode(200);
    }
}
