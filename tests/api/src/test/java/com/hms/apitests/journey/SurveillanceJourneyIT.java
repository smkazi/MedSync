package com.hms.apitests.journey;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.Platform;
import com.hms.apitests.support.RequiresRunningStack;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The notifiable-disease return, end to end and across three services.
 *
 * <p>What this buys over the two service suites is the half neither can reach. scheduling-service's
 * own tests stub interop, so "the disclosure register is written before a file exists" is proven
 * against a stub there and against the real register here — over real HTTP, with the
 * administrator's own token forwarded, so the gate is genuinely enforced twice. And the last step
 * closes the loop the whole design is for: the patient this list named opens their own accounting
 * of disclosures and finds it.
 *
 * <p>Three identities, because the module's design is that they are three different jobs.
 * {@code dr.rao} diagnoses a case and never sees the return; {@code epidemiologist} reads the
 * counts and is refused the names; {@code admin} compiles the names and cannot do it without the
 * register recording that they did.
 */
@DisplayName("the notifiable-disease return, the line list, and the disclosure behind it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SurveillanceJourneyIT extends RequiresRunningStack {

    /** Measles: on the seeded notifiable list, and notifiable within twenty-four hours. */
    private static final String THE_CODE = "B05";

    /**
     * The day the platform will stamp this diagnosis with, which is not necessarily the day the
     * JVM running this test is having. A return asked for the wrong one of those two is empty.
     */
    private static final String TODAY = Platform.today().toString();

    private static Fixtures.PortalPatient patient;
    private static Fixtures.Clinician clinician;
    private static String encounterId;

    @Test
    @Order(1)
    @DisplayName("a clinician diagnoses a notifiable condition, knowing nothing about any return")
    void aCaseIsDiagnosed() {
        // A portal patient rather than a plain one, because the last step of this journey is the
        // patient reading their own accounting of what left the building about them.
        patient = Fixtures.portalPatient("Notify");
        clinician = Fixtures.clinician();

        String appointmentId = given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of("patientId", patient.id(), "patientMrn", patient.mrn(),
                        "clinicianId", clinician.id(), "clinicianName", clinician.fullName(),
                        "departmentCode", clinician.departmentCode(),
                        "startsAt", Fixtures.slot(0).toString(), "durationMinutes", 5))
                .when().post("/appointments")
                .then().statusCode(201)
                .extract().jsonPath().getString("id");

        encounterId = given().spec(Api.withToken(clinician.accessToken()))
                .body(Map.of("appointmentId", appointmentId, "patientId", patient.id(),
                        "patientMrn", patient.mrn(), "clinicianId", clinician.id(),
                        "departmentCode", clinician.departmentCode(),
                        "encounterType", "OUTPATIENT"))
                .when().post("/encounters")
                .then().statusCode(201)
                .extract().jsonPath().getString("id");

        given().spec(Api.withToken(clinician.accessToken()))
                .body(Map.of("icd10Code", THE_CODE, "description", "Measles",
                        "category", "PRIMARY"))
                .when().post("/encounters/{id}/diagnoses", encounterId)
                .then().statusCode(201);

        // The clinician who diagnosed it holds no part of the return, in either half. They already
        // know about their own case; compiling a district's is somebody else's job.
        given().spec(Api.withToken(clinician.accessToken()))
                .queryParam("from", TODAY).queryParam("to", TODAY)
                .when().get("/surveillance/notifiable")
                .then().statusCode(403);
        given().spec(Api.withToken(clinician.accessToken()))
                .queryParam("from", TODAY).queryParam("to", TODAY)
                .when().get("/surveillance/notifiable/line-list")
                .then().statusCode(403);
    }

    @Test
    @Order(2)
    @DisplayName("the epidemiologist reads the counts, and the case is in them")
    void theCountsIncludeIt() {
        given().spec(Api.as(Api.EPIDEMIOLOGIST))
                .queryParam("from", TODAY).queryParam("to", TODAY)
                .when().get("/surveillance/notifiable")
                .then().statusCode(200)
                // At least one, not exactly one: other journeys in this run diagnose their own
                // patients on the same day, and a statutory return counts all of them.
                .body("conditions.find { it.icd10Code == '%s' }.cases".formatted(THE_CODE),
                        greaterThanOrEqualTo(1))
                .body("zone", notNullValue())
                // Every configured condition appears, zeroes included: "no cholera this fortnight"
                // and "cholera is not on our list" must not render identically.
                .body("conditions.size()", greaterThanOrEqualTo(18));
    }

    @Test
    @Order(3)
    @DisplayName("the epidemiologist is refused the names, which is the property that lets the role exist")
    void theNamesAreNotTheirs() {
        given().spec(Api.as(Api.EPIDEMIOLOGIST))
                .queryParam("from", TODAY).queryParam("to", TODAY)
                .when().get("/surveillance/notifiable/line-list")
                .then().statusCode(403);
        given().spec(Api.as(Api.EPIDEMIOLOGIST))
                .queryParam("from", TODAY).queryParam("to", TODAY)
                .header("Accept", "text/csv, application/json")
                .when().get("/surveillance/notifiable/line-list.csv")
                .then().statusCode(403);
    }

    @Test
    @Order(4)
    @DisplayName("the administrator's preview names the patient and registers nothing")
    void thePreviewIsALookAndNotANotification() {
        given().spec(Api.as(Api.ADMIN))
                .queryParam("from", TODAY).queryParam("to", TODAY)
                .when().get("/surveillance/notifiable/line-list")
                .then().statusCode(200)
                .body("cases.find { it.patientMrn == '%s' }.icd10Code".formatted(patient.mrn()),
                        equalTo(THE_CODE))
                .body("cases.find { it.patientMrn == '%s' }.diagnosedOn".formatted(patient.mrn()),
                        equalTo(TODAY))
                .body("registered", equalTo(false));

        // And the patient's own accounting is still empty of it. Reading a record inside the
        // hospital is audited; handing one to somebody outside is registered. An administrator
        // looking at this fortnight's return has notified nobody.
        given().spec(Api.withToken(patient.accessToken()))
                .when().get("/portal/records/disclosures")
                .then().statusCode(200)
                .body("findAll { it.kind == 'PUBLIC_HEALTH_REPORT' }.size()", equalTo(0));
    }

    @Test
    @Order(5)
    @DisplayName("the download registers a disclosure first, and only then is there a file")
    void theFileExistsBecauseTheRegisterRecordedIt() {
        Response response = given().spec(Api.as(Api.ADMIN))
                .queryParam("from", TODAY).queryParam("to", TODAY)
                .header("Accept", "text/csv")
                .when().get("/surveillance/notifiable/line-list.csv")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .header("Content-Disposition",
                        "attachment; filename=\"notifiable-line-list-%s-to-%s.csv\""
                                .formatted(TODAY, TODAY))
                .extract().response();

        String csv = response.asString();
        assertThat(csv).startsWith("patientMrn,icd10Code,condition,diagnosedOn,notifyWithinHours");
        assertThat(csv).contains(patient.mrn() + "," + THE_CODE + ",Measles," + TODAY + ",24");
        // The authority receiving this has to be able to ask the hospital about a case, and an
        // internal UUID is a number nobody outside can use. So an MRN is on the file and the id
        // is not.
        assertThat(csv).doesNotContain(patient.id());
        assertThat(Integer.parseInt(response.header("X-Disclosures-Registered")))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(6)
    @DisplayName("the patient this list named can find it in their own accounting")
    void thePatientCanSeeIt() {
        // The whole point of a register that writes one row per patient rather than one per run:
        // a run-level row would need a fabricated patient id and would be invisible to every
        // patient on the list — including this one, asking the one question the accounting exists
        // to answer.
        given().spec(Api.withToken(patient.accessToken()))
                .when().get("/portal/records/disclosures")
                .then().statusCode(200)
                .body("findAll { it.kind == 'PUBLIC_HEALTH_REPORT' }.size()",
                        greaterThanOrEqualTo(1))
                .body("find { it.kind == 'PUBLIC_HEALTH_REPORT' }.recipient", notNullValue())
                // No consent, because notification is compelled by law and needs no permission.
                // A row naming one would tell this patient they agreed to something they were
                // never asked about, which is worse than an incomplete record because it is false.
                .body("find { it.kind == 'PUBLIC_HEALTH_REPORT' }.artefactId", nullValue())
                // And no releasedBy on the patient's view: the accounting requirement is about the
                // disclosure, not about who clicked, and naming an individual invites a complaint
                // aimed at a person rather than at the hospital.
                .body("find { it.kind == 'PUBLIC_HEALTH_REPORT' }", not(hasKey("releasedBy")));
    }
}
