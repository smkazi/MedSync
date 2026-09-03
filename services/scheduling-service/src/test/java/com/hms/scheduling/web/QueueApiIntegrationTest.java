package com.hms.scheduling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.scheduling.client.RoomDirectoryClient;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The token queue as the desk and the corridor see it.
 *
 * <p>The assertion that carries the weight is the last one: the public display is reachable with no
 * token at all, and there must be nothing in its response that identifies anybody. That screen is
 * mounted in a corridor and is visible to every visitor, delivery driver and passer-by in the
 * building — it is the one response in this platform where the safest possible content is also the
 * correct content.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueueApiIntegrationTest {

    /** A room this test owns, so the day's numbering starts where the assertions expect. */
    private static final String QUEUE_ROOM = "QT-QUEUE";

    /**
     * The length of every fixture appointment, and therefore the gap between them.
     *
     * <p>Five because that is the shortest the service accepts ({@code @Min(5)} on
     * {@code durationMinutes}) and the room exclusion constraint means fixtures spaced closer
     * than their own duration collide in the same room. Eight of them at five minutes need
     * forty minutes of the rest of the day; at fifteen they needed two hours, which is two
     * hours of every evening in which this class could not run.
     */
    private static final int SLOT_MINUTES = 5;

    /** How far ahead the first fixture is booked — the service refuses to book in the past. */
    private static final int LEAD_MINUTES = 2;

    /** The longest set this class books: eight fixtures, so seven gaps after the first. */
    private static final int LONGEST_SET_MINUTES = LEAD_MINUTES + 7 * SLOT_MINUTES;

    /**
     * The instant the first fixture of the current test is booked at, fixed once per test.
     *
     * <p>Fixed rather than re-read from the clock on every call, because a set of fixtures split
     * across two service dates is exactly the bug this field exists to make impossible: the
     * queue numbers a day, and half a set on either side of midnight is two half-boards.
     */
    private Instant firstSlot;

    /** The service date {@link #firstSlot} falls on, in the clinic zone (UTC under this profile). */
    private LocalDate serviceDate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RoomDirectoryClient roomDirectory;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Clears this room's appointments and tokens before each test.
     *
     * <p>The numbering is per room per day and it does not reset on its own — that is the point of
     * it. Without this the suite is green on its first run and red on every one after, which is
     * worse than red.
     */
    @BeforeEach
    void freeTheQueue() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("delete from QueueToken t where t.roomCode = :code")
                    .setParameter("code", QUEUE_ROOM).executeUpdate();
            entityManager.createQuery("delete from Appointment a where a.roomCode = :code")
                    .setParameter("code", QUEUE_ROOM).executeUpdate();
            entityManager.createNativeQuery(
                            "delete from queue_counters where room_code = :code")
                    .setParameter("code", QUEUE_ROOM).executeUpdate();
        });
    }

    /**
     * Chooses the day this test books on: today when the rest of it can hold the longest set,
     * and tomorrow morning when it cannot.
     *
     * <p>{@code QueueService} derives a token's service date from the appointment's start in the
     * clinic zone, and the staff board reads a named date — so a run beginning near local
     * midnight simply moves its whole fixture set to the next day and reads that board. The
     * corridor display has no such escape: it shows today by design, which is why the two tests
     * of it check {@link #displayIsTestableToday()} first.
     */
    @BeforeEach
    void chooseTheDay() {
        Instant soon = Instant.now().plus(LEAD_MINUTES, ChronoUnit.MINUTES);
        LocalDate today = soon.atZone(ZoneOffset.UTC).toLocalDate();
        boolean todayCanHold = soon.plus(LONGEST_SET_MINUTES, ChronoUnit.MINUTES)
                .atZone(ZoneOffset.UTC).toLocalDate().equals(today);
        this.firstSlot = todayCanHold
                ? soon
                : today.plusDays(1).atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();
        this.serviceDate = firstSlot.atZone(ZoneOffset.UTC).toLocalDate();
    }

    @BeforeEach
    void stubTheDirectory() {
        when(roomDirectory.find(nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.empty());
        RoomDirectoryClient.RoomLocation room = new RoomDirectoryClient.RoomLocation(
                UUID.nameUUIDFromBytes(QUEUE_ROOM.getBytes(StandardCharsets.UTF_8)),
                QUEUE_ROOM, "Queue Test OPD", "Ground Floor", "Follow the signs", true);
        when(roomDirectory.require(eq(QUEUE_ROOM), nullable(String.class))).thenReturn(room);
        when(roomDirectory.find(eq(QUEUE_ROOM), nullable(String.class))).thenReturn(Optional.of(room));
    }

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user")
                        .claim("roles", List.of(roles)))
                .authorities(authorities);
    }

    /**
     * A slot on {@link #serviceDate}, {@code minutesFromFirst} after the first one.
     *
     * <p>Callers step in {@link #SLOT_MINUTES} multiples, which is the appointment length: the
     * room exclusion constraint is real, so fixtures spaced closer than their own duration
     * collide in the same room and answer 409. Five minutes rather than the fifteen this used to
     * use, because eight of them at fifteen needed two hours of runway and there are only so many
     * hours in a day that begin two before midnight.
     */
    private Instant todayAt(int minutesFromFirst) {
        return firstSlot.plus(minutesFromFirst, ChronoUnit.MINUTES);
    }

    /**
     * Whether today can still host a test of the corridor display.
     *
     * <p>The display shows today and nothing else — deliberately, since accepting a date would
     * let anybody on the internet read the shape of any past clinic. So a test of it has to book
     * on the day, and in the last {@link #LONGEST_SET_MINUTES} minutes of the clinic's day there
     * is no room left to book. That window is the one place this suite cannot assert; the two
     * tests below say so out loud rather than reporting an empty board as a defect.
     */
    private boolean displayIsTestableToday() {
        return serviceDate.equals(LocalDate.now(ZoneOffset.UTC));
    }

    private static final String NO_ROOM_LEFT_TODAY =
            "The corridor display shows today and nothing else, and there is less than "
                    + LONGEST_SET_MINUTES + " minutes of today left in the clinic zone to book "
                    + "fixtures into. This is a limit of a today-only screen, not a defect in it.";

    private String bookAndCheckIn(String surname, int minuteOffset) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", UUID.randomUUID());
        body.put("patientMrn", "MRN-QUEUE-" + System.nanoTime() % 1000000);
        body.put("clinicianId", UUID.randomUUID());
        body.put("clinicianName", "Dr " + surname);
        body.put("departmentCode", "GEN");
        body.put("startsAt", todayAt(minuteOffset).toString());
        body.put("durationMinutes", SLOT_MINUTES);
        body.put("roomCode", QUEUE_ROOM);

        String created = mockMvc.perform(post("/appointments").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asString();

        mockMvc.perform(post("/appointments/" + id + "/check-in").with(as("RECEPTIONIST")))
                .andExpect(status().isOk());
        return id;
    }

    private JsonNode board(String role) throws Exception {
        // ?date= rather than the server's idea of today: the staff board takes a date precisely so
        // a board can be read for a named day, and pinning it to the fixtures' own service date is
        // what stops a run that begins after local midnight reading an empty board.
        String body = mockMvc.perform(get("/queue/" + QUEUE_ROOM)
                        .param("date", serviceDate.toString())
                        .with(as(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("checking in issues the next number, and the numbers run in order")
    void checkingInIssuesATokenInOrder() throws Exception {
        bookAndCheckIn("First", 0);
        bookAndCheckIn("Second", SLOT_MINUTES);
        bookAndCheckIn("Third", 2 * SLOT_MINUTES);

        JsonNode board = board("RECEPTIONIST");
        assertThat(board.get("roomCode").asString()).isEqualTo(QUEUE_ROOM);
        assertThat(board.get("tokens").size()).isEqualTo(3);
        assertThat(List.of(
                board.get("tokens").get(0).get("tokenNumber").asInt(),
                board.get("tokens").get(1).get("tokenNumber").asInt(),
                board.get("tokens").get(2).get("tokenNumber").asInt()))
                .isEqualTo(List.of(1, 2, 3));
        // Nothing has been called yet, so nothing is being served. Not zero: zero is a number, and
        // a corridor board showing "now serving 0" is a board saying something untrue.
        assertThat(board.get("nowServing").isNull()).isTrue();
    }

    @Test
    @DisplayName("checking in twice does not hand out a second number")
    void aTokenIsIssuedOnce() throws Exception {
        String id = bookAndCheckIn("Once", 0);

        // The state machine already refuses a second check-in, so this asserts the belt as well as
        // the braces: even if a transition were added that re-ran the branch, the unique key on
        // appointment_id means the same patient keeps the same number. A second number would leave
        // a gap in the sequence that nobody answers.
        mockMvc.perform(post("/appointments/" + id + "/check-in").with(as("RECEPTIONIST")))
                .andExpect(status().isConflict());
        assertThat(board("RECEPTIONIST").get("tokens").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("starting the consultation calls the number; completing it takes it off the board")
    void theBoardFollowsTheAppointment() throws Exception {
        String first = bookAndCheckIn("Called", 0);
        bookAndCheckIn("Waiting", SLOT_MINUTES);

        mockMvc.perform(post("/appointments/" + first + "/start").with(as("DOCTOR")))
                .andExpect(status().isOk());
        assertThat(board("DOCTOR").get("nowServing").asInt()).isEqualTo(1);

        mockMvc.perform(post("/appointments/" + first + "/complete").with(as("DOCTOR")))
                .andExpect(status().isOk());
        // Off the board rather than still showing: a number that stays lit for somebody who has
        // left is how a corridor stops believing the display.
        assertThat(board("DOCTOR").get("nowServing").isNull()).isTrue();
        assertThat(board("DOCTOR").get("tokens").get(0).get("status").asString()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("an appointment with no room checks in without a number")
    void noRoomMeansNoToken() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", UUID.randomUUID());
        body.put("patientMrn", "MRN-NOROOM-" + System.nanoTime() % 1000000);
        body.put("clinicianId", UUID.randomUUID());
        body.put("clinicianName", "Dr Roomless");
        body.put("departmentCode", "GEN");
        body.put("startsAt", todayAt(0).toString());
        body.put("durationMinutes", SLOT_MINUTES);

        String created = mockMvc.perform(post("/appointments").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asString();

        // A token queue is a queue for a door. An appointment booked before the clinic knew where
        // it would run checks in normally and simply is not on any board, rather than being given
        // a number for a queue with no location.
        mockMvc.perform(post("/appointments/" + id + "/check-in").with(as("RECEPTIONIST")))
                .andExpect(status().isOk());
        assertThat(board("RECEPTIONIST").get("tokens").size()).isZero();
    }

    @Test
    @DisplayName("the corridor display needs no token and returns nothing that identifies anybody")
    void thePublicDisplayIsReachableAndCarriesNoPhi() throws Exception {
        assumeTrue(displayIsTestableToday(), NO_ROOM_LEFT_TODAY);
        String first = bookAndCheckIn("Nairsmith", 0);
        bookAndCheckIn("Iqbalson", SLOT_MINUTES);
        bookAndCheckIn("Menonford", 2 * SLOT_MINUTES);
        mockMvc.perform(post("/appointments/" + first + "/start").with(as("DOCTOR")))
                .andExpect(status().isOk());

        // No `.with(as(...))`: this is the whole point. `hms.security.public-paths` allowlists it.
        String body = mockMvc.perform(get("/public/queue/" + QUEUE_ROOM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomCode").value(QUEUE_ROOM))
                .andExpect(jsonPath("$.nowServing").value(1))
                .andExpect(jsonPath("$.upcoming").isArray())
                .andReturn().getResponse().getContentAsString();

        // Greping the rendered body, not inspecting the DTO's fields: what matters is what goes out
        // on the wire. The names above are the clinicians on the fixtures and would be the nearest
        // thing to a name available to leak.
        assertThat(body.toLowerCase(Locale.ROOT))
                .as("no name, no MRN, no id of any kind")
                .doesNotContain("nairsmith")
                .doesNotContain("iqbalson")
                .doesNotContain("menonford")
                .doesNotContain("mrn-")
                .doesNotContain("patient")
                .doesNotContain("appointment")
                .doesNotContain("clinician");
        // And structurally: the only keys are the three the corridor needs.
        assertThat(objectMapper.readTree(body).propertyNames())
                .containsExactlyInAnyOrder("roomCode", "nowServing", "upcoming");
    }

    @Test
    @DisplayName("the display shows a handful of numbers, not the length of the queue")
    void theDisplayDoesNotSayHowManyAreWaiting() throws Exception {
        assumeTrue(displayIsTestableToday(), NO_ROOM_LEFT_TODAY);
        for (int i = 0; i < 8; i++) {
            bookAndCheckIn("Queued" + i, i * SLOT_MINUTES);
        }

        JsonNode display = objectMapper.readTree(
                mockMvc.perform(get("/public/queue/" + QUEUE_ROOM))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        // Capped at five. "You are eighth" plus a visible arrival order is enough for a stranger
        // to work out who is who, so the board is a hint about the next few rather than a census.
        assertThat(display.get("upcoming").size()).isEqualTo(5);
        assertThat(display.propertyNames()).doesNotContain("waitingCount", "total");
    }

    @Test
    @DisplayName("the staff board still needs a role, and the front desk is not enough for nothing")
    void theStaffBoardIsNotPublic() throws Exception {
        mockMvc.perform(get("/queue/" + QUEUE_ROOM)).andExpect(status().isUnauthorized());
        // Readable by everybody clinical: the nurse calling the next patient and the clinician
        // wondering how far behind they are both need it, and it carries less than the appointment
        // book they can already read.
        mockMvc.perform(get("/queue/" + QUEUE_ROOM).with(as("LAB_TECH"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a room with no queue answers an empty board rather than a 404")
    void anUnknownRoomIsEmptyNotMissing() throws Exception {
        // A display is mounted and switched on before the first patient arrives, and a corridor
        // screen showing an error page on a quiet morning is worse than one showing nothing.
        mockMvc.perform(get("/public/queue/ZZ-NOBODY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nowServing").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.upcoming").isEmpty());
    }

    @Test
    @DisplayName("a lower-case room code in the URL finds the same queue")
    void roomCodesAreCaseInsensitiveInTheUrl() throws Exception {
        assumeTrue(displayIsTestableToday(), NO_ROOM_LEFT_TODAY);
        bookAndCheckIn("Case", 0);
        // A display's URL is typed once by hand into a kiosk browser, so it is worth not caring.
        mockMvc.perform(get("/public/queue/" + QUEUE_ROOM.toLowerCase(Locale.ROOT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upcoming[0]").value(1));
    }
}
