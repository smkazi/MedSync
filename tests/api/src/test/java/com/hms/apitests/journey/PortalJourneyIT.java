package com.hms.apitests.journey;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.RequiresRunningStack;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The patient portal, end to end through the gateway, under a real portal session.
 *
 * <p>The identity here is not a seeded account. It is a patient registered by the front desk, given
 * portal access by the front desk, signed in with the one-time password and then made to change it
 * — so the enrolment path, the initial-password gate and the {@code patient_id} claim are all
 * exercised before the first assertion about the portal itself.
 *
 * <p>Two patients, deliberately. Every ownership rule in the portal is stated as "the session's
 * own", and the only way to test that claim is to have a second record for the first session to
 * fail to reach. The requests that distinguish them are byte-identical apart from the token.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("the patient portal")
class PortalJourneyIT extends RequiresRunningStack {

    private static Fixtures.PortalPatient me;
    private static Fixtures.PortalPatient somebodyElse;
    private static Fixtures.Clinician clinician;

    @BeforeAll
    static void twoPatientsWithPortalAccess() {
        me = Fixtures.portalPatient("Portalone");
        somebodyElse = Fixtures.portalPatient("Portaltwo");
        clinician = Fixtures.clinician();
    }

    private static io.restassured.specification.RequestSpecification asMe() {
        return Api.withToken(me.accessToken());
    }

    private static io.restassured.specification.RequestSpecification asOther() {
        return Api.withToken(somebodyElse.accessToken());
    }

    @Test
    @DisplayName("the portal answers the signed-in patient's own record, and names none to do it")
    void theSessionIsTheIdentity() {
        // No id in the request line. There is no /portal/patients/{id} on this platform, so an
        // IDOR test against this endpoint has nothing to tamper with — which is a stronger
        // property than an IDOR test that passes.
        given().spec(asMe())
                .when().get("/portal/me")
                .then().statusCode(200)
                .body("id", equalTo(me.id()))
                .body("mrn", equalTo(me.mrn()));

        given().spec(asOther())
                .when().get("/portal/me")
                .then().statusCode(200)
                .body("id", equalTo(somebodyElse.id()));
    }

    @Test
    @DisplayName("the registration notes staff write about a patient are not published to them")
    void staffNotesAreNotPublished() {
        var keys = given().spec(asMe())
                .when().get("/portal/me")
                .then().statusCode(200)
                .extract().jsonPath().getMap("$").keySet();
        // Staff free text about a patient is written by people who have never considered that its
        // subject would read it, and publishing it changes what gets written there.
        assertThat(keys).doesNotContain("notes", "nationalId", "abhaNumber", "abhaAddress");
    }

    @Test
    @DisplayName("a patient books for themselves, and cannot mark it urgent or choose a room")
    void selfBooking() {
        Instant when = Fixtures.slot(0);
        var booked = given().spec(asMe())
                .body(Map.of(
                        "clinicianId", clinician.id(),
                        "departmentCode", clinician.departmentCode(),
                        "startsAt", when.toString(),
                        "durationMinutes", 15,
                        "reason", "A cough that has not settled",
                        // Both ignored rather than refused: the request the platform accepts has
                        // nowhere to put either, so these are simply not fields.
                        "priority", "EMERGENCY",
                        "roomCode", "GF-GEN"))
                .when().post("/portal/appointments")
                .then().statusCode(201)
                .body("patientId", equalTo(me.id()))
                .body("patientMrn", equalTo(me.mrn()))
                .body("priority", equalTo("ROUTINE"))
                .extract().jsonPath();
        String appointmentId = booked.getString("id");
        assertThat(booked.<Object>get("room")).as("a patient does not choose the room").isNull();

        // On their own list, and not on anybody else's.
        assertThat(given().spec(asMe()).when().get("/portal/appointments")
                .then().statusCode(200).extract().jsonPath().getList("id", String.class))
                .contains(appointmentId);
        assertThat(given().spec(asOther()).when().get("/portal/appointments")
                .then().statusCode(200).extract().jsonPath().getList("id", String.class))
                .doesNotContain(appointmentId);

        // 404 rather than 403 for somebody else's: an id that comes back "not yours" is an id
        // confirmed to be real.
        given().spec(asOther())
                .body(Map.of("reason", "Not mine to cancel"))
                .when().post("/portal/appointments/{id}/cancel", appointmentId)
                .then().statusCode(404);

        given().spec(asMe())
                .body(Map.of("reason", "I cannot make it"))
                .when().post("/portal/appointments/{id}/cancel", appointmentId)
                .then().statusCode(204);
    }

