package com.hms.apitests.journey;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.MinimalDicom;
import com.hms.apitests.support.RequiresRunningStack;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * One examination, from a clinician's question to a signed report, across four services.
 *
 * <p>Ordered on purpose, like the clinical journey: this is a single workflow and each step depends
 * on the one before exactly as the department does. Three identities drive it and none of them can
 * do another's part, which is the whole reason radiology has two roles rather than one.
 *
 * <p>What only this test can prove is the seam that no in-process test sees: a file goes in over
 * multipart through the gateway, and what comes back out is a study attached to a request raised by
 * a different service against a patient a third service owns — matched on nothing but the accession
 * number the platform minted.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("radiology journey across the gateway")
class ImagingJourneyIT extends RequiresRunningStack {

    private static Fixtures.Patient patient;
    private static Fixtures.Clinician clinician;
    private static String encounterId;
    private static String orderId;
    private static String accessionNo;
    private static String studyId;
    private static String procedureCode;

    /** Distinct per run, so a re-run does not collide with the study UIDs the last one registered. */
    private static final String RUN_UID = MinimalDicom.uid(
            Long.toString(System.currentTimeMillis()));

    @Test
    @Order(1)
    @DisplayName("a clinician orders an examination from the encounter, and gets an accession number")
    void order() {
        patient = Fixtures.registerPatient(Api.RECEPTIONIST, "Imaging");
        clinician = Fixtures.clinician();
        encounterId = openEncounter();

        // Whatever the department has configured, rather than a code hard-coded here: the
        // catalogue is configuration, and a test that pinned one row would break the first time
        // somebody edited it.
        JsonPath catalogue = given().spec(Api.as(Api.DOCTOR))
                .when().get("/imaging/procedures")
                .then().statusCode(200)
                .extract().jsonPath();
        procedureCode = catalogue.getString("[0].code");
        assertThat(procedureCode).as("the catalogue must have rows or nothing can be ordered")
                .isNotBlank();

        JsonPath ordered = given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("patientId", patient.id(), "patientMrn", patient.mrn(),
                        "encounterId", encounterId, "procedureCode", procedureCode,
                        "clinicalQuestion", "Persistent cough for three weeks, query consolidation.",
                        "priority", "URGENT"))
                .when().post("/imaging/orders")
                .then().statusCode(201)
                .body("status", equalTo("ORDERED"))
                .body("patientMrn", equalTo(patient.mrn()))
                .body("accessionNo", notNullValue())
                .extract().jsonPath();

        orderId = ordered.getString("id");
        accessionNo = ordered.getString("accessionNo");

