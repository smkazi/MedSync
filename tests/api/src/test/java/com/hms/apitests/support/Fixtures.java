package com.hms.apitests.support;

import static io.restassured.RestAssured.given;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/** Creates the rows a journey needs, with values unique per run so suites never collide. */
public final class Fixtures {

    /** Distinguishes this run's rows from every previous run's against the same database. */
    public static final String RUN = Long.toString(System.currentTimeMillis(), 36).toUpperCase();

    private Fixtures() {
    }

    public record Patient(String id, String mrn, String fullName) {
    }

    /**
     * A clinician for this run, and a login that is them.
     *
     * <p>{@code id} is the <strong>user</strong> id, not the staff id, and that distinction became
     * load-bearing with the care-team narrowing: {@code encounters.clinician_id} is a login, it is
     * validated against {@code staff.user_id} before it is written, and it decides who may read the
     * chart afterwards. This fixture used to hand back the staff id, which the platform's own
     * booking screen has never done — it filters the clinician picker to staff who have a login.
     *
     * <p>{@code accessToken} is that clinician's own session, so a test can act as the person the
     * encounter is about rather than as an unrelated doctor who happens to hold the role.
     */
    public record Clinician(String id, String staffId, String fullName, String departmentCode,
                            String username, String accessToken) {
    }

    /** A bookable consulting room created for this run. */
    /**
     * A patient with live portal access, and the token their browser would carry.
     *
     * <p>The token is what the tests use, not a role name: every other identity in this suite is a
     * seeded staff account, and a portal account cannot be seeded because it has to point at a
     * patient record and the seed runs before there is one. So a portal patient is enrolled for
     * real, through the front desk, and the one-time password is changed exactly as the patient
     * would change it — which makes this fixture a test of enrolment as well as a fixture.
     */
    public record PortalPatient(String id, String mrn, String username, String accessToken) {
    }

    public record ConsultingRoom(String id, String code) {
    }

    public static Patient registerPatient(String actor, String surname) {
        var body = given().spec(Api.as(actor))
                .body(Map.of(
                        "firstName", "Api" + UUID.randomUUID().toString().substring(0, 4),
                        "lastName", surname + RUN,
                        "dateOfBirth", "1985-04-11",
                        "sex", "FEMALE",
                        "phone", "+9715" + (1000000 + (int) (Math.random() * 8999999)),
                        "city", "Test City",
                        // Detection is doing its job; the suite is not testing it here.
                        "forceDuplicate", true))
                .when().post("/patients")
                .then().statusCode(201)
                .extract().jsonPath();
        return new Patient(body.getString("id"), body.getString("mrn"), body.getString("fullName"));
    }

    /**
     * Registers a patient and gives them working portal access.
     *
     * <p>Four steps, all through the API, in the order a hospital does them: the front desk
     * registers the patient with an address on the record, the desk issues a one-time password, the
     * patient signs in with it — receiving a token carrying no roles at all, which is the platform's
     * existing initial-password gate — and then chooses their own password, at which point the next
     * sign-in mints the token this fixture returns.
     *
     * <p>The email is unique per patient, because identity-service refuses two accounts on one
     * address: two people sharing an inbox would each be able to reset the other's access.
     */
    public static PortalPatient portalPatient(String surname) {
        String email = "portal." + UUID.randomUUID().toString().substring(0, 12) + "@example.invalid";
        var registered = given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of(
                        "firstName", "Portal" + UUID.randomUUID().toString().substring(0, 4),
                        "lastName", surname + RUN,
                        "dateOfBirth", "1985-04-11",
                        "sex", "FEMALE",
                        "email", email,
                        "phone", "+9715" + (1000000 + (int) (Math.random() * 8999999)),
                        "forceDuplicate", true))
                .when().post("/patients")
                .then().statusCode(201)
                .extract().jsonPath();
        String patientId = registered.getString("id");
        String mrn = registered.getString("mrn");

        var issued = given().spec(Api.as(Api.RECEPTIONIST))
                .when().post("/patients/{id}/portal-account", patientId)
                .then().statusCode(201)
                .extract().jsonPath();
        String username = issued.getString("username");
        String temporary = issued.getString("temporaryPassword");

