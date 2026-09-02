package com.hms.apitests.journey;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.RequiresRunningStack;
import io.restassured.path.json.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The closed medication loop through the gateway, under three identities.
 *
 * <p>Black box, and here it buys the one thing pharmacy-service's own suite cannot have: the
 * allergy check runs over a real HTTP call to patient-service with a real forwarded token. The
 * service test stubs that client — it has to, since nothing else is running there — so "the
 * prescription was refused because the patient's chart says penicillin" is only actually proven
 * here, end to end, across two services and two schemas.
 *
 * <p>Three identities because no account may hold more than one part of the loop: {@code dr.rao}
 * prescribes, {@code pharmacist} dispenses, {@code nurse.iqbal} gives the dose. A suite that drove
 * it all as an administrator would be testing a system nobody runs.
 */
@DisplayName("the closed medication loop")
class MedicationJourneyIT extends RequiresRunningStack {

    private static Fixtures.Patient patient;

    /** A batch received for this run, so the assertions about stock are about this test's stock. */
    private static String batchNo;

    @BeforeAll
    static void seed() {
        patient = Fixtures.registerPatient(Api.RECEPTIONIST, "Meds");
        batchNo = "API-" + Fixtures.RUN;
        given().spec(Api.as(Api.PHARMACIST))
                .body(Map.of("drugCode", "PARA500", "batchNo", batchNo,
                        "expiresOn", LocalDate.now().plusYears(1).toString(), "quantity", 500))
                .when().post("/pharmacy/stock")
                .then().statusCode(201);
    }

    private static Map<String, Object> line(String drugCode, int quantity) {
        return Map.of("drugCode", drugCode, "dose", "1 tablet", "frequency", "twice daily",
                "durationDays", 5, "quantity", quantity);
    }

