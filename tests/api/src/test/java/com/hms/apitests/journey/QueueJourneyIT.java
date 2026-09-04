package com.hms.apitests.journey;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Platform;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.RequiresRunningStack;
import io.restassured.path.json.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The OPD token queue through the gateway, and the corridor display without a token.
 *
 * <p>Black box on purpose, and it matters more here than anywhere else in this suite. The public
 * display's safety rests on three things that live in three different places — the service's
 * allowlist, the gateway's route, and the DTO's shape — and only a request that goes all the way
 * through with no {@code Authorization} header at all exercises the combination. A Spring context
 * test can prove the endpoint is permitted; it cannot prove the gateway routes it or that nothing
 * in front of it adds a redirect.
 */
@DisplayName("the OPD token queue")
class QueueJourneyIT extends RequiresRunningStack {

    /**
     * A room created for this run, not a seeded one.
     *
     * <p>Two reasons, and the second is the stronger. A room booking is guarded by an exclusion
     * constraint over (room_id, time range), so sharing a room with the browser suite means the
     * second of them to run gets 409s — which is exactly how this test first failed. And a token
     * queue is per room per day with numbering that does not reset, so a shared room means no
     * assertion here could say anything about which number the check-in was given.
     */
    private static String room;

    private static Fixtures.Patient patient;
    private static Fixtures.Clinician clinician;

    /**
     * One anchor for every booking in the class, captured once.
     *
     * <p>Not {@code Instant.now()} per call, and this cost an hour. The tests share a clinician, so
     * their appointments are guarded by the exclusion constraint over (clinician_id, time range) —
     * and with a per-call now, an offset computed a second later shifts its whole interval a second
     * later too. Two tests whose offsets are exactly one appointment apart then overlap by that
     * second, but only when the later-offset one happens to run first, which JUnit does not
     * promise. A fixed anchor makes the intervals exact whatever the order.
     */
    private static Instant anchor;

    /**
     * The service date every booking in this class lands on, in the clinic's own zone.
     *
     * <p>Not UTC, and it used to say UTC because it used to be: S13b put scheduling on the shared
     * HMS_ZONE chain, whose default is Asia/Kolkata. Everything below asks {@link Platform} rather
     * than assuming, because the queue numbers a <em>day</em> and the day is the service's to
     * decide - see the class comment on Platform for the five-and-a-half-hour window in which the
     * two answers differ.
     */
    private static LocalDate serviceDate;

    /** Every fixture is this long, and they step in twice that so nothing depends on order. */
    private static final int SLOT_MINUTES = 5;

    /** The furthest offset any test books at: four fixtures, so three gaps after the first. */
    private static final int LONGEST_SET_MINUTES = 10 + 3 * 2 * SLOT_MINUTES;

    @BeforeAll
    static void seed() {
        patient = Fixtures.registerPatient(Api.RECEPTIONIST, "Queue");
        clinician = Fixtures.clinician();
        room = Fixtures.consultingRoom().code();
        // Today when the rest of it can hold the whole set, and tomorrow morning when it cannot.
        // The queue numbers a day: a set split across midnight is two half-boards, and the staff
        // board below is read for whichever day the set is actually on.
        Instant soon = Instant.now().plus(10, ChronoUnit.MINUTES);
        LocalDate today = soon.atZone(Platform.zone()).toLocalDate();
        boolean todayCanHold = soon.plus(LONGEST_SET_MINUTES, ChronoUnit.MINUTES)
                .atZone(Platform.zone()).toLocalDate().equals(today);
        anchor = todayCanHold
                ? soon
                : today.plusDays(1).atStartOfDay(Platform.zone()).plusHours(9).toInstant();
        serviceDate = anchor.atZone(Platform.zone()).toLocalDate();
    }

    /**
     * Whether today can still host a test of the corridor display.
     *
     * <p>The display shows today and nothing else, deliberately: accepting a date would let
     * anybody on the internet read the shape of any past clinic. So a test of it has to book on
     * the day, and in the last {@link #LONGEST_SET_MINUTES} minutes of the clinic's day there is
     * nothing left to book. That window is the one thing this suite cannot assert, and it says so
     * rather than reporting an empty board as a defect in the board.
     */
    private static boolean displayIsTestableToday() {
        return serviceDate.equals(Platform.today());
    }

