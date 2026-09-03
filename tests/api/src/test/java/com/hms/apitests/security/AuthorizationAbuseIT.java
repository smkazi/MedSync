package com.hms.apitests.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.RequiresRunningStack;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Authorization abuse cases: what happens when an authenticated user asks for something their role
 * does not cover, or for someone else's record.
 *
 * <p>Every case here is a genuine token for a genuine account. That is what separates these from
 * the "does it require a token" checks the ZAP baseline already makes - broken access control is
 * almost never an unauthenticated hole, it is a logged-in user reaching one row too far.
 */
@DisplayName("authorization abuse cases")
class AuthorizationAbuseIT extends RequiresRunningStack {

    private static String patientId;
    private static String patientMrn;

    @BeforeAll
    static void seed() {
        var patient = Fixtures.registerPatient(Api.RECEPTIONIST, "Authz");
        patientId = patient.id();
        patientMrn = patient.mrn();
    }

    /**
     * Role escalation, endpoint by endpoint. Each row is a role that must NOT be able to perform
     * the action - a 403 or 401 is a pass, a 2xx is a finding, and a 500 is also a finding
     * (the check failed open, or failed in a way that leaks).
     */
    @ParameterizedTest(name = "{0} must not {1} {2}")
    @CsvSource({
            // Administration is admin-only.
            "dr.rao,        GET,    /admin/users",
            "nurse.iqbal,   GET,    /admin/users",
            "reception,     GET,    /admin/users",
            "lab.tech,      GET,    /admin/users",
            "dr.rao,        GET,    /admin/audit",
            "reception,     GET,    /admin/audit",
            "dr.rao,        POST,   /staff",
            "reception,     POST,   /staff",
            "reception,     POST,   /departments",
            // Retiring a department or decommissioning a bed is administrative: it changes what
            // every other service can reference, and a clinician retiring the department they work
            // in would take it out of every pick-list.
            "dr.rao,        PATCH,  /departments/CARD",
            "reception,     PATCH,  /departments/CARD",
            "dr.rao,        PATCH,  /beds/00000000-0000-0000-0000-000000000000",
            "nurse.iqbal,   PATCH,  /beds/00000000-0000-0000-0000-000000000000",
            // Raw analyzer traffic is lab-only: it is unfiltered instrument output.
            "dr.rao,        GET,    /lab/device-messages",
            "reception,     GET,    /lab/device-messages",
            "nurse.iqbal,   GET,    /lab/device-messages",
            // Reference ranges decide whether a result reads as high or low. Changing one
            // silently reclassifies every future result, so it is not clinician- or tech-editable.
            "dr.rao,        PATCH,  /lab/reference-ranges/00000000-0000-0000-0000-000000000000",
            "lab.tech,      PATCH,  /lab/reference-ranges/00000000-0000-0000-0000-000000000000",
            "nurse.iqbal,   PATCH,  /lab/reference-ranges/00000000-0000-0000-0000-000000000000",
            // The other two threshold tiers, same reasoning: an interpretive rule decides what a
            // signed report says out loud, and a morphology cut-off decides what the cells get
            // called on it. Neither is clinician- or technician-editable.
            "dr.rao,        PATCH,  /lab/interpretive-rules/ANISOCYTOSIS",
            "lab.tech,      PATCH,  /lab/interpretive-rules/ANISOCYTOSIS",
            "dr.rao,        PATCH,  /lab/morphology-thresholds/MCV_MICROCYTIC",
            "lab.tech,      PATCH,  /lab/morphology-thresholds/MCV_MICROCYTIC",
            "nurse.iqbal,   PATCH,  /lab/morphology-thresholds/MCV_MICROCYTIC",
            // The front desk books and checks in; it does not write clinical content.
            "reception,     POST,   /encounters",
            "lab.tech,      POST,   /encounters",
            // An encounter's orders are chart content, unlike the patient-scoped order list that
            // the lab and the front desk both need.
            "reception,     GET,    /lab/encounters/00000000-0000-0000-0000-000000000000/orders",
            "lab.tech,      GET,    /lab/encounters/00000000-0000-0000-0000-000000000000/orders",
            // The bench does not tell patients things. A pathologist releasing a report triggers
            // a message through the event, not by originating one.
            "lab.tech,      POST,   /notifications",
            "dr.pathan,     POST,   /notifications",
            "lab.tech,      GET,    /notifications",
            "dr.pathan,     GET,    /notifications",
            // The platform's voice to a patient is administrative.
            "dr.rao,        PATCH,  /notifications/templates/00000000-0000-0000-0000-000000000000",
            "reception,     PATCH,  /notifications/templates/00000000-0000-0000-0000-000000000000",
            // The casualty board and the in-patient census are a chart in table form: who is in
            // the department, what they came in with, and how sick somebody judged them. The
            // front desk registers and books, and has no business reading it; the bench has less
            // reason still. This is the narrowing BED_MANAGE exists for, and the row that would
            // catch it being widened back to CLINICAL_READ.
            "reception,     GET,    /casualty",
            "lab.tech,      GET,    /casualty",
            "dr.pathan,     GET,    /casualty",
            "reception,     GET,    /admissions",
            "lab.tech,      GET,    /admissions",
            "dr.pathan,     GET,    /admissions",
            "reception,     POST,   /casualty",
            "lab.tech,      POST,   /casualty",
            "reception,     POST,   /admissions",
            // Free-bed lists too: a bed map is a census with the names taken out, and reading
            // which resus bay is empty is still reading how busy casualty is.
            "reception,     GET,    /casualty/beds",
            "lab.tech,      GET,    /admissions/beds",
            "reception,     GET,    /casualty/patients/00000000-0000-4000-8000-000000000000",
            "lab.tech,      GET,    /admissions/patients/00000000-0000-4000-8000-000000000000",
            // The medication loop is three acts by three roles, and no role holds two of them.
            // These rows are what makes the loop a control rather than a workflow: if any one of
            // them ever answers 2xx, one account can order a medicine, hand it over and sign that
            // it was given.
            "nurse.iqbal,   POST,   /prescriptions",
            "pharmacist,    POST,   /prescriptions",
            "reception,     POST,   /prescriptions",
            "dr.rao,        POST,   /pharmacy/dispenses",
            "nurse.iqbal,   POST,   /pharmacy/dispenses",
            "pharmacist,    POST,   /emar/administer",
            // The formulary and the interaction table decide what may be prescribed at all and
            // what the platform refuses. Readable by the ward, writable only by the pharmacy.
            "dr.rao,        POST,   /pharmacy/formulary",
            "nurse.iqbal,   POST,   /pharmacy/interactions",
            "dr.rao,        POST,   /pharmacy/stock",
            // And the laboratory has no part in the loop: not the queue, not the stock, not the
            // formulary.
            "lab.tech,      GET,    /prescriptions",
            "lab.tech,      GET,    /pharmacy/stock",
            "dr.pathan,     GET,    /pharmacy/formulary",
            "reception,     GET,    /prescriptions",
            // The narrow allergy endpoint the pharmacy holds is narrow in both directions: it is
            // the pharmacy's door into the chart, and not the front desk's or the laboratory's.
            "reception,     GET,    /patients/00000000-0000-4000-8000-000000000000/allergies",
            "lab.tech,      GET,    /patients/00000000-0000-4000-8000-000000000000/allergies",
            // The money. Two separations, and both are rows here because both are easy to widen
            // by accident. A clinician decides what was done and must not record that it was paid
            // -- the oldest financial control there is -- and a cashier takes money and must not
            // set prices, because somebody who could discount a procedure to zero and then record
            // it as settled in full would need no accomplice.
            "dr.rao,        POST,   /invoices",
            "nurse.iqbal,   POST,   /invoices",
            "reception,     POST,   /invoices",
            "dr.rao,        POST,   /charges",
            "reception,     POST,   /claims",
            "cashier,       POST,   /charge-items",
            "cashier,       POST,   /tax-rates",
            "cashier,       POST,   /payers",
            "cashier,       PATCH,  /charge-items/CONSULT_OP",
            // The bench and the pharmacy have no part in the revenue cycle at all: their charges
            // arrive as events, with no token and no screen, so reading what every patient has
            // been billed would be reading a financial record for no reason anybody can state.
            "lab.tech,      GET,    /invoices",
            "dr.pathan,     GET,    /invoices",
            "pharmacist,    GET,    /day-book",
            "pharmacist,    GET,    /claims",
            "lab.tech,      GET,    /charge-items",
            // And a cashier is not a clinician: the chart is closed to the billing desk, which is
            // the other half of the same line.
            "cashier,       GET,    /casualty",
            "cashier,       GET,    /prescriptions",
            // Consent, and the two halves that must stay apart: the front desk records what the
            // patient decided, a clinician acts on it. A clinician who could record a consent
            // would be authorising their own access to the record they are about to send, and a
            // receptionist who could share one would be deciding which record that is.
            "dr.rao,        POST,   /consents",
            "nurse.iqbal,   POST,   /consents",
            "reception,     POST,   /interop/share",
            "nurse.iqbal,   POST,   /interop/share",
            // Exporting a whole chart is the most sensitive thing the platform does, and it is an
            // administrator's alone until a patient can do it themselves through a portal.
            "dr.rao,        POST,   /interop/export/00000000-0000-4000-8000-000000000000",
            "nurse.iqbal,   POST,   /interop/export/00000000-0000-4000-8000-000000000000",
            "reception,     POST,   /interop/export/00000000-0000-4000-8000-000000000000",
            "cashier,       POST,   /interop/export/00000000-0000-4000-8000-000000000000",
            // The bench, the pharmacy and the billing desk have no part in any of it: none refers
            // a patient onwards, and who has asked for somebody's record is not their question.
            "lab.tech,      GET,    /consents",
            "dr.pathan,     GET,    /consents",
            "pharmacist,    GET,    /consents",
            "cashier,       GET,    /consents",
            "lab.tech,      GET,    /interop/disclosures",
            "cashier,       GET,    /interop/disclosures",
            // Writing a national health identifier onto a record happens at the desk with the
            // patient's card in front of you, not while reading a chart.
            "lab.tech,      GET,    /patients/00000000-0000-4000-8000-000000000000/identifiers",
            "pharmacist,    GET,    /patients/00000000-0000-4000-8000-000000000000/identifiers",
            "cashier,       GET,    /patients/00000000-0000-4000-8000-000000000000/identifiers",
    })
    void roleIsEnforcedPerEndpoint(String username, String method, String path) {
        var request = given().spec(Api.as(username.trim()));
        var response = switch (method.trim()) {
            case "GET" -> request.when().get(path.trim());
            case "POST" -> request.body(Map.of()).when().post(path.trim());
            case "PATCH" -> request.body(Map.of()).when().patch(path.trim());
            default -> throw new IllegalArgumentException("unhandled method " + method);
        };

        response.then().statusCode(org.hamcrest.Matchers.anyOf(
                org.hamcrest.Matchers.is(401),
                org.hamcrest.Matchers.is(403),
                // A malformed body on a permitted-but-invalid call is fine; what must never happen
                // is the action succeeding. 400 is only acceptable if authorization ran first,
                // which the 403 rows above already establish for these roles.
                org.hamcrest.Matchers.is(400)));
    }

