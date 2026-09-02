package com.hms.apitests.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.RequiresRunningStack;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The initial-password gate, from outside.
 *
 * <p>An account that has not changed the password it was issued with gets a session that can do
 * exactly one useful thing: change that password. The mechanism is a token minted with no roles,
 * so every {@code @PreAuthorize} on the platform refuses it without any service knowing the flag
 * exists — which is the reason this suite is black box and reaches across all four business
 * services rather than testing identity alone. A gate that only holds where somebody remembered
 * to check it is not a gate.
 *
 * <p>The fixture is the seeded {@code new.starter} account. It genuinely holds RECEPTIONIST in the
 * database, so a row below that comes back 2xx would mean the flag was ignored rather than that
 * the role was missing.
 */
@DisplayName("the initial-password gate")
class InitialPasswordGateIT extends RequiresRunningStack {

    private static final String FRESH_ACCOUNT = "new.starter";

    private static String freshToken;

    @BeforeAll
    static void signInAsTheFreshAccount() {
        freshToken = Api.login(FRESH_ACCOUNT, Api.SEED_PASSWORD).accessToken();
    }

    @Test
    @DisplayName("signing in works, and says why the session is useless")
    void loginSucceedsAndReportsTheFlag() {
        // Refusing the login itself would be simpler and would strand the account: changing the
        // password needs a token. So the sign-in succeeds and the response says what is wrong.
        given().spec(Api.spec())
                .body(Map.of("username", FRESH_ACCOUNT, "password", Api.SEED_PASSWORD))
                .when().post("/auth/login")
                .then().statusCode(200)
                .body("user.mustChangePassword", is(true))
                // The roles are reported, because a client needs to know who is signed in. They
                // are reported from the database, not from the token, which carries none.
                .body("user.roles", org.hamcrest.Matchers.hasItem("RECEPTIONIST"));
    }

    /**
     * Every business service, reached through the gateway. Reception can legitimately do the first
     * four of these with a normal session, which is what makes them the interesting rows.
     */
    @ParameterizedTest(name = "{0} {1} is refused")
    @CsvSource({
            "GET,    /patients",
            "GET,    /appointments",
            "GET,    /staff",
            "GET,    /departments",
            "GET,    /rooms",
            "GET,    /beds",
            "GET,    /schedules/clinicians/33333333-0000-4000-8000-000000000002",
            "GET,    /lab/orders",
            "GET,    /lab/catalog",
            "GET,    /encounters/patients/00000000-0000-4000-8000-000000000000",
            "GET,    /admin/users",
    })
    void everyBusinessEndpointIsRefused(String method, String path) {
        var request = given().spec(Api.withToken(freshToken));
        var response = switch (method.trim()) {
            case "GET" -> request.when().get(path.trim());
            default -> throw new IllegalArgumentException("unhandled method " + method);
        };

        // 403 is the expected answer: the token is valid, the authority is not there. 401 is
        // acceptable too. Anything 2xx means a role-less token reached data.
        response.then().statusCode(anyOf(is(401), is(403)));
    }

    @Test
    @DisplayName("a well-formed write is refused by authorization, not by validation")
    void aValidWriteIsStillRefused() {
        // Deliberately a body the service would accept. An empty one comes back 400 from bean
        // validation, which happens during argument binding and so before @PreAuthorize runs -
        // a pass that would have proved nothing about the gate.
        given().spec(Api.withToken(freshToken))
                .body(Map.of(
                        "firstName", "Gate",
                        "lastName", "Probe" + UUID.randomUUID().toString().substring(0, 6),
                        "dateOfBirth", "1990-01-01",
                        "sex", "FEMALE"))
                .when().post("/patients")
                .then().statusCode(403);
    }

    @Test
    @DisplayName("the session can still see who it is and sign out")
    void theWayOutIsOpen() {
        given().spec(Api.withToken(freshToken))
                .when().get("/auth/me")
                .then().statusCode(200)
                .body("username", is(FRESH_ACCOUNT))
                .body("mustChangePassword", is(true));
    }

    @Test
    @DisplayName("changing the password is the one write it can make, and it opens the gate")
    void changingThePasswordOpensTheGate() {
        // A throwaway account, because this test consumes the initial password. Doing it to
        // new.starter would leave the fixture changed for every later run.
        String username = "gate-" + UUID.randomUUID().toString().substring(0, 8);
        String initial = "Initial!Password2026";
        String replacement = "Replacement!Password2026";

        given().spec(Api.as(Api.ADMIN))
                .body(Map.of("username", username, "email", username + "@hms.local",
                        "fullName", "Gate Test User", "password", initial,
                        "roles", java.util.List.of("RECEPTIONIST")))
                .when().post("/admin/users")
                .then().statusCode(anyOf(is(200), is(201)));

        // An admin-created account is flagged, which is the path that matters outside a demo.
        String gated = Api.login(username, initial).accessToken();
        given().spec(Api.withToken(gated)).when().get("/patients").then().statusCode(403);

        given().spec(Api.withToken(gated))
                .body(Map.of("currentPassword", initial, "newPassword", replacement))
                .when().post("/auth/change-password")
                .then().statusCode(200);

        // The old session is revoked by the change, and the new one carries the role.
        given().spec(Api.withToken(gated)).when().get("/patients").then().statusCode(403);
        given().spec(Api.withToken(Api.login(username, replacement).accessToken()))
                .when().get("/patients")
                .then().statusCode(200);
    }
}
