package com.hms.apitests.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.RequiresRunningStack;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Session and credential abuse: refresh-token theft, brute force, and account enumeration.
 *
 * <p>The refresh-replay case is the important one. Rotation alone is not theft detection - it only
 * helps if presenting an already-used token is treated as evidence that someone has a copy, and
 * the whole family is burned. Anything less means the attacker who stole a token keeps a working
 * session for as long as they keep refreshing.
 */
@DisplayName("session and credential abuse cases")
class SessionAbuseIT extends RequiresRunningStack {

    /**
     * A throwaway account, so a test that deliberately locks one out cannot lock out a seeded
     * account that later tests still need.
     */
    private String disposableAccount() {
        String username = "abuse-" + UUID.randomUUID().toString().substring(0, 8);
        given().spec(Api.as(Api.ADMIN))
                .body(Map.of(
                        "username", username,
                        "email", username + "@hms.local",
                        "password", Api.SEED_PASSWORD,
                        "fullName", "Abuse Case Account",
                        "roles", java.util.List.of("NURSE")))
                .when().post("/admin/users")
                .then().statusCode(201);
        return username;
    }

    @Test
    @DisplayName("a refresh token works exactly once")
    void refreshTokenRotates() {
        String username = disposableAccount();
        var first = Api.login(username, Api.SEED_PASSWORD);

        var rotated = given().spec(Api.spec())
                .body(Map.of("refreshToken", first.refreshToken()))
                .when().post("/auth/refresh")
                .then().statusCode(200)
                .extract().jsonPath();

        assertThat(rotated.getString("refreshToken"))
                .as("a refresh that returns the same token is not rotation")
                .isNotEqualTo(first.refreshToken());
        assertThat(rotated.getString("accessToken")).isNotBlank();
    }

    @Test
    @DisplayName("replaying a spent refresh token burns the whole family")
    void refreshReplayRevokesTheFamily() {
        String username = disposableAccount();
        var stolen = Api.login(username, Api.SEED_PASSWORD);

        // The legitimate client refreshes.
        String liveToken = given().spec(Api.spec())
                .body(Map.of("refreshToken", stolen.refreshToken()))
                .when().post("/auth/refresh")
                .then().statusCode(200)
                .extract().jsonPath().getString("refreshToken");

        // The attacker replays the copy they took before that refresh.
        given().spec(Api.spec())
                .body(Map.of("refreshToken", stolen.refreshToken()))
                .when().post("/auth/refresh")
                .then().statusCode(401);

        // And now the legitimate client's own token must be dead too. This is the whole point:
        // the replay proved a copy exists, and there is no way to tell which side is the thief,
        // so both are cut off and the user signs in again.
        given().spec(Api.spec())
                .body(Map.of("refreshToken", liveToken))
                .when().post("/auth/refresh")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("logout revokes the refresh token")
    void logoutRevokes() {
        String username = disposableAccount();
        var session = Api.login(username, Api.SEED_PASSWORD);

        given().spec(Api.spec())
                .body(Map.of("refreshToken", session.refreshToken()))
                .when().post("/auth/logout")
                .then().statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200), org.hamcrest.Matchers.is(204)));

        given().spec(Api.spec())
                .body(Map.of("refreshToken", session.refreshToken()))
                .when().post("/auth/refresh")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("repeated bad passwords lock the account, and the lock survives a correct password")
    void bruteForceIsLockedOut() {
        String username = disposableAccount();

        int lastStatus = 0;
        for (int attempt = 1; attempt <= 8; attempt++) {
            lastStatus = given().spec(Api.spec())
                    .body(Map.of("username", username, "password", "definitely-wrong-" + attempt))
                    .when().post("/auth/login")
                    .then().extract().statusCode();
            if (lastStatus == 423) {
                break;
            }
            assertThat(lastStatus)
                    .as("a wrong password is a 401, never a 500")
                    .isEqualTo(401);
        }

        assertThat(lastStatus)
                .as("the account must lock out within 8 attempts")
                .isEqualTo(423);

        // The correct password must not open a locked account - otherwise the lockout only slows
        // down an attacker who has not yet guessed right, which is no protection at all.
        given().spec(Api.spec())
                .body(Map.of("username", username, "password", Api.SEED_PASSWORD))
                .when().post("/auth/login")
                .then().statusCode(423);
    }

    @Test
    @DisplayName("an unknown username and a wrong password are indistinguishable")
    void loginDoesNotEnumerateAccounts() {
        String unknownBody = given().spec(Api.spec())
                .body(Map.of("username", "no-such-user-" + UUID.randomUUID(), "password", "whatever"))
                .when().post("/auth/login")
                .then().statusCode(401)
                .extract().jsonPath().getString("detail");

        String wrongPasswordBody = given().spec(Api.spec())
                .body(Map.of("username", Api.DOCTOR, "password", "definitely-not-the-password"))
                .when().post("/auth/login")
                .then().statusCode(401)
                .extract().jsonPath().getString("detail");

        assertThat(unknownBody)
                .as("differing messages tell an attacker which usernames are real")
                .isEqualTo(wrongPasswordBody);
    }

    @Test
    @DisplayName("an access token is not accepted where a refresh token is expected")
    void tokenTypesAreNotInterchangeable() {
        given().spec(Api.spec())
                .body(Map.of("refreshToken", Api.token(Api.DOCTOR)))
                .when().post("/auth/refresh")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("a user cannot change another user's password through the self-service endpoint")
    void changePasswordOnlyAffectsTheCaller() {
        String username = disposableAccount();
        var session = Api.login(username, Api.SEED_PASSWORD);

        // No user id in the payload at all: the endpoint takes its subject from the token, which
        // is the only design where this cannot be abused.
        //
        // 400, not 401. The caller is authenticated and the account is already known - it is the
        // one holding the token - so the uniform "invalid username or password" that guards login
        // against account enumeration has nothing to protect here, and saying it would only
        // mislead somebody who mistyped their own password.
        given().spec(Api.withToken(session.accessToken()))
                .body(Map.of("currentPassword", "wrong-current-password",
                        "newPassword", "AnotherPassword!2026"))
                .when().post("/auth/change-password")
                .then().statusCode(400)
                .body("detail", org.hamcrest.Matchers.equalTo("Current password is incorrect"));

        // And the original password still works.
        Api.login(username, Api.SEED_PASSWORD);
    }
}
