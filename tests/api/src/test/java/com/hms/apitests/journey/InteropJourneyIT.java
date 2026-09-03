package com.hms.apitests.journey;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.RequiresRunningStack;
import io.restassured.path.json.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consent and disclosure through the gateway, across four services.
 *
 * <p>What this buys over interop-service's own suite is the part that cannot be tested inside one
 * service: the bundle is composed from patient-service and scheduling-service over real HTTP with
 * a real forwarded token, so "a doctor's own authority is what reads the chart" is proven rather
 * than mocked. The service test stubs that client — it has to, since nothing else runs beside it.
 *
 * <p>Two identities, because the module's whole design is that they are different people:
 * {@code reception} records what the patient decided, {@code dr.rao} sends a record under it, and
 * neither can do the other's half.
 */
@DisplayName("consent and health-information exchange")
class InteropJourneyIT extends RequiresRunningStack {

    private static Fixtures.Patient patient;
    private static Fixtures.Clinician clinician;
    private static String encounterId;

    @BeforeAll
    static void seed() {
        patient = Fixtures.registerPatient(Api.RECEPTIONIST, "Consent");
        clinician = Fixtures.clinician();

        // An encounter hangs off an appointment, which needs a clinician of this run's own: the
        // booking exclusion constraint is over (clinician, time range), so a shared one would
        // collide with a previous run.
        String appointmentId = given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of("patientId", patient.id(), "patientMrn", patient.mrn(),
                        "clinicianId", clinician.id(), "clinicianName", clinician.fullName(),
                        "departmentCode", clinician.departmentCode(),
                        "startsAt", Fixtures.slot(0).toString(), "durationMinutes", 15))
                .when().post("/appointments")
                .then().statusCode(201)
                .extract().jsonPath().getString("id");

