package com.hms.apitests.journey;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.RequiresRunningStack;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Outbound messaging, black box through the gateway.
 *
 * <p>The assertion that carries the weight is the PHI one, and it is here rather than only in
 * notification-service's own suite because the rule has to hold on the deployed thing: a template
 * reworded on a running system, a channel substituted at runtime, and a contact resolved by a
 * service account over the network are all outside the reach of a Spring context test.
 */
@DisplayName("outbound messaging")
class NotificationJourneyIT extends RequiresRunningStack {

    private static String patientId;
    private static String patientMrn;
    private static String fullName;

    @BeforeAll
    static void seed() {
        var patient = Fixtures.registerPatient(Api.RECEPTIONIST, "Notify");
        patientId = patient.id();
        patientMrn = patient.mrn();
        fullName = patient.fullName();
    }

    private static JsonPath send(String username, Map<String, Object> body) {
        return given().spec(Api.as(username))
                .body(body)
                .when().post("/notifications")
                .then().statusCode(201)
                .extract().jsonPath();
    }

    @Test
    @DisplayName("a released-report message says a report is ready and nothing about what it says")
    void theMessageCarriesNoPhi() {
        JsonPath sent = send(Api.DOCTOR, Map.of(
                "category", "LAB_REPORT_READY",
                "channel", "SMS",
                "patientId", patientId,
                "reference", "journey-" + UUID.randomUUID()));

        String body = sent.getString("body");
        String subject = sent.getString("subject");
        String everything = (body + " " + (subject == null ? "" : subject))
                .toLowerCase(java.util.Locale.ROOT);

        assertThat(body).contains("ready");
        assertThat(everything)
                .as("no name, no MRN, no value, no flag, no diagnosis")
                .doesNotContain(fullName.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain(patientMrn.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("haemoglobin")
                .doesNotContain("abnormal");
    }

    @Test
    @DisplayName("the delivery log records what was sent, and to which address")
    void theDeliveryLogIsTheRecord() {
        String reference = "journey-log-" + UUID.randomUUID();
        send(Api.RECEPTIONIST, Map.of(
                "category", "APPOINTMENT_CONFIRMED",
                "channel", "LOG",
                "patientId", patientId,
                "reference", reference,
                "when", "12 March, 10:30"));

        List<Map<String, Object>> rows = given().spec(Api.as(Api.ADMIN))
                .when().get("/notifications/patients/{id}", patientId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");

        assertThat(rows)
                .as("the row for this reference exists and says what happened")
                .anySatisfy(row -> {
                    assertThat(row.get("reference")).isEqualTo(reference);
                    assertThat(row.get("status")).isIn("SENT", "SUPPRESSED");
                    assertThat((String) row.get("body")).contains("12 March, 10:30");
                });
    }

    @Test
    @DisplayName("the same message asked for twice is one message")
    void aReplayIsNotASecondMessage() {
        Map<String, Object> request = Map.of(
                "category", "PORTAL_MESSAGE",
                "channel", "LOG",
                "patientId", patientId,
                "reference", "journey-replay-" + UUID.randomUUID());

        String first = send(Api.NURSE, request).getString("id");
        String second = send(Api.NURSE, request).getString("id");

        // The same row. A redelivered event and a double-clicked button are the same problem.
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("the platform says which channels it really has rather than pretending")
    void capabilitiesAreHonest() {
        JsonPath capabilities = given().spec(Api.as(Api.ADMIN))
                .when().get("/notifications/capabilities")
                .then().statusCode(200)
                .extract().jsonPath();

        // LOG always exists. Whether SMS and EMAIL do depends on the deployment, and a screen is
        // entitled to know: asking for a channel that is not configured falls back to the log
        // rather than failing, which is right at runtime and misleading in a UI.
        assertThat(capabilities.getList("channels", String.class)).contains("LOG");
    }

    @Test
    @DisplayName("a template cannot be reworded into a disclosure, even by an administrator")
    void theClosedPlaceholderSetHoldsOnTheDeployedSystem() {
        String templateId = given().spec(Api.as(Api.ADMIN))
                .when().get("/notifications/templates")
                .then().statusCode(200)
                .extract().jsonPath().getString("[0].id");

        given().spec(Api.as(Api.ADMIN))
                .body(Map.of("body", "Your result of {value} is ready. {portalUrl}"))
                .when().patch("/notifications/templates/{id}", templateId)
                .then().statusCode(400)
                .body("detail", org.hamcrest.Matchers.containsString("{value}"));

        // And the stored template is untouched, so a refused rewrite cannot have half-applied.
        given().spec(Api.as(Api.ADMIN))
                .when().get("/notifications/templates")
                .then().statusCode(200)
                .body("[0].body", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("{value}")));
    }

    @Test
    @DisplayName("an unknown category is refused rather than sent as an empty message")
    void anUnknownCategoryIsRefused() {
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("category", "NOT_A_CATEGORY", "channel", "LOG", "patientId", patientId))
                .when().post("/notifications")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("a message with no patient is refused: there is nobody to send it to")
    void aMessageNeedsAPatient() {
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("category", "PORTAL_MESSAGE", "channel", "LOG"))
                .when().post("/notifications")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("a patient with nothing on file is recorded as not sent, not silently dropped")
    void anUnreachablePatientIsRecorded() {
        // Registered with no phone and no email. "The patient was never told" needs evidence
        // behind it, and a message that silently never existed leaves the front desk believing
        // the opposite.
        String unreachable = given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of("firstName", "Unreachable", "lastName", "Case" + UUID.randomUUID()
                                .toString().substring(0, 6),
                        "dateOfBirth", "1975-06-01", "sex", "FEMALE"))
                .when().post("/patients")
                .then().statusCode(201)
                .extract().jsonPath().getString("id");

        send(Api.DOCTOR, Map.of("category", "LAB_REPORT_READY", "channel", "LOG",
                        "patientId", unreachable))
                .prettyPeek();

        given().spec(Api.as(Api.ADMIN))
                .when().get("/notifications/patients/{id}", unreachable)
                .then().statusCode(200)
                .body("[0].status", equalTo("SUPPRESSED"))
                .body("[0].recipient", org.hamcrest.Matchers.nullValue())
                // The body is still recorded: what the platform would have said is part of the
                // evidence, not something to reconstruct later from a template that has since
                // been reworded.
                .body("[0].body", org.hamcrest.Matchers.containsString("ready"));
    }
}
