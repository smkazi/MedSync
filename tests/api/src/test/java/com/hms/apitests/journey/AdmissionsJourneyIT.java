package com.hms.apitests.journey;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.RequiresRunningStack;
import io.restassured.path.json.JsonPath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Casualty and the in-patient ward through the gateway.
 *
 * <p>Black box, and here that buys something specific: bed allocation is a partial unique index in
 * admissions-service's schema, but the bed itself is patient-service's row, fetched over HTTP with
 * a forwarded token. A service test can prove the index refuses a second claim; only a request
 * through the gateway proves the allocator can see the bed at all, that the token survives the hop,
 * and that {@code /casualty/beds} is answered by admissions-service rather than by patient-service's
 * own {@code /beds/**} route — which is a real bug this suite would have caught, because the first
 * version of these endpoints lived under {@code /beds/casualty} and the gateway answered 405 from
 * the wrong service.
 *
 * <p>Every test releases the beds it takes. The seeded facility has four in-patient beds and the
 * browser suite runs against the same stack, so a test that admits and never discharges is a test
 * that makes the next one fail.
 */
@DisplayName("casualty and the in-patient ward")
class AdmissionsJourneyIT extends RequiresRunningStack {

    private static Fixtures.Patient patient;
    private static Fixtures.Clinician clinician;

    /** Everything opened here, so a failure part-way through does not strand a bed. */
    private static final List<String> OPEN_ATTENDANCES = new ArrayList<>();
    private static final List<String> OPEN_ADMISSIONS = new ArrayList<>();

    @BeforeAll
    static void seed() {
        patient = Fixtures.registerPatient(Api.RECEPTIONIST, "Casualty");
        clinician = Fixtures.clinician();
    }

    @AfterAll
    static void releaseWhatIsStillHeld() {
        for (String id : OPEN_ADMISSIONS) {
            given().spec(Api.as(Api.DOCTOR)).body(Map.of("summary", "api suite cleanup"))
                    .when().post("/admissions/{id}/discharge", id);
        }
        for (String id : OPEN_ATTENDANCES) {
            given().spec(Api.as(Api.DOCTOR)).when().post("/casualty/{id}/discharge", id);
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private static String arrive(int acuity, String complaint) {
        String id = given().spec(Api.as(Api.NURSE))
                .body(Map.of(
                        "patientId", patient.id(),
                        "patientMrn", patient.mrn(),
                        "triageAcuity", acuity,
                        "presentingComplaint", complaint))
                .when().post("/casualty")
                .then().statusCode(201)
                .body("status", equalTo("WAITING"))
                .body("triageAcuity", equalTo(acuity))
                .extract().jsonPath().getString("id");
        OPEN_ATTENDANCES.add(id);
        return id;
    }

    /** Free beds of a kind, in the order the service lists them. */
    private static List<Map<String, Object>> freeBeds(String path) {
        List<Map<String, Object>> beds = given().spec(Api.as(Api.DOCTOR))
                .when().get(path)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        return beds.stream().filter(bed -> !((Boolean) bed.get("occupied"))).toList();
    }

    private static String bedId(Map<String, Object> bed) {
        return (String) bed.get("bedId");
    }

    /** Where an attendance sits on the board, or -1 when it is no longer on it. */
    private static int boardPosition(String attendanceId) {
        List<String> ids = given().spec(Api.as(Api.NURSE))
                .when().get("/casualty")
                .then().statusCode(200)
                .extract().jsonPath().getList("id");
        return ids.indexOf(attendanceId);
    }

    // ---- the tests -------------------------------------------------------------

    @Test
    @DisplayName("the sickest patient is above one who arrived first, whatever the order of arrival")
    void sickestFirst() {
        // Deliberately reversed: the walking wounded arrive before the collapse. A board that
        // sorted by arrival - which is what a spreadsheet does, and what a sortable column header
        // invites somebody to do at 3am - would put them the other way round.
        String minor = arrive(4, "Grazed knee, walked in");
        String critical = arrive(1, "Unresponsive, brought in by ambulance");

        // Relative, not absolute. The board is shared with the browser suite and with whatever a
        // developer left on it, so "at the top" is not a claim this test can make; "above" is.
        assertThat(boardPosition(critical))
                .as("acuity 1 must be above the acuity 4 who arrived before them")
                .isLessThan(boardPosition(minor));
    }

    @Test
    @DisplayName("re-triage moves a patient up the board while they wait")
    void deteriorationIsNoticed() {
        String deteriorating = arrive(4, "Abdominal pain, comfortable");
        String other = arrive(2, "Chest pain");
        assertThat(boardPosition(deteriorating)).isGreaterThan(boardPosition(other));

        given().spec(Api.as(Api.NURSE))
                .body(Map.of("triageAcuity", 1))
                .when().patch("/casualty/{id}/triage", deteriorating)
                .then().statusCode(200)
                .body("triageAcuity", equalTo(1));

        // A patient who gets worse in a corridor is the case the board exists for. It is not
        // enough that the number changed - the order has to change with it.
        assertThat(boardPosition(deteriorating))
                .as("re-triaged to 1, now above the acuity 2")
                .isLessThan(boardPosition(other));
    }

    @Test
    @DisplayName("a whole stay: arrive, bay, admit, transfer, discharge, and the bed comes back")
    void theWholeStay() {
        String attendance = arrive(2, "Chest pain, radiating");

        Map<String, Object> bay = freeBeds("/casualty/beds").getFirst();
        given().spec(Api.as(Api.NURSE))
                .body(Map.of("bedId", bedId(bay)))
                .when().post("/casualty/{id}/bed", attendance)
                .then().statusCode(200)
                .body("status", equalTo("IN_BED"))
                .body("bedCode", equalTo(bay.get("bedCode")));

        List<Map<String, Object>> wardBeds = freeBeds("/admissions/beds");
        assertThat(wardBeds)
                .as("this test needs two free in-patient beds; something before it did not clean up")
                .hasSizeGreaterThanOrEqualTo(2);
        Map<String, Object> first = wardBeds.get(0);
        Map<String, Object> second = wardBeds.get(1);

        Map<String, Object> admitBody = new HashMap<>(Map.of(
                "patientId", patient.id(),
                "patientMrn", patient.mrn(),
                "attendanceId", attendance,
                "bedId", bedId(first),
                "admittingClinicianId", clinician.id(),
                "source", "CASUALTY"));
        String admission = given().spec(Api.as(Api.DOCTOR))
                .body(admitBody)
                .when().post("/admissions")
                .then().statusCode(201)
                .body("status", equalTo("ADMITTED"))
                .body("bedCode", equalTo(first.get("bedCode")))
                .extract().jsonPath().getString("id");
        OPEN_ADMISSIONS.add(admission);

        // Admitting hands the casualty bay back. The whole point of one occupancy table is that a
        // patient cannot be in two beds at once, and the corollary is that the bay is free the
        // moment they leave it - not when somebody remembers to tidy the board.
        assertThat(freeBeds("/casualty/beds").stream().map(AdmissionsJourneyIT::bedId))
                .as("the resus bay is free again the moment the patient goes to the ward")
                .contains(bedId(bay));
        assertThat(boardPosition(attendance))
                .as("an admitted patient is off the casualty board")
                .isEqualTo(-1);

        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("toBedId", bedId(second), "reason", "Side room needed for isolation"))
                .when().post("/admissions/{id}/transfer", admission)
                .then().statusCode(200)
                .body("bedCode", equalTo(second.get("bedCode")))
                .body("transfers.size()", equalTo(1))
                .body("transfers[0].fromBedCode", equalTo(first.get("bedCode")))
                .body("transfers[0].toBedCode", equalTo(second.get("bedCode")))
                .body("transfers[0].reason", equalTo("Side room needed for isolation"));

        // Released then claimed inside one transaction, so there is no instant at which the
        // patient reads as being in both beds - and none at which they are in neither.
        List<String> freeAfterTransfer = freeBeds("/admissions/beds").stream()
                .map(AdmissionsJourneyIT::bedId).toList();
        assertThat(freeAfterTransfer).contains(bedId(first)).doesNotContain(bedId(second));

        JsonPath discharged = given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("summary", "Ruled out. Home with advice."))
                .when().post("/admissions/{id}/discharge", admission)
                .then().statusCode(200)
                .body("status", equalTo("DISCHARGED"))
                .extract().jsonPath();
        OPEN_ADMISSIONS.remove(admission);
        OPEN_ATTENDANCES.remove(attendance);
        assertThat(discharged.getString("dischargedAt")).isNotNull();

        assertThat(freeBeds("/admissions/beds").stream().map(AdmissionsJourneyIT::bedId))
                .as("a discharged bed is allocatable again")
                .contains(bedId(second));
    }

    @Test
    @DisplayName("two patients cannot be put in one bed, and the refusal says what to do next")
    void oneBedOnePatient() {
        String firstPatient = arrive(3, "Laceration to forearm");
        String secondPatient = arrive(3, "Laceration to hand");

        Map<String, Object> bay = freeBeds("/casualty/beds").getFirst();
        given().spec(Api.as(Api.NURSE))
                .body(Map.of("bedId", bedId(bay)))
                .when().post("/casualty/{id}/bed", firstPatient)
                .then().statusCode(200);

        // The index is the control, not a check-then-act in the service: two nurses on two
        // terminals reach this line at the same instant on a busy night, and only the database
        // sees both. The message matters as much as the status - a nurse holding a trolley needs
        // to know to pick another bay, not to read a stack trace.
        String detail = given().spec(Api.as(Api.NURSE))
                .body(Map.of("bedId", bedId(bay)))
                .when().post("/casualty/{id}/bed", secondPatient)
                .then().statusCode(409)
                .extract().jsonPath().getString("detail");
        assertThat(detail)
                .contains((String) bay.get("bedCode"))
                .contains((String) bay.get("roomCode"))
                .contains("Pick another");
    }

    @Test
    @DisplayName("leaving without being seen is its own outcome, not a discharge")
    void leftWithoutBeingSeen() {
        String attendance = arrive(4, "Sore throat, waited two hours");

        given().spec(Api.as(Api.NURSE))
                .when().post("/casualty/{id}/left", attendance)
                .then().statusCode(200)
                .body("status", equalTo("LEFT_WITHOUT_BEING_SEEN"));
        OPEN_ATTENDANCES.remove(attendance);

        assertThat(boardPosition(attendance)).isEqualTo(-1);

        // And it survives on the patient's timeline as itself. Folding it into DISCHARGED would
        // delete the only signal that says people are giving up on the department.
        List<String> statuses = given().spec(Api.as(Api.DOCTOR))
                .when().get("/casualty/patients/{id}", patient.id())
                .then().statusCode(200)
                .extract().jsonPath().getList("status");
        assertThat(statuses).contains("LEFT_WITHOUT_BEING_SEEN");
    }

    @Test
    @DisplayName("an admission is refused when the casualty attendance is already closed")
    void anAlreadyClosedAttendanceCannotBeAdmitted() {
        String attendance = arrive(3, "Twisted ankle");
        given().spec(Api.as(Api.NURSE))
                .when().post("/casualty/{id}/discharge", attendance)
                .then().statusCode(200);
        OPEN_ATTENDANCES.remove(attendance);

        Map<String, Object> ward = freeBeds("/admissions/beds").getFirst();
        Map<String, Object> body = new HashMap<>(Map.of(
                "patientId", patient.id(),
                "patientMrn", patient.mrn(),
                "attendanceId", attendance,
                "bedId", bedId(ward),
                "admittingClinicianId", clinician.id(),
                "source", "CASUALTY"));

        given().spec(Api.as(Api.DOCTOR)).body(body)
                .when().post("/admissions")
                .then().statusCode(400);

        // And the bed was not taken on the way to being refused.
        assertThat(freeBeds("/admissions/beds").stream().map(AdmissionsJourneyIT::bedId))
                .as("a refused admission leaves no occupancy behind")
                .contains(bedId(ward));
    }

    @Test
    @DisplayName("a planned admission needs no casualty attendance at all")
    void anElectiveAdmissionHasNoAttendance() {
        Map<String, Object> ward = freeBeds("/admissions/beds").getFirst();
        Map<String, Object> body = new HashMap<>(Map.of(
                "patientId", patient.id(),
                "patientMrn", patient.mrn(),
                "bedId", bedId(ward),
                "admittingClinicianId", clinician.id(),
                "source", "ELECTIVE"));

        String admission = given().spec(Api.as(Api.DOCTOR)).body(body)
                .when().post("/admissions")
                .then().statusCode(201)
                .body("source", equalTo("ELECTIVE"))
                // Null, not absent and not a placeholder: a planned admission genuinely has no
                // casualty attendance, which is a real state rather than missing data.
                .body("attendanceId", org.hamcrest.Matchers.nullValue())
                .extract().jsonPath().getString("id");
        OPEN_ADMISSIONS.add(admission);

        given().spec(Api.as(Api.DOCTOR)).body(Map.of("summary", "Day case, home same evening."))
                .when().post("/admissions/{id}/discharge", admission)
                .then().statusCode(200);
        OPEN_ADMISSIONS.remove(admission);
    }
}