    private static final String NO_ROOM_LEFT_TODAY =
            "The corridor display shows today and nothing else, and there is less than "
                    + LONGEST_SET_MINUTES + " minutes of today left in the clinic zone to book "
                    + "fixtures into. A limit of a today-only screen, not a defect in it.";

    /**
     * A slot later today, offset from the class's fixed anchor.
     *
     * <p>On the day the anchor chose. Callers step in multiples of {@code 2 * SLOT_MINUTES},
     * twice the appointment length: adjacent intervals then have five minutes of air between them
     * and nothing depends on execution order. Five-minute appointments rather than fifteen because
     * the set has to fit inside one service date, and fifteen-minute ones spaced thirty needed
     * over an hour and a half of the day left.
     */
    private static Instant laterToday(int minutesFromAnchor) {
        return anchor.plus(minutesFromAnchor, ChronoUnit.MINUTES);
    }

    private static String bookAndCheckIn(Instant at) {
        String id = given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of(
                        "patientId", patient.id(),
                        "patientMrn", patient.mrn(),
                        "clinicianId", clinician.id(),
                        "clinicianName", clinician.fullName(),
                        "departmentCode", clinician.departmentCode(),
                        "startsAt", at.toString(),
                        "durationMinutes", SLOT_MINUTES,
                        "roomCode", room))
                .when().post("/appointments")
                .then().log().ifValidationFails(io.restassured.filter.log.LogDetail.BODY)
                .statusCode(201)
                .extract().jsonPath().getString("id");