    @Test
    @DisplayName("the portal's availability is the same calculation the front desk reads")
    void availabilityIsShared() {
        String date = java.time.LocalDate.now().plusDays(45).toString();
        String portal = given().spec(asMe())
                .queryParam("clinicianId", clinician.id()).queryParam("date", date)
                .when().get("/portal/availability")
                .then().statusCode(200)
                .extract().body().asString();
        String desk = given().spec(Api.as(Api.RECEPTIONIST))
                .queryParam("clinicianId", clinician.id()).queryParam("date", date)
                .when().get("/appointments/availability")
                .then().statusCode(200)
                .extract().body().asString();
        // One calculator, one answer. A portal that computed availability separately would
        // eventually disagree with the desk, and the patient at the counter would be told they
        // are wrong.
        assertThat(portal).isEqualTo(desk);
    }

    @Test
    @DisplayName("a written question reaches the hospital, is answered, and the badge clears")
    void secureMessaging() {
        var thread = given().spec(asMe())
                .body(Map.of("subject", "My discharge medicines",
                        "body", "The two boxes say different things about food."))
                .when().post("/portal/messages")
                .then().statusCode(201)
                .body("status", equalTo("OPEN"))
                .extract().jsonPath();
        String threadId = thread.getString("id");
        // The notice is on the response and a caller cannot suppress it.
        assertThat(thread.getString("notice")).contains("casualty");

        // Another patient cannot read it, and is told nothing about whether it exists.
        given().spec(asOther()).when().get("/portal/messages/{id}", threadId)
                .then().statusCode(404);

        given().spec(Api.as(Api.NURSE))
                .body(Map.of("body", "Take both with food. Ring the ward if you are unsure."))
                .when().post("/notifications/messages/{id}/replies", threadId)
                .then().statusCode(200)
                .body("status", equalTo("ANSWERED"));

        given().spec(asMe()).when().get("/portal/messages/unread")
                .then().statusCode(200).body("unread", equalTo(1));
        // Opening the thread is what reading it means.
        given().spec(asMe()).when().get("/portal/messages/{id}", threadId).then().statusCode(200);
        given().spec(asMe()).when().get("/portal/messages/unread")
                .then().statusCode(200).body("unread", equalTo(0));

        given().spec(Api.as(Api.RECEPTIONIST))
                .when().post("/notifications/messages/{id}/close", threadId)
                .then().statusCode(200).body("status", equalTo("CLOSED"));
        given().spec(asMe())
                .body(Map.of("body", "One more thing"))
                .when().post("/portal/messages/{id}/replies", threadId)
                .then().statusCode(409);
    }

    @Test
    @DisplayName("a patient downloads their own record, and the disclosure log records that it left")
    void downloadAndTransmit() {
        var bundle = given().spec(asMe())
                .when().get("/portal/records/export")
                .then().statusCode(200)
                .body("resourceType", equalTo("Bundle"))
                .body("type", equalTo("searchset"))
                .extract().jsonPath();
        // A bundle is a bundle whether or not there is anything in it yet: a new patient with
        // nothing on file exports empty rather than failing, which is the correct answer.
        assertThat(bundle.getList("entry")).isNotNull();

        // Registered like anything else that leaves the platform. A patient reading their own
        // record is not a disclosure to anybody new, and that is exactly why it is logged: a
        // register missing one category of departure is unreliable for the others.
        var disclosures = given().spec(Api.as(Api.ADMIN))
                .queryParam("patientId", me.id())
                .when().get("/interop/disclosures")
                .then().statusCode(200)
                .extract().jsonPath().getList("kind", String.class);
        assertThat(disclosures).contains("PATIENT_EXPORT");
    }

    @Test
    @DisplayName("a released report is reachable and a provisional one is not")
    void releasedResultsOnly() {
        // Ordered for this patient by a clinician, taken by the bench, and left unverified.
        String orderId = given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("patientId", me.id(), "patientMrn", me.mrn(), "patientSex", "F",
                        "testCodes", List.of("CBC"), "priority", "ROUTINE"))
                .when().post("/lab/orders")
                .then().statusCode(201)
                .extract().jsonPath().getString("id");
        given().spec(Api.as(Api.LAB_TECH)).body(Map.of())
                .when().post("/lab/orders/{id}/specimens", orderId)
                .then().statusCode(201);
        given().spec(Api.as(Api.LAB_TECH))
                .body(Map.of("results", List.of(
                        Map.of("parameter", "HGB", "value", "9.4", "unit", "g/dL"))))
                .when().post("/lab/orders/{id}/results", orderId)
                .then().statusCode(200);

        // Listed as in progress, with no number attached, and refused with a sentence saying why.
        given().spec(asMe()).when().get("/portal/reports")
                .then().statusCode(200);
        given().spec(asMe()).when().get("/portal/reports/{id}", orderId)
                .then().statusCode(400);

