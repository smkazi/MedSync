package com.hms.apitests.support;

import static io.restassured.RestAssured.given;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.RestAssuredConfig;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.Map;

/**
 * The one place that knows where the stack is and how to talk to it.
 *
 * <p>These are black-box tests: no Spring context, no repositories, no test slices - just HTTP
 * against a deployed gateway, exactly as a client sees it. That is the point. An integration test
 * inside a service can pass while the gateway's routing, the resource server's JWKS validation, or
 * the cross-service contract is broken, because none of those are in the picture.
 *
 * <p>Tokens are cached per username for the life of the JVM. Argon2id is deliberately expensive
 * and a suite that logs in for every request spends most of its time hashing.
 */
public final class Api {

    public static final String BASE_URL = System.getProperty("hms.api.base-url", "http://localhost:8080");
    public static final String SEED_PASSWORD = System.getProperty("hms.api.password", "ChangeMe!Dev2026");

    /** The seeded accounts, by role. */
    public static final String ADMIN = "admin";
    public static final String DOCTOR = "dr.rao";
    public static final String NURSE = "nurse.iqbal";
    public static final String RECEPTIONIST = "reception";
    public static final String LAB_TECH = "lab.tech";
    public static final String PATHOLOGIST = "dr.pathan";
    public static final String PHARMACIST = "pharmacist";
    public static final String CASHIER = "cashier";
    public static final String RADIOGRAPHER = "radiographer";
    public static final String RADIOLOGIST = "dr.mistry";

    /**
     * The notification service's own account. Not a person.
     *
     * <p>Here so the abuse suite can prove the narrowest role on the platform really is narrow:
     * an unattended account's password is the one most likely to end up in a deployment file
     * somebody can read, so what it can reach matters more than what it is for.
     */
    public static final String SERVICE_ACCOUNT = "svc.notification";

    private static final Map<String, String> TOKEN_CACHE = new HashMap<>();

    private Api() {
    }

    /** Base spec: JSON in and out, and a full dump of anything that fails. */
    public static RequestSpecification spec() {
        return new RequestSpecBuilder()
                .setBaseUri(BASE_URL)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .setConfig(RestAssuredConfig.config())
                .build()
                // Any 5xx is dumped in full: a server error here is always the interesting
                // event, and reading it off the CI log beats reproducing it locally.
                .filter(io.restassured.filter.log.ResponseLoggingFilter
                        .logResponseIfStatusCodeMatches(org.hamcrest.Matchers.greaterThanOrEqualTo(500)))
                .log()
                .ifValidationFails(LogDetail.ALL);
    }

    /** Base spec plus a bearer token for {@code username}. */
    public static RequestSpecification as(String username) {
        return spec().header("Authorization", "Bearer " + token(username));
    }

    /** Base spec with an explicit token - used by the JWT abuse cases. */
    public static RequestSpecification withToken(String token) {
        return spec().header("Authorization", "Bearer " + token);
    }

    public static synchronized String token(String username) {
        return TOKEN_CACHE.computeIfAbsent(username, u -> login(u, SEED_PASSWORD).accessToken());
    }

    /** A fresh login, bypassing the cache. Returns both tokens so refresh flows can use them. */
    public static Tokens login(String username, String password) {
        var body = given().spec(spec())
                .body(Map.of("username", username, "password", password))
                .when().post("/auth/login")
                .then().statusCode(200)
                .extract().jsonPath();
        return new Tokens(body.getString("accessToken"), body.getString("refreshToken"));
    }

    public record Tokens(String accessToken, String refreshToken) {
    }

    /** True when the gateway is currently refusing this client with 429. */
    public static boolean rateLimited() {
        try {
            int status = RestAssured.given().baseUri(BASE_URL)
                    .contentType(ContentType.JSON)
                    .body(Map.of("username", "rate-limit-probe", "password", "not-a-password"))
                    .when().post("/auth/login")
                    .then().extract().statusCode();
            return status == 429;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True when the stack answers. Used to fail the suite with a useful message, not a timeout. */
    public static boolean reachable() {
        try {
            RestAssured.given().baseUri(BASE_URL).when().get("/actuator/health")
                    .then().statusCode(200);
            return true;
        } catch (RuntimeException | AssertionError e) {
            return false;
        }
    }
}