        given().spec(Api.as(Api.RECEPTIONIST))
                .when().post("/appointments/{id}/check-in", id)
                .then().statusCode(200);
        return id;
    }

    private static JsonPath staffBoard() {
        return given().spec(Api.as(Api.NURSE))
                .queryParam("date", serviceDate.toString())
                .when().get("/queue/{room}", room)
                .then().statusCode(200)
                .extract().jsonPath();
    }

    @Test
    @DisplayName("checking in issues a number and the board shows it")
    void checkingInIssuesANumber() {
        String appointmentId = bookAndCheckIn(laterToday(0));

        List<Map<String, Object>> tokens = staffBoard().getList("tokens");
        assertThat(tokens)
                .as("the appointment just checked in has a number on the board")
                .anySatisfy(token -> {
                    assertThat(token.get("appointmentId")).isEqualTo(appointmentId);
                    assertThat(token.get("status")).isEqualTo("WAITING");
                    assertThat((Integer) token.get("tokenNumber")).isPositive();
                });
    }

    @Test
    @DisplayName("the corridor display answers without a token and carries nothing about anybody")
    void theDisplayIsPublicAndPhiFree() {
        assumeTrue(displayIsTestableToday(), NO_ROOM_LEFT_TODAY);
        bookAndCheckIn(laterToday(2 * SLOT_MINUTES));

        // Api.spec() rather than Api.as(...): no Authorization header at all. This is the single
        // most important line in the file.
        String body = given().spec(Api.spec())
                .when().get("/public/queue/{room}", room)
                .then().statusCode(200)
                .body("roomCode", equalTo(room))
                .extract().body().asString();

        assertThat(body.toLowerCase(java.util.Locale.ROOT))
                .as("a screen every visitor in the building can read")
                .doesNotContain(patient.mrn().toLowerCase(java.util.Locale.ROOT))
                .doesNotContain(patient.fullName().toLowerCase(java.util.Locale.ROOT))
                .doesNotContain(patient.id().toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("patient")
                .doesNotContain("appointment")
                .doesNotContain("clinician")
                .doesNotContain("mrn");

        // Structurally, too: three keys and no more. A filtered view of the staff board would be
        // one field away from leaking, which is why this is a different type on the service side.
        assertThat(given().spec(Api.spec())
                .when().get("/public/queue/{room}", room)
                .then().extract().jsonPath().getMap("$").keySet())
                .containsExactlyInAnyOrder("roomCode", "nowServing", "upcoming");
    }

    @Test
    @DisplayName("the staff board still needs a token, and nothing else under /public exists")
    void theAllowlistIsNarrow() {
        given().spec(Api.spec()).when().get("/queue/{room}", room).then().statusCode(401);
        // A prefix allowlisted by pattern is a prefix somebody adds a second endpoint to. The
        // absence is asserted rather than assumed.
        for (String path : List.of("/public/patients", "/public/appointments", "/public/queue")) {
            given().spec(Api.spec()).when().get(path)
                    .then().statusCode(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.is(404), org.hamcrest.Matchers.is(401)));
        }
    }

    @Test
    @DisplayName("starting the consultation calls the number; completing it takes it off the board")
    void theBoardFollowsTheAppointment() {
        assumeTrue(displayIsTestableToday(), NO_ROOM_LEFT_TODAY);
        String appointmentId = bookAndCheckIn(laterToday(2 * 2 * SLOT_MINUTES));
        int number = tokenNumberFor(appointmentId);

        given().spec(Api.as(Api.DOCTOR))
                .when().post("/appointments/{id}/start", appointmentId)
                .then().statusCode(200);
        assertThat(given().spec(Api.spec())
                .when().get("/public/queue/{room}", room)
                .then().statusCode(200)
                .extract().jsonPath().getInt("nowServing"))
                .as("the corridor is calling the number the consulting room started")
                .isEqualTo(number);

        given().spec(Api.as(Api.DOCTOR))
                .when().post("/appointments/{id}/complete", appointmentId)
                .then().statusCode(200);
        // Off the board. A number that stays lit for somebody who has left is how a corridor stops
        // believing the display.
        assertThat(statusOf(appointmentId)).isEqualTo("DONE");
    }

    @Test
    @DisplayName("an appointment with no room checks in without a number")
    void noRoomMeansNoNumber() {
        String id = given().spec(Api.as(Api.RECEPTIONIST))
                .body(Map.of(
                        "patientId", patient.id(),
                        "patientMrn", patient.mrn(),
                        "clinicianId", clinician.id(),
                        "clinicianName", clinician.fullName(),
                        "departmentCode", clinician.departmentCode(),
                        "startsAt", laterToday(3 * 2 * SLOT_MINUTES).toString(),
                        "durationMinutes", SLOT_MINUTES))
                .when().post("/appointments")
                .then().statusCode(201)
                .extract().jsonPath().getString("id");

        given().spec(Api.as(Api.RECEPTIONIST))
                .when().post("/appointments/{id}/check-in", id)
                .then().statusCode(200);

        // A token queue is a queue for a door. An appointment booked before the clinic knew where
        // it would run checks in normally and is simply not on any board.
        assertThat(staffBoard().getList("tokens", Map.class))
                .noneSatisfy(token -> assertThat(token.get("appointmentId")).isEqualTo(id));
    }

    @Test
    @DisplayName("the display is cacheable, because a wall screen polls")
    void theDisplaySetsACacheHeader() {
        // Ten seconds of staleness on a number being called is invisible to somebody sitting
        // down, and it is the difference between one screen per corridor and one query per second
        // per corridor. Asserted because it is a deliberate choice, not an accident of framework
        // defaults - every other endpoint on the platform is no-store.
        given().spec(Api.spec())
                .when().get("/public/queue/{room}", room)
                .then().statusCode(200)
                .header("Cache-Control", org.hamcrest.Matchers.containsString("max-age"));
    }

    private static int tokenNumberFor(String appointmentId) {
        List<Map<String, Object>> tokens = staffBoard().getList("tokens");
        return tokens.stream()
                .filter(token -> appointmentId.equals(token.get("appointmentId")))
                .map(token -> (Integer) token.get("tokenNumber"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no token for appointment " + appointmentId));
    }

    private static String statusOf(String appointmentId) {
        List<Map<String, Object>> tokens = staffBoard().getList("tokens");
        return tokens.stream()
                .filter(token -> appointmentId.equals(token.get("appointmentId")))
                .map(token -> (String) token.get("status"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no token for appointment " + appointmentId));
    }
}