        // The chart can see what it raised. This is the link that makes an examination part of a
        // visit rather than a free-floating fact about a patient.
        given().spec(Api.as(Api.DOCTOR))
                .when().get("/imaging/encounters/{id}/orders", encounterId)
                .then().statusCode(200)
                .body("accessionNo", hasItem(accessionNo));
    }

    @Test
    @Order(2)
    @DisplayName("the request reaches the radiographer's worklist, without the clinical question")
    void worklist() {
        JsonPath worklist = given().spec(Api.as(Api.RADIOGRAPHER))
                .when().get("/imaging/worklist")
                .then().statusCode(200)
                .body("accessionNo", hasItem(accessionNo))
                .extract().jsonPath();

        // Asserted on the payload's own keys rather than by searching the body for a substring.
        // A worklist is read on a screen beside a scanner, in a room patients walk through, and
        // "the question happens not to be in this row" is a weaker claim than "there is nowhere in
        // this shape to put one".
        Map<String, Object> row = worklist.getMap("find { it.accessionNo == '" + accessionNo + "' }");
        assertThat(row).doesNotContainKeys("clinicalQuestion", "patientName", "patientId");
        assertThat(row).containsKeys("accessionNo", "patientMrn", "procedureName", "priority");
    }

    @Test
    @Order(3)
    @DisplayName("the radiographer books a slot")
    void schedule() {
        Instant slot = Instant.now().plusSeconds(3600);
        given().spec(Api.as(Api.RADIOGRAPHER))
                .body(Map.of("scheduledFor", slot.toString()))
                .when().post("/imaging/orders/{id}/schedule", orderId)
                .then().statusCode(200)
                .body("status", equalTo("SCHEDULED"))
                .body("scheduledFor", notNullValue());
    }

    @Test
    @Order(4)
    @DisplayName("a file carrying the accession number is filed against the request, and the header's patient id is not believed")
    void fileTheStudy() {
        // A patient id that is not this patient's, on purpose. A DICOM header's identifiers are
        // whatever was typed at the modality console; matching on them would file a study against
        // the wrong visit the first time somebody was scanned twice in a day. The accession number
        // is the field the worklist puts into the machine and the machine writes back.
        byte[] file = MinimalDicom.instance(accessionNo, "TYPED-WRONG-AT-CONSOLE",
                RUN_UID + ".1", RUN_UID + ".1.1", RUN_UID + ".1.1.1");

        JsonPath filed = given().spec(Api.as(Api.RADIOGRAPHER))
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "chest.dcm", file, "application/dicom")
                .when().post("/imaging/studies")
                .then().statusCode(201)
                .body("matched", equalTo(true))
                .body("accessionNo", equalTo(accessionNo))
                .extract().jsonPath();

        assertThat(filed.getString("message"))
                .as("the platform says whether it matched and whether it archived, in words")
                .isNotBlank();

        JsonPath order = given().spec(Api.as(Api.RADIOGRAPHER))
                .when().get("/imaging/orders/{id}", orderId)
                .then().statusCode(200)
                .body("status", equalTo("ACQUIRED"))
                .body("studies.size()", equalTo(1))
                // The order's MRN, not the header's. This is the assertion that the accession
                // number is the only thing trusted.
                .body("studies[0].patientMrn", equalTo(patient.mrn()))
                .body("studies[0].orderId", equalTo(orderId))
                .extract().jsonPath();

        studyId = order.getString("studies[0].id");
        assertThat(order.getInt("studies[0].series[0].instanceCount")).isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("the same instance filed twice does not become two")
    void resendIsIdempotent() {
        byte[] file = MinimalDicom.instance(accessionNo, "TYPED-WRONG-AT-CONSOLE",
                RUN_UID + ".1", RUN_UID + ".1.1", RUN_UID + ".1.1.1");

        // A modality that was unsure the first attempt landed resends. Answering 201 twice and
        // registering two copies would inflate every count the department reads.
        given().spec(Api.as(Api.RADIOGRAPHER))
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "chest.dcm", file, "application/dicom")
                .when().post("/imaging/studies")
                .then().statusCode(201);

        given().spec(Api.as(Api.RADIOGRAPHER))
                .when().get("/imaging/orders/{id}", orderId)
                .then().statusCode(200)
                .body("studies.size()", equalTo(1))
                .body("studies[0].series[0].instanceCount", equalTo(1));
    }

    @Test
    @Order(6)
    @DisplayName("it reaches the reporting queue, and a draft is not released")
    void draft() {
        given().spec(Api.as(Api.RADIOLOGIST))
                .when().get("/imaging/reporting-queue")
                .then().statusCode(200)
                .body("accessionNo", hasItem(accessionNo));

        given().spec(Api.as(Api.RADIOLOGIST))
                .body(Map.of("findings", "No focal consolidation. Heart size within normal limits.",
                        "impression", "Normal chest radiograph."))
                .when().put("/imaging/studies/{id}/report", studyId)
                .then().statusCode(200)
                .body("status", equalTo("DRAFT"))
                .body("signedBy", nullValue())
                .body("signedAt", nullValue());

        // Still unread as far as the queue is concerned: a draft is not a report, and a queue that
        // dropped a study on the first keystroke would lose it.
        given().spec(Api.as(Api.RADIOLOGIST))
                .when().get("/imaging/reporting-queue")
                .then().statusCode(200)
                .body("accessionNo", hasItem(accessionNo));
    }

    @Test
    @Order(7)
    @DisplayName("signing releases it, and the order reads REPORTED")
    void sign() {
        given().spec(Api.as(Api.RADIOLOGIST))
                .when().post("/imaging/studies/{id}/report/sign", studyId)
                .then().statusCode(200)
                .body("status", equalTo("SIGNED"))
                .body("signedBy", equalTo(Api.RADIOLOGIST))
                .body("signedAt", notNullValue());

        given().spec(Api.as(Api.DOCTOR))
                .when().get("/imaging/orders/{id}", orderId)
                .then().statusCode(200)
                .body("status", equalTo("REPORTED"))
                .body("studies[0].report.impression", equalTo("Normal chest radiograph."));

        given().spec(Api.as(Api.RADIOLOGIST))
                .when().get("/imaging/reporting-queue")
                .then().statusCode(200)
                .body("accessionNo", not(hasItem(accessionNo)));
    }

    @Test
    @Order(8)
    @DisplayName("an amendment keeps the text that was signed")
    void amend() {
        String superseded = given().spec(Api.as(Api.RADIOLOGIST))
                .body(Map.of("findings", "Subtle right lower zone opacity on review.",
                        "impression", "Possible early consolidation. Correlate clinically.",
                        "reason", "Re-reviewed with the previous film available for comparison."))
                .when().post("/imaging/studies/{id}/report/amend", studyId)
                .then().statusCode(200)
                .body("status", equalTo("AMENDED"))
                .body("amendedReason", notNullValue())
                .body("findings", equalTo("Subtle right lower zone opacity on review."))
                .body("impression", equalTo("Possible early consolidation. Correlate clinically."))
                .extract().jsonPath().getString("amendedFrom");

        // The whole point of an amendment rather than an edit: somebody may have treated from what
        // was signed, so what was signed stays on the record beside the correction.
        //
        // And what is kept is the whole released document rather than one field of it — both halves
        // of the report plus who signed it and when. That is the right thing to keep and it is
        // worth asserting deliberately: a clinician who acted on the old report needs to see the
        // text they read and whose name was on it, not a diff of the impression line.
        assertThat(superseded)
                .contains("No focal consolidation")
                .contains("Normal chest radiograph.")
                .contains(Api.RADIOLOGIST);
    }

    @Test
    @Order(9)
    @DisplayName("a study whose accession names no request is registered and listed, not guessed at or dropped")
    void unmatchedIsKept() {
        // Short on purpose: an accession number is a DICOM SH, capped at sixteen characters, and a
        // fixture that overran it would be testing the column's width rather than the matching.
        String orphanAccession = "NOSUCH-" + Fixtures.RUN;
        byte[] file = MinimalDicom.instance(orphanAccession, "SOMEBODY",
                RUN_UID + ".9", RUN_UID + ".9.1", RUN_UID + ".9.1.1");

        given().spec(Api.as(Api.RADIOGRAPHER))
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "orphan.dcm", file, "application/dicom")
                .when().post("/imaging/studies")
                .then().statusCode(201)
                .body("matched", equalTo(false))
                // Registered anyway. The images exist on a scanner's disk whatever this platform
                // makes of them, and somebody in the department has the day's paperwork.
                .body("studyId", notNullValue());

        given().spec(Api.as(Api.RADIOGRAPHER))
                .when().get("/imaging/studies/unmatched")
                .then().statusCode(200)
                .body("accessionNo", hasItem(orphanAccession))
                .body("find { it.accessionNo == '" + orphanAccession + "' }.orderId", nullValue());
    }

    @Test
    @Order(10)
    @DisplayName("a file that is not DICOM is refused with a reason, not a 500")
    void notADicomFile() {
        given().spec(Api.as(Api.RADIOGRAPHER))
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "holiday.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
                        "image/jpeg")
                .when().post("/imaging/studies")
                .then().statusCode(400)
                .body("detail", notNullValue());
    }

    @Test
    @Order(11)
    @DisplayName("cancelling is the requester's act and is refused once images exist")
    void cancellingAnAcquiredExaminationIsRefused() {
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("reason", "Ordered in error"))
                .when().post("/imaging/orders/{id}/cancel", orderId)
                .then().statusCode(409)
                .body("detail", notNullValue());
    }

    private static String openEncounter() {
        Instant startsAt = Fixtures.slot(30);
        String appointmentId = given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of("patientId", patient.id(), "patientMrn", patient.mrn(),
                        "clinicianId", clinician.id(), "clinicianName", clinician.fullName(),
                        "departmentCode", clinician.departmentCode(),
                        "startsAt", startsAt.toString(), "durationMinutes", 5))
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
}