        encounterId = given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("appointmentId", appointmentId, "patientId", patient.id(),
                        "patientMrn", patient.mrn(), "clinicianId", clinician.id(),
                        "departmentCode", clinician.departmentCode(),
                        "encounterType", "OUTPATIENT"))
                .when().post("/encounters")
                .then().statusCode(201)
                .extract().jsonPath().getString("id");

        // A signed note, so the bundle carries a narrative rather than an empty document.
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("subjective", "Sore throat for three days",
                        "objective", "Pharynx inflamed",
                        "assessment", "Viral pharyngitis",
                        "plan", "Fluids and review"))
                .when().put("/encounters/" + encounterId + "/note")
                .then().statusCode(200);
        given().spec(Api.as(Api.DOCTOR))
                .when().post("/encounters/" + encounterId + "/note/sign")
                .then().statusCode(200);

        // Vitals and a diagnosis as well, so the bundle carries observations and a condition
        // rather than three resources and a narrative — the fan-out across services is the thing
        // this suite exists to prove, and it is only visible when there is something to fan out.
        given().spec(Api.as(Api.NURSE))
                .body(Map.of("heartRate", 88, "systolicBp", 128, "diastolicBp", 82,
                        "respiratoryRate", 18, "oxygenSaturation", 97))
                .when().post("/encounters/" + encounterId + "/vitals")
                .then().statusCode(201);
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("icd10Code", "J06.9",
                        "description", "Acute upper respiratory infection",
                        "category", "PRIMARY"))
                .when().post("/encounters/" + encounterId + "/diagnoses")
                .then().statusCode(201);
    }

    private static Map<String, Object> consentBody(Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientId", patient.id());
        body.put("patientMrn", patient.mrn());
        body.put("requester", "A referring clinic");
        body.put("purposeCode", "CARE_MANAGEMENT");
        body.put("hiTypes", List.of("OP_CONSULTATION"));
        body.put("coversFrom", LocalDate.now().minusYears(1).toString());
        body.put("coversTo", LocalDate.now().toString());
        body.put("expiresAt", Instant.now().plus(30, ChronoUnit.DAYS).toString());
        body.putAll(overrides);
        return body;
    }

    private static String requestConsent(Map<String, Object> overrides) {
        return given().spec(Api.as(Api.RECEPTIONIST))
                .body(consentBody(overrides))
                .when().post("/consents")
                .then().statusCode(201)
                .extract().jsonPath().getString("artefactId");
    }

    private static void grant(String artefactId) {
        given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of("signature", "signed by a consent manager"))
                .when().post("/consents/" + artefactId + "/grant")
                .then().statusCode(200);
    }

    private static JsonPath share(String artefactId, String hiType, String recordId, int expected) {
        return given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("artefactId", artefactId, "hiType", hiType, "recordId", recordId))
                .when().post("/interop/share")
                .then().statusCode(expected)
                .extract().jsonPath();
    }

    @Test
    @DisplayName("granted, shared, and recorded — with the bundle built from two other services")
    void theWholeExchange() {
        String artefactId = requestConsent(Map.of());
        assertThat(share(artefactId, "OP_CONSULTATION", encounterId, 409).getString("detail"))
                .as("a pending request is not permission")
                .contains("not yet granted");

        grant(artefactId);
        JsonPath shared = share(artefactId, "OP_CONSULTATION", encounterId, 200);
        assertThat(shared.getInt("resourceCount"))
                .as("Composition, Patient, Encounter, observations and a condition — assembled "
                        + "over real HTTP from patient-service and scheduling-service")
                .isGreaterThan(3);
        assertThat(shared.getBoolean("transmitted"))
                .as("the default adapter records and sends nothing, and says so")
                .isFalse();

        JsonPath disclosures = given().spec(Api.as(Api.DOCTOR))
                .when().get("/interop/disclosures?patientId=" + patient.id())
                .then().statusCode(200)
                .extract().jsonPath();
        assertThat(disclosures.getList("kind")).contains("CONSENTED_SHARE");
        assertThat(disclosures.getString("[0].releasedBy")).isEqualTo(Api.DOCTOR);
    }

    @Test
    @DisplayName("each of the four conditions refuses on its own, naming consent")
    void theFourRefusals() {
        String artefactId = requestConsent(Map.of());
        grant(artefactId);

        // The wrong kind of record.
        assertThat(share(artefactId, "PRESCRIPTION", UUID.randomUUID().toString(), 409)
                .getString("detail"))
                .contains("does not cover")
                .contains("consent for one kind of record is not consent for another");

        // A record outside the covered period: this consent covers up to today, and the encounter
        // is from today, so a consent that ended yesterday is the one that refuses.
        String narrow = requestConsent(Map.of(
                "coversFrom", LocalDate.now().minusDays(30).toString(),
                "coversTo", LocalDate.now().minusDays(1).toString()));
        grant(narrow);
        assertThat(share(narrow, "OP_CONSULTATION", encounterId, 409).getString("detail"))
                .contains("covers records dated");

        // Revoked.
        given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of("reason", "The patient withdrew it"))
                .when().post("/consents/" + artefactId + "/revoke")
                .then().statusCode(200);
        assertThat(share(artefactId, "OP_CONSULTATION", encounterId, 409).getString("detail"))
                .contains("revoked").contains("withdrew it");

        // And one that does not exist at all.
        assertThat(share("LOCAL-NOT-A-CONSENT", "OP_CONSULTATION", encounterId, 404)
                .getString("detail"))
                .contains("Nothing can be shared without one");
    }

    @Test
    @DisplayName("recording a decision and acting on one are different authorities")
    void separationOfDuties() {
        String artefactId = requestConsent(Map.of());

        // A clinician cannot record the patient's decision: that would be authorising their own
        // access to the record they are about to send.
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of())
                .when().post("/consents/" + artefactId + "/grant")
                .then().statusCode(403);

        grant(artefactId);
        // And the front desk cannot decide which record is the one to send.
        given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of("artefactId", artefactId, "hiType", "OP_CONSULTATION",
                        "recordId", encounterId))
                .when().post("/interop/share")
                .then().statusCode(403);

        // Exporting a whole chart is an administrator's alone.
        given().spec(Api.as(Api.DOCTOR))
                .when().post("/interop/export/" + patient.id())
                .then().statusCode(403);
    }

    @Test
    @DisplayName("an export is FHIR, is recorded, and needs no consent because it is the patient's own")
    void exportIsTheOtherPath() {
        JsonPath export = given().spec(Api.as(Api.ADMIN))
                .when().post("/interop/export/" + patient.id() + "?encounterId=" + encounterId)
                .then().statusCode(200)
                .extract().jsonPath();

        assertThat(export.getString("resourceType")).isEqualTo("Bundle");
        assertThat(export.getString("type")).isEqualTo("searchset");
        assertThat(export.getString("entry[0].resource.type")).isEqualTo("document");
        assertThat(export.getString("entry[0].resource.entry[0].resource.resourceType"))
                .as("R4 requires a document bundle to be led by its Composition")
                .isEqualTo("Composition");

        JsonPath disclosures = given().spec(Api.as(Api.ADMIN))
                .when().get("/interop/disclosures?patientId=" + patient.id())
                .then().statusCode(200)
                .extract().jsonPath();
        assertThat(disclosures.getList("kind")).contains("PATIENT_EXPORT");
    }

    @Test
    @DisplayName("an ABHA is linked at the desk, encrypted, and never in an ordinary response")
    void abhaIsLinkedAndHidden() {
        JsonPath linked = given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of("abhaNumber", "12-3456-7890-1234",
                        "abhaAddress", "api." + Fixtures.RUN.toLowerCase() + "@sbx"))
                .when().put("/patients/" + patient.id() + "/abha")
                .then().statusCode(200)
                .extract().jsonPath();
        assertThat(linked.getString("abhaNumber"))
                .as("a national identifier does not appear in an ordinary patient response")
                .isNull();

        JsonPath identifiers = given().spec(Api.as(Api.DOCTOR))
                .when().get("/patients/" + patient.id() + "/identifiers")
                .then().statusCode(200)
                .extract().jsonPath();
        assertThat(identifiers.getString("abhaNumber"))
                .as("stored without its grouping, so two records cannot differ by punctuation")
                .isEqualTo("12345678901234");

        // A doctor reads a number a referral quotes and does not write one.
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("abhaNumber", "99999999999999", "abhaAddress", "someone@sbx"))
                .when().put("/patients/" + patient.id() + "/abha")
                .then().statusCode(403);
    }

    @Test
    @DisplayName("a bundle carries the MRN and never the ABHA number")
    void bundlesCarryNoNationalIdentifier() {
        String artefactId = requestConsent(Map.of());
        grant(artefactId);
        share(artefactId, "OP_CONSULTATION", encounterId, 200);

        JsonPath export = given().spec(Api.as(Api.ADMIN))
                .when().post("/interop/export/" + patient.id() + "?encounterId=" + encounterId)
                .then().statusCode(200)
                .extract().jsonPath();
        String body = export.prettify();
        assertThat(body).contains(patient.mrn());
        assertThat(body.toLowerCase())
                .as("an ABDM push addresses the patient at the gateway; a bundle carrying the "
                        + "number too would put a national identifier in every payload")
                .doesNotContain("abha")
                .doesNotContain("12345678901234");
    }
}