        // The first sign-in carries no roles: the account owes a password change and the platform
        // says so structurally rather than with a flag the UI could ignore.
        String chosen = "ChosenByThePatient!2026";
        String firstToken = Api.login(username, temporary).accessToken();
        given().spec(Api.withToken(firstToken))
                .body(Map.of("currentPassword", temporary, "newPassword", chosen))
                .when().post("/auth/change-password")
                .then().statusCode(200);

        return new PortalPatient(patientId, mrn, username,
                Api.login(username, chosen).accessToken());
    }

    /**
     * A clinician created for this run. Deliberately not a shared seeded one: appointments are
     * guarded by an exclusion constraint over (clinician_id, time range), so reusing a clinician
     * across runs would make booking collide with a previous run's rows.
     */
    public static Clinician clinician() {
        String department = given().spec(Api.as(Api.ADMIN))
                .when().get("/departments")
                .then().statusCode(200)
                .extract().jsonPath().getString("find { it.active == true }.code");

        // The login first, because the staff row has to point at it. A doctor: the encounter this
        // clinician opens is one they must then be able to read, which is what the care team is.
        String username = "api.clinician." + UUID.randomUUID().toString().substring(0, 8);
        String issued = "IssuedByTheDesk!2026";
        String chosen = "ChosenByTheDoctor!2026";
        var user = given().spec(Api.as(Api.ADMIN))
                .body(Map.of(
                        "username", username,
                        "email", username + "@hms.local",
                        "fullName", "Api Clinician " + RUN,
                        "password", issued,
                        "roles", java.util.List.of("DOCTOR")))
                .when().post("/admin/users")
                .then().statusCode(201)
                .extract().jsonPath();

        // Every new account owes a password change, and until it makes one its token carries no
        // roles at all. Same gate the portal fixture goes through, for the same reason.
        String firstToken = Api.login(username, issued).accessToken();
        given().spec(Api.withToken(firstToken))
                .body(Map.of("currentPassword", issued, "newPassword", chosen))
                .when().post("/auth/change-password")
                .then().statusCode(200);

        var body = given().spec(Api.as(Api.ADMIN))
                .body(Map.of(
                        "employeeNo", "API-" + UUID.randomUUID().toString().substring(0, 8),
                        "fullName", "Api Clinician " + RUN,
                        "designation", "Consultant",
                        "departmentCode", department,
                        "specialty", "General Medicine",
                        "userId", user.getString("id")))
                .when().post("/staff")
                .then().statusCode(201)
                .extract().jsonPath();
        return new Clinician(user.getString("id"), body.getString("id"), body.getString("fullName"),
                body.getString("departmentCode"), username, Api.login(username, chosen).accessToken());
    }

    /** A slot far enough out that no seeded or previous-run appointment can be sitting in it. */
    public static Instant slot(int minutesFromBase) {
        return LocalDate.now().plusDays(45).atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().plus(9, ChronoUnit.HOURS).plus(minutesFromBase, ChronoUnit.MINUTES);
    }

    /**
     * A bookable consulting room, created for this run.
     *
     * <p>Deliberately not a seeded one, and the reason is the same as for {@link #clinician()}: a
     * room booking is guarded by an exclusion constraint over (room_id, time range), so two runs
     * sharing a room collide on the second and every one after. The queue makes this sharper still
     * — a token queue is per room per day and the numbering does not reset, so a shared room means
     * a test cannot say anything about which number it was given.
     *
     * <p>Left behind rather than cleaned up, like the clinician: it is inactive to nobody and the
     * facility screens show it as a real room, which is the honest state of a development database
     * that has had tests run against it.
     */
    public static ConsultingRoom consultingRoom() {
        String floor = given().spec(Api.as(Api.ADMIN))
                .when().get("/floors")
                .then().statusCode(200)
                .extract().jsonPath().getString("find { it.active == true }.code");

        var body = given().spec(Api.as(Api.ADMIN))
                .body(Map.of(
                        // Sixteen characters is the column, and the run key is five, so the prefix
                        // has to stay short.
                        "code", "QRT-" + RUN,
                        "name", "API Queue Room " + RUN,
                        // A schedulable, clinical type: the booking service refuses a room whose
                        // type cannot take appointments, which is the point of that flag.
                        "roomTypeCode", "CONSULTATION",
                        "floorCode", floor,
                        "capacity", 1,
                        "bookable", true))
                .when().post("/rooms")
                .then().statusCode(201)
                .extract().jsonPath();
        return new ConsultingRoom(body.getString("id"), body.getString("code"));
    }
}
