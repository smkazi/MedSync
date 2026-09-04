package com.hms.apitests.journey;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.Platform;
import com.hms.apitests.support.RequiresRunningStack;
import io.restassured.path.json.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * A child who arrives with a card, across three services.
 *
 * <p>Ordered, like the clinical and radiology journeys: it is one workflow and each step depends on
 * the one before. What only this test can prove is the seam no in-process test sees — a due list
 * whose birthdays come from patient-service, whose doses come from immunisation-service, and whose
 * schedule is rows in a third place, assembled through the gateway on one caller's own token.
 *
 * <p>The narrowing is a step rather than a footnote. This module runs the care-relationship check on
 * the <em>write</em> path as well as the read, which no other module does, so the journey has to
 * establish a care relationship before it can record anything — and the refusal before it does is
 * the assertion that the guard is real.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("immunisation journey across the gateway")
class ImmunisationJourneyIT extends RequiresRunningStack {

    /** Ten months old, so the six- and fourteen-week visits are behind them and measles is due. */
    /**
     * In the platform's zone, because the service computes ageDays against its own today and the
     * test then asserts that number exactly. Computed in the JVM's zone instead, this child was
     * 301 days old for five and a half hours out of every day.
     */
    private static final LocalDate BORN_ON = Platform.today().minusDays(300);

    private static Fixtures.Patient child;
    private static Fixtures.Clinician clinician;
    private static String lotNo;

