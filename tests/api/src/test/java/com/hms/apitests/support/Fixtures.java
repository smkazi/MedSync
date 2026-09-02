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

    public record Clinician(String id, String fullName, String departmentCode) {
    }

    /** A bookable consulting room created for this run. */
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
     * A clinician created for this run. Deliberately not a shared seeded one: appointments are
     * guarded by an exclusion constraint over (clinician_id, time range), so reusing a clinician
     * across runs would make booking collide with a previous run's rows.
     */
    public static Clinician clinician() {
        String department = given().spec(Api.as(Api.ADMIN))
                .when().get("/departments")
                .then().statusCode(200)
                .extract().jsonPath().getString("find { it.active == true }.code");

        var body = given().spec(Api.as(Api.ADMIN))
                .body(Map.of(
                        "employeeNo", "API-" + UUID.randomUUID().toString().substring(0, 8),
                        "fullName", "Api Clinician " + RUN,
                        "designation", "Consultant",
                        "departmentCode", department,
                        "specialty", "General Medicine"))
                .when().post("/staff")
                .then().statusCode(201)
                .extract().jsonPath();
        return new Clinician(body.getString("id"), body.getString("fullName"), body.getString("departmentCode"));
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