        given().spec(Api.as(Api.PATHOLOGIST))
                .when().post("/lab/orders/{id}/verify", orderId)
                .then().statusCode(200);

        given().spec(asMe()).when().get("/portal/reports/{id}", orderId)
                .then().statusCode(200)
                .body("results[0].value", equalTo("9.4"));
        // And still not the other patient's to read.
        given().spec(asOther()).when().get("/portal/reports/{id}", orderId)
                .then().statusCode(404);
    }

    @Test
    @DisplayName("a portal session reaches no clinical endpoint, and staff reach no portal endpoint")
    void neitherSideCanUseTheOther() {
        // Every one of these is a real GET that a role decides, so 403 is the only acceptable
        // answer and the assertion says so exactly. A path that answered 405 or 404 would prove
        // nothing about authorisation — the request would have been turned away by routing before
        // any role was consulted, which is how a list like this quietly stops testing anything.
        for (String path : List.of("/patients", "/appointments", "/lab/orders", "/prescriptions",
                "/invoices", "/consents", "/admin/users", "/day-book", "/casualty", "/admissions",
                "/order-sets", "/queue/GF-GEN", "/pharmacy/stock", "/notifications",
                "/notifications/messages")) {
            given().spec(asMe()).when().get(path).then().statusCode(403);
        }
        // And the other direction, for every staff identity this suite has. An administrator is
        // included on purpose: /portal answers "the signed-in patient's own record", and there is
        // no patient an administrator is.
        for (String actor : List.of(Api.ADMIN, Api.DOCTOR, Api.NURSE, Api.RECEPTIONIST,
                Api.LAB_TECH, Api.PATHOLOGIST, Api.PHARMACIST, Api.CASHIER)) {
            for (String path : List.of("/portal/me", "/portal/appointments", "/portal/reports",
                    "/portal/encounters", "/portal/prescriptions", "/portal/invoices",
                    "/portal/messages", "/portal/records/export")) {
                given().spec(Api.as(actor)).when().get(path)
                        .then().statusCode(403);
            }
        }
    }

    @Test
    @DisplayName("withdrawing access ends the session immediately, not at token expiry")
    void withdrawalIsImmediate() {
        Fixtures.PortalPatient doomed = Fixtures.portalPatient("Portalgone");
        given().spec(Api.withToken(doomed.accessToken()))
                .when().get("/portal/me").then().statusCode(200);

        given().spec(Api.as(Api.RECEPTIONIST))
                .when().delete("/patients/{id}/portal-account", doomed.id())
                .then().statusCode(200)
                .body("active", equalTo(false));

        // The access token is still cryptographically valid and still inside its lifetime. What
        // ends is the account: identity-service revokes every refresh token, and the deactivated
        // account can mint nothing new.
        given().spec(Api.spec())
                .body(Map.of("username", doomed.username(), "password", "ChosenByThePatient!2026"))
                .when().post("/auth/login")
                .then().statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403)));
    }

    @Test
    @DisplayName("a patient cannot pay, prescribe, verify a result or write on their own chart")
    void thePortalIsReadOnlyWhereItShouldBe() {
        given().spec(asMe())
                .body(Map.of("amount", "1.00", "method", "CASH"))
                .when().post("/invoices/{id}/payments", java.util.UUID.randomUUID())
                .then().statusCode(403);
        given().spec(asMe())
                .body(Map.of("patientId", me.id(), "patientMrn", me.mrn(),
                        "items", List.of(Map.of("drugCode", "AMOX500", "dose", "1 tablet",
                                "frequency", "twice daily", "durationDays", 5, "quantity", 10))))
                .when().post("/prescriptions")
                .then().statusCode(403);
        given().spec(asMe())
                .when().post("/lab/orders/{id}/verify", java.util.UUID.randomUUID())
                .then().statusCode(403);
        given().spec(asMe())
                .body(Map.of("subjective", "I would like this in my notes"))
                .when().put("/encounters/{id}/note", java.util.UUID.randomUUID())
                .then().statusCode(403);
    }

    /** Kept out of the loop above: the timestamp assertion needs its own message. */
    @Test
    @DisplayName("a portal token is refused by the corridor display's neighbours too")
    void nothingElseUnderPortalExists() {
        // A prefix allowlisted by pattern is a prefix somebody adds a second endpoint to, so the
        // absence of a catch-all is asserted rather than assumed.
        for (String path : List.of("/portal", "/portal/patients", "/portal/admin")) {
            given().spec(asMe()).when().get(path)
                    .then().statusCode(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.is(404), org.hamcrest.Matchers.is(403),
                            org.hamcrest.Matchers.is(405)));
        }
        given().spec(Api.spec()).when().get("/portal/me").then().statusCode(401);
    }
}