    /** A child of a known age, which {@code Fixtures.registerPatient} cannot give us. */
    private static Fixtures.Patient registerChild() {
        JsonPath body = given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of(
                        "firstName", "Imm" + UUID.randomUUID().toString().substring(0, 4),
                        "lastName", "Cohort" + Fixtures.RUN,
                        "dateOfBirth", BORN_ON.toString(),
                        "sex", "MALE",
                        "city", "Test City",
                        "forceDuplicate", true))
                .when().post("/patients")
                .then().statusCode(201)
                .extract().jsonPath();
        return new Fixtures.Patient(body.getString("id"), body.getString("mrn"),
                body.getString("fullName"));
    }

    @Test
    @Order(1)
    @DisplayName("a clinician who is not looking after the child cannot record a dose for them")
    void theWritePathIsNarrowed() {
        child = registerChild();
        clinician = Fixtures.clinician();

        // Every other module leaves writes to the role gate, because writing to a chart is itself
        // evidence of providing care. A register is different: it is a lifetime record, and a dose
        // recorded against the wrong patient is a dose that child will not be called for again.
        given().spec(Api.as(Api.NURSE))
                .body(Map.of("patientId", child.id(), "patientMrn", child.mrn(),
                        "productCode", "BCG", "givenOn", BORN_ON.toString(),
                        "dateEstimated", false, "source", "HISTORICAL_DOCUMENTED",
                        "evidence", "Immunisation card seen, entry dated at birth"))
                .when().post("/immunisations/historical")
                .then().statusCode(403);
    }

    @Test
    @Order(2)
    @DisplayName("the clinician who opens the encounter records what the card says, with no lot")
    void aDoseFromACard() {
        // Opening the encounter enrols its clinician on the care team, which is what makes the
        // narrowing shippable rather than an outage: the treating clinician's experience is
        // unchanged and everybody else has to ask.
        given().spec(Api.withToken(clinician.accessToken()))
                .body(Map.of("patientId", child.id(), "patientMrn", child.mrn(),
                        "clinicianId", clinician.id(),
                        "departmentCode", clinician.departmentCode(),
                        "encounterType", "OUTPATIENT"))
                .when().post("/encounters")
                .then().statusCode(201);

        given().spec(Api.withToken(clinician.accessToken()))
                .body(Map.of("patientId", child.id(), "patientMrn", child.mrn(),
                        "productCode", "BCG", "givenOn", BORN_ON.toString(),
                        "dateEstimated", false, "source", "HISTORICAL_DOCUMENTED",
                        "evidence", "Immunisation card seen, entry dated at birth"))
                .when().post("/immunisations/historical")
                .then().statusCode(201)
                .body("source", equalTo("HISTORICAL_DOCUMENTED"))
                // The two fields that make a card dose say so on its face.
                .body("lotNo", nullValue())
                .body("evidence", containsString("card seen"))
                .body("antigenCodes", hasItem("BCG"));
    }

    @Test
    @Order(3)
    @DisplayName("naming this hospital on that endpoint is a request error, not a broken server")
    void theWrongSourceIsRefusedInWords() {
        given().spec(Api.withToken(clinician.accessToken()))
                .body(Map.of("patientId", child.id(), "patientMrn", child.mrn(),
                        "productCode", "OPV", "givenOn", BORN_ON.toString(),
                        "dateEstimated", false, "source", "ADMINISTERED_HERE",
                        "evidence", "This should not be accepted through this endpoint"))
                .when().post("/immunisations/historical")
                .then().statusCode(400)
                .body("detail", containsString("POST /immunisations"));
    }

    @Test
    @Order(4)
    @DisplayName("a dose given here comes out of a named lot in the fridge")
    void aDoseGivenHere() {
        lotNo = "JRN-" + Fixtures.RUN;
        given().spec(Api.as(Api.ADMIN))
                .body(Map.of("productCode", "PENTA", "lotNo", lotNo,
                        "expiresOn", LocalDate.now().plusYears(1).toString(),
                        "quantity", 10, "vvmStage", 1))
                .when().post("/vaccines/lots")
                .then().statusCode(201);

        given().spec(Api.withToken(clinician.accessToken()))
                .body(Map.of("patientId", child.id(), "patientMrn", child.mrn(),
                        "productCode", "PENTA", "lotNo", lotNo,
                        "givenOn", BORN_ON.plusDays(42).toString(), "site", "Left thigh"))
                .when().post("/immunisations")
                .then().statusCode(201)
                .body("lotNo", equalTo(lotNo))
                // The route comes off the product rather than the request, so a dose cannot record
                // a route the vaccine does not have.
                .body("route", equalTo("INTRAMUSCULAR"))
                // One vial, five antigens, and no code anywhere that knows what PENTA contains.
                .body("antigenCodes", hasItem("HIB"))
                .body("antigenCodes", hasItem("HEPB"));
    }

    @Test
    @Order(5)
    @DisplayName("the same product on the same day is refused by the constraint, not by a check")
    void theSameDoseTwice() {
        given().spec(Api.withToken(clinician.accessToken()))
                .body(Map.of("patientId", child.id(), "patientMrn", child.mrn(),
                        "productCode", "PENTA", "lotNo", lotNo,
                        "givenOn", BORN_ON.plusDays(42).toString(), "site", "Right thigh"))
                .when().post("/immunisations")
                .then().statusCode(409)
                .body("detail", containsString("already recorded"));
    }

    @Test
    @Order(6)
    @DisplayName("the register reads back both doses, from both roads")
    void theRegisterHasBoth() {
        JsonPath register = given().spec(Api.withToken(clinician.accessToken()))
                .when().get("/immunisations/patients/{id}", child.id())
                .then().statusCode(200)
                .extract().jsonPath();

        List<String> sources = register.getList("doses.source");
        assertThat(sources).containsExactlyInAnyOrder("HISTORICAL_DOCUMENTED", "ADMINISTERED_HERE");
        // A lot number on one and not the other, which is the biconditional read back out.
        assertThat(register.getList("doses.lotNo")).containsExactlyInAnyOrder(null, lotNo);
    }

    @Test
    @Order(7)
    @DisplayName("the due list crosses three services and knows the card dose counted")
    void theDueList() {
        JsonPath due = given().spec(Api.as(Api.NURSE))
                .queryParam("bornFrom", BORN_ON.toString())
                .queryParam("bornTo", BORN_ON.toString())
                .when().get("/immunisations/due")
                .then().statusCode(200)
                .body("scheduleCode", equalTo("UIP-2024"))
                .extract().jsonPath();

        // The cohort is not narrowed per row -- calling a birth cohort in is inherently
        // cross-patient work -- so this nurse sees the child without any care relationship, which
        // step 1 proved they cannot write to. Both halves are deliberate.
        List<Map<String, Object>> children = due.getList("children");
        assertThat(children).isNotEmpty();

        Map<String, Object> ours = children.stream()
                .filter(row -> child.mrn().equals(row.get("mrn")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the child is not in their own birth cohort"));
        assertThat((Integer) ours.get("ageDays")).isEqualTo(300);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ours.get("due");

        // BCG was recorded from the card, so the series is complete and nothing is due for it.
        Map<String, Object> bcg = row(rows, "BCG", 1);
        assertThat(bcg.get("status")).isEqualTo("COMPLETE");
        assertThat(bcg.get("dosesCounted")).isEqualTo(1);

        // The pentavalent dose at six weeks advanced Hib to its second dose.
        Map<String, Object> hib = row(rows, "HIB", 2);
        assertThat(hib.get("dosesCounted")).isEqualTo(1);
        assertThat(hib.get("status")).isEqualTo("OVERDUE");

        // And the hepatitis B birth dose window shut before that dose was given, so it did not fill
        // it -- the defect this assertion exists for is a register saying a child had their birth
        // dose on a date proving they did not.
        assertThat(row(rows, "HEPB", 1).get("status")).isEqualTo("NO_LONGER_GIVEN");
    }

    @Test
    @Order(8)
    @DisplayName("a card dose with nothing behind it is refused")
    void evidenceIsRequired() {
        given().spec(Api.withToken(clinician.accessToken()))
                .body(Map.of("patientId", child.id(), "patientMrn", child.mrn(),
                        "productCode", "MR", "givenOn", BORN_ON.plusDays(280).toString(),
                        "dateEstimated", true, "source", "HISTORICAL_PARENT_REPORTED",
                        "evidence", "told"))
                .when().post("/immunisations/historical")
                .then().statusCode(400);
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String antigen,
                                           int doseNumber) {
        return rows.stream()
                .filter(r -> antigen.equals(r.get("antigenCode"))
                        && Integer.valueOf(doseNumber).equals(r.get("doseNumber")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no due row for " + antigen + " dose " + doseNumber + " in " + rows));
    }
}