    private static JsonPath prescribe(List<Map<String, Object>> items, String override,
                                      int expected) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("patientId", patient.id());
        body.put("patientMrn", patient.mrn());
        body.put("items", items);
        if (override != null) {
            body.put("overrideReason", override);
        }
        return given().spec(Api.as(Api.DOCTOR))
                .body(body)
                .when().post("/prescriptions")
                .then().statusCode(expected)
                .extract().jsonPath();
    }

    private static void recordAllergy(String substance, String severity) {
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("substance", substance, "reaction", "anaphylaxis",
                        "severity", severity))
                .when().post("/patients/{id}/allergies", patient.id())
                .then().statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(201), org.hamcrest.Matchers.is(409)));
    }

    // ---- the narrow role -----------------------------------------------------

    @Test
    @DisplayName("a pharmacist reads the allergy list and nothing else about the patient")
    void thePharmacyRoleIsNarrow() {
        recordAllergy("Penicillin", "LIFE_THREATENING");

        // The one patient endpoint the pharmacy holds. It exists so that a pharmacist can check a
        // dispense against what the patient reacts to without being handed the chart.
        List<String> substances = given().spec(Api.as(Api.PHARMACIST))
                .when().get("/patients/{id}/allergies", patient.id())
                .then().statusCode(200)
                .extract().jsonPath().getList("allergies.substance");
        assertThat(substances).contains("Penicillin");

        // And nothing else. Each of these is a 403 for the pharmacy, which is the whole point of a
        // separate role: a leaked pharmacy password buys a formulary and a dispensing queue.
        for (String path : List.of("/patients?q=meds", "/appointments?from=2026-01-01&to=2026-12-31",
                "/lab/orders", "/casualty", "/admissions", "/admin/users", "/notifications")) {
            given().spec(Api.as(Api.PHARMACIST))
                    .when().get(path)
                    .then().statusCode(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.is(403), org.hamcrest.Matchers.is(401)));
        }
    }

    // ---- the checks, across two services -------------------------------------

    @Test
    @DisplayName("a recorded penicillin allergy refuses amoxicillin, read live from the chart")
    void theAllergyCheckCrossesTheServiceBoundary() {
        recordAllergy("Penicillin", "LIFE_THREATENING");

        String detail = prescribe(List.of(line("AMOX500", 21)), null, 409).getString("detail");

        // Matched on the ingredient, not on the name: nobody records an allergy as "amoxicillin",
        // and a check that compared the two words would hand the capsule over.
        assertThat(detail)
                .contains("cannot be written")
                .contains("Penicillin")
                .contains("PENICILLIN");
    }

    @Test
    @DisplayName("a contraindicated pairing refuses however good the reason")
    void aContraindicatedPairingRefuses() {
        String detail = prescribe(List.of(line("CLARITH500", 14), line("SIMVA20", 28)),
                "Patient has taken both before", 409).getString("detail");

        assertThat(detail)
                .contains("contraindicated")
                // The management text travels with the refusal, because "these interact" is not an
                // instruction and "use azithromycin instead" is.
                .contains("azithromycin");
    }

    @Test
    @DisplayName("a major pairing is written once a prescriber says why, and the pharmacy sees the reason")
    void aMajorPairingCarriesItsReasonToTheCounter() {
        prescribe(List.of(line("WARF5", 28), line("IBU400", 15)), null, 409);

        String reason = "Five-day course, PPI added, INR on day 3";
        String id = prescribe(List.of(line("WARF5", 28), line("IBU400", 15)), reason, 201)
                .getString("id");

        // Read back by the pharmacist, which is the point: they are the last person who can
        // question the judgement, and it has to reach them.
        given().spec(Api.as(Api.PHARMACIST))
                .when().get("/prescriptions/{id}", id)
                .then().statusCode(200)
                .body("overrideReason", equalTo(reason));
    }

    // ---- the loop ------------------------------------------------------------

    @Test
    @DisplayName("prescribed, dispensed, given: three acts, three roles, one dose one record")
    void theWholeLoop() {
        JsonPath written = prescribe(List.of(line("PARA500", 12)), null, 201);
        String itemId = written.getString("items[0].id");

        // A doctor cannot dispense what they wrote.
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("prescriptionItemId", itemId, "quantity", 6))
                .when().post("/pharmacy/dispenses")
                .then().statusCode(403);

        JsonPath dispensed = given().spec(Api.as(Api.PHARMACIST))
                .body(Map.of("prescriptionItemId", itemId, "quantity", 12))
                .when().post("/pharmacy/dispenses")
                .then().statusCode(201)
                .body("outstanding", equalTo(0))
                .extract().jsonPath();
        assertThat(dispensed.getString("batchNo"))
                .as("first expiry first out among the usable batches")
                .isNotBlank();

        // Fully dispensed, so the prescription completes itself — asked of the numbers rather than
        // set as a flag somebody has to remember.
        given().spec(Api.as(Api.PHARMACIST))
                .when().get("/prescriptions/{id}", written.getString("id"))
                .then().statusCode(200)
                .body("status", equalTo("COMPLETED"));

        // The pharmacy does not give the dose.
        String due = Instant.now().toString();
        given().spec(Api.as(Api.PHARMACIST))
                .body(Map.of("prescriptionItemId", itemId, "scheduledFor", due,
                        "patientScan", patient.mrn(), "drugScan", "PARA500"))
                .when().post("/emar/administer")
                .then().statusCode(403);

        // The wrong wristband is refused, and the message says which chart is open.
        String refusal = given().spec(Api.as(Api.NURSE))
                .body(Map.of("prescriptionItemId", itemId, "scheduledFor", due,
                        "patientScan", "MRN-0000-000000", "drugScan", "PARA500"))
                .when().post("/emar/administer")
                .then().statusCode(409)
                .extract().jsonPath().getString("detail");
        assertThat(refusal).contains("Do not give this dose").contains(patient.mrn());

        // Both scans right, in whatever case the reader produced.
        given().spec(Api.as(Api.NURSE))
                .body(Map.of("prescriptionItemId", itemId, "scheduledFor", due,
                        "patientScan", patient.mrn().toLowerCase(Locale.ROOT),
                        "drugScan", "para500"))
                .when().post("/emar/administer")
                .then().statusCode(201)
                .body("status", equalTo("GIVEN"));

        // And once. Two nurses at one bedside, each believing the other had not given it, is the
        // failure the unique constraint exists for.
        String second = given().spec(Api.as(Api.NURSE))
                .body(Map.of("prescriptionItemId", itemId, "scheduledFor", due,
                        "patientScan", patient.mrn(), "drugScan", "PARA500"))
                .when().post("/emar/administer")
                .then().statusCode(409)
                .extract().jsonPath().getString("detail");
        assertThat(second).contains("already been recorded");
    }

    @Test
    @DisplayName("a dose not given is a row with a reason, and the round shows it")
    void aDoseNotGivenIsRecorded() {
        JsonPath written = prescribe(List.of(line("PARA500", 4)), null, 201);
        String itemId = written.getString("items[0].id");

        given().spec(Api.as(Api.NURSE))
                .body(Map.of("prescriptionItemId", itemId, "scheduledFor", Instant.now().toString(),
                        "status", "OMITTED", "reason", "Nil by mouth for theatre"))
                .when().post("/emar/not-given")
                .then().statusCode(201)
                .body("status", equalTo("OMITTED"));

        List<String> statuses = given().spec(Api.as(Api.NURSE))
                .when().get("/emar/items/{id}", itemId)
                .then().statusCode(200)
                .extract().jsonPath().getList("status");
        assertThat(statuses).containsExactly("OMITTED");
    }

    @Test
    @DisplayName("a check against a patient who does not exist is a 404, not a platform failure")
    void anUnknownPatientIsTheCallersMistake() {
        // The client that reads the allergy list fails closed, which is right for an unreachable
        // service and wrong for a wrong id: the first version answered 500 for both, telling a
        // prescriber the platform was broken when in fact they had mistyped an id. Only a
        // black-box test can catch that — pharmacy-service's own suite stubs this client.
        String detail = given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("patientId", UUID.randomUUID().toString(),
                        "drugCodes", List.of("PARA500")))
                .when().post("/pharmacy/check")
                .then().statusCode(404)
                .extract().jsonPath().getString("detail");
        assertThat(detail).contains("No patient with id");
    }

    @Test
    @DisplayName("an expired batch cannot be received, whatever the paperwork says")
    void expiredStockIsRefused() {
        given().spec(Api.as(Api.PHARMACIST))
                .body(Map.of("drugCode", "PARA500", "batchNo", "EXP-" + UUID.randomUUID(),
                        "expiresOn", LocalDate.now().minusDays(1).toString(), "quantity", 10))
                .when().post("/pharmacy/stock")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("the interaction table is readable by the ward, not only by the pharmacy")
    void theInteractionTableIsReadableByWhoeverGivesTheMedicine() {
        // A nurse told two medicines interact should be able to look up what the platform thinks
        // and what it advises, rather than meeting the rule only as somebody else's refusal.
        List<String> management = given().spec(Api.as(Api.NURSE))
                .when().get("/pharmacy/interactions")
                .then().statusCode(200)
                .extract().jsonPath().getList("management");
        assertThat(management).isNotEmpty().allSatisfy(text -> assertThat(text).isNotBlank());

        // And not by the laboratory, which has no part in this loop at all.
        given().spec(Api.as(Api.LAB_TECH))
                .when().get("/pharmacy/interactions")
                .then().statusCode(403);
    }
}