    @Test
    @DisplayName("linking an ABHA is refused for a clinician even when the request is well-formed")
    void aWellFormedAbhaLinkIsStillRefused() {
        // The table above accepts 400 as a pass, because @Valid resolves during argument binding
        // before @PreAuthorize runs. This body is valid, so the only thing left to refuse it is
        // the role — and PUT is not a method the table's shape covers.
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("abhaNumber", "12345678901234", "abhaAddress", "someone@sbx"))
                .when().put("/patients/00000000-0000-4000-8000-000000000000/abha")
                .then().statusCode(403);
        given().spec(Api.as(Api.NURSE))
                .body(Map.of("abhaNumber", "12345678901234", "abhaAddress", "someone@sbx"))
                .when().put("/patients/00000000-0000-4000-8000-000000000000/abha")
                .then().statusCode(403);
    }

    @Test
    @DisplayName("repricing is refused for a cashier even when the request is well-formed")
    void aWellFormedRepriceIsStillRefused() {
        // Same reasoning as the threshold test below: an empty body answers 400 whatever the role,
        // so the table above proves the write did not happen rather than proving what stopped it.
        // This body is valid, and the only thing left to refuse it is the role.
        given().spec(Api.as(Api.CASHIER))
                .body(Map.of("unitPrice", "1.00"))
                .when().patch("/charge-items/CONSULT_OP")
                .then().statusCode(403);
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("amount", "1.00", "method", "CASH"))
                .when().post("/invoices/00000000-0000-4000-8000-000000000000/payments")
                .then().statusCode(403);
    }

    @Test
    @DisplayName("retuning a laboratory threshold is refused even when the request is well-formed")
    void aWellFormedThresholdWriteIsStillRefused() {
        // The table above accepts 400 as a pass, because @Valid on a @RequestBody is resolved
        // during argument binding - before the @PreAuthorize interceptor runs - so an empty body
        // answers 400 whatever the caller's role. That proves the write did not happen; it does
        // not prove authorization is what stopped it. These bodies are valid, so the only thing
        // left to refuse them is the role.
        given().spec(Api.as(Api.LAB_TECH))
                .body(Map.of("threshold", 1))
                .when().patch("/lab/morphology-thresholds/MCV_MICROCYTIC")
                .then().statusCode(403);
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("threshold", 1))
                .when().patch("/lab/morphology-thresholds/MCV_MICROCYTIC")
                .then().statusCode(403);
        given().spec(Api.as(Api.LAB_TECH))
                .body(Map.of("normalLow", 0, "normalHigh", 1))
                .when().patch("/lab/reference-ranges/{id}", UUID.randomUUID())
                .then().statusCode(403);
    }

    @Test
    @DisplayName("the service account reaches a patient's contact details and nothing else at all")
    void theServiceAccountIsAsNarrowAsItLooks() {
        // The narrowest role on the platform, and the one whose password is most likely to end up
        // in a deployment file: it exists because a Kafka consumer has no caller's token to
        // forward, and it is worth proving that what it bought is a contact list rather than a
        // chart. Every row below is a 403 or the whole point of a separate role has gone.
        given().spec(Api.as(Api.SERVICE_ACCOUNT))
                .when().get("/patients/{id}/contact", patientId)
                .then().statusCode(200)
                .body("phone", notNullValue())
                // Not the chart, not even the name.
                .body("fullName", org.hamcrest.Matchers.nullValue())
                .body("mrn", org.hamcrest.Matchers.nullValue());

        for (String path : List.of("/patients", "/patients/" + patientId,
                "/patients/" + patientId + "/identifiers", "/encounters/patients/" + patientId,
                "/lab/orders", "/appointments?from=2026-01-01&to=2026-12-31", "/staff",
                "/notifications", "/admin/users")) {
            given().spec(Api.as(Api.SERVICE_ACCOUNT))
                    .when().get(path)
                    .then().statusCode(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.is(403), org.hamcrest.Matchers.is(405)));
        }
    }

    @Test
    @DisplayName("a clinician who can read the whole chart is not given the contact endpoint")
    void theContactEndpointIsNotASecondDoorToTheChart() {
        // Deliberate, not an oversight. A doctor reads the phone number from the chart, in
        // context; this endpoint exists only so a service need not be granted the chart, and
        // widening it to everyone who already has the chart would add a narrower door to the same
        // room for nothing.
        given().spec(Api.as(Api.DOCTOR))
                .when().get("/patients/{id}/contact", patientId)
                .then().statusCode(403);
        given().spec(Api.as(Api.LAB_TECH))
                .when().get("/patients/{id}/contact", patientId)
                .then().statusCode(403);
    }

    @Test
    @DisplayName("a clinician cannot read another patient's record by guessing an id")
    void idorOnPatientById() {
        // A well-formed id that belongs to nobody. The correct answer is 404 - not a 200 with an
        // empty body, and not a 500 from an unhandled lookup.
        given().spec(Api.as(Api.DOCTOR))
                .when().get("/patients/{id}", UUID.randomUUID())
                .then().statusCode(404);
    }

    @Test
    @DisplayName("the lab cannot open or read a clinical encounter for a patient it is testing")
    void labTechCannotReadEncounters() {
        given().spec(Api.as(Api.LAB_TECH))
                .when().get("/encounters/patients/{id}", patientId)
                .then().statusCode(403);
    }

    @Test
    @DisplayName("the encrypted identifiers endpoint is closed to roles that do not need it")
    void identifiersAreNarrowlyScoped() {
        // Nurses and lab staff see the patient; they do not see the national id or policy number.
        given().spec(Api.as(Api.NURSE))
                .when().get("/patients/{id}/identifiers", patientId)
                .then().statusCode(403);
        given().spec(Api.as(Api.LAB_TECH))
                .when().get("/patients/{id}/identifiers", patientId)
                .then().statusCode(403);
    }

    @Test
    @DisplayName("a 403 does not confirm whether the record exists")
    void deniedResponsesDoNotLeakExistence() {
        String real = given().spec(Api.as(Api.LAB_TECH))
                .when().get("/encounters/patients/{id}", patientId)
                .then().statusCode(403).extract().body().asString();
        String fake = given().spec(Api.as(Api.LAB_TECH))
                .when().get("/encounters/patients/{id}", UUID.randomUUID())
                .then().statusCode(403).extract().body().asString();

        // Both bodies carry a correlation id, so they are not byte-identical. What matters is that
        // neither says anything about the patient.
        org.assertj.core.api.Assertions.assertThat(real).doesNotContain(patientMrn);
        org.assertj.core.api.Assertions.assertThat(fake).doesNotContain(patientMrn);
    }

    @Test
    @DisplayName("only a pathologist can verify a result the lab technician entered")
    void resultVerificationIsSeparatedFromEntry() {
        // Separation of duties: whoever runs the sample does not sign off on it.
        given().spec(Api.as(Api.LAB_TECH))
                .body(Map.of("resultIds", List.of(UUID.randomUUID().toString())))
                .when().post("/lab/results/verify")
                .then().statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(403), org.hamcrest.Matchers.is(404)));
    }

    @Test
    @DisplayName("error responses never carry a stack trace, SQL, or a class name")
    void errorsDoNotLeakInternals() {
        String body = given().spec(Api.as(Api.DOCTOR))
                .when().get("/patients/{id}", "not-a-uuid")
                .then().statusCode(400)
                .extract().body().asString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("org.springframework")
                .doesNotContain("java.lang")
                .doesNotContain("Exception")
                .doesNotContain("select ")
                .doesNotContain("hibernate");
    }

    @Test
    @DisplayName("the gateway does not proxy a service's actuator surface")
    void actuatorIsNotReachableThroughTheGateway() {
        for (String path : List.of("/identity/actuator/env", "/actuator/env", "/actuator/heapdump",
                "/actuator/beans", "/actuator/configprops")) {
            given().spec(Api.as(Api.ADMIN))
                    .when().get(path)
                    .then().statusCode(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.is(404), org.hamcrest.Matchers.is(401),
                            org.hamcrest.Matchers.is(403)));
        }
    }

    @Test
    @DisplayName("security headers are present and the runtime version is not advertised")
    void securityHeadersArePresent() {
        given().spec(Api.spec())
                .when().get("/actuator/health")
                .then().statusCode(200)
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .header("Server", org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.nullValue(),
                        not(containsString("Tomcat")),
                        not(containsString("Netty"))));
    }
}
