package com.hms.scheduling.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.common.error.BadRequestException;
import com.hms.scheduling.client.RoomDirectoryClient;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
import tools.jackson.databind.ObjectMapper;

/**
 * The room double-booking guard, against a real PostgreSQL.
 *
 * <p>This closes a gap the platform shipped with: an appointment carried a department code and no
 * room, so two clinicians could be booked into the same consulting room at the same time and
 * nothing objected. The guard is a second exclusion constraint over
 * {@code (room_id, tstzrange)} — separate from the clinician one, because a composite over both
 * would be satisfied by two different clinicians sharing a room, which is the exact case being
 * prevented.
 *
 * <p>{@code RoomDirectoryClient} is stubbed. It reaches patient-service over HTTP, which is not
 * running in a unit test, and what these tests are about is the constraint and the translation of
 * its violation into something the front desk can act on. The client's own behaviour is covered by
 * the cross-service journey in {@code tests/api} and was verified by hand against a live stack.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomBookingIntegrationTest {

    private static final String CONSULT_ROOM = "GF-GEN";
    private static final String OTHER_ROOM = "GF-PAED";
    private static final String CASUALTY_BAY = "GF-CAS";

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
     * Frees the two rooms this test books into, before each test.
     *
     * <p>Needed because the assertions depend on two things being fixed: the room code (the tests
     * read it back out of the response) and the slot (a room clash is only a clash at the same
     * instant). Nothing here is run-scoped, so without this the second run of the suite fails with
     * 409s against rows the first run left behind — green once, red forever after, which is worse
     * than red.
     *
     * <p>Scoped to these two codes and this test's own day window, so it cannot reach data another
     * test owns. The appointment FK from encounters is {@code ON DELETE SET NULL}, so this does not
     * strand a row.
     */
    @BeforeEach
    void freeTheRooms() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createQuery(
                                "delete from Appointment a where a.roomCode in :codes and a.startsAt >= :from")
                        .setParameter("codes", List.of(CONSULT_ROOM, OTHER_ROOM))
                        .setParameter("from", slot(0).minus(1, ChronoUnit.DAYS))
                        .executeUpdate());
    }

    @BeforeEach
    void stubTheDirectory() {
        // The catch-all goes FIRST. Mockito applies the last matching stub, so registering it after
        // the per-code stubs below would silently swallow them and every room would look unknown.
        when(roomDirectory.find(nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.empty());

        for (String code : List.of(CONSULT_ROOM, OTHER_ROOM)) {
            RoomDirectoryClient.RoomLocation room = new RoomDirectoryClient.RoomLocation(
                    // Deterministic per code, so two bookings into the same code really do collide
                    // on the same room_id. A random id per call would make every test pass.
                    UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    code, code.equals(CONSULT_ROOM) ? "General OPD" : "Paediatric OPD",
                    "Ground Floor", "From reception, follow the signs", true);
            when(roomDirectory.require(eq(code), nullable(String.class))).thenReturn(room);
            when(roomDirectory.find(eq(code), nullable(String.class))).thenReturn(Optional.of(room));
        }
        // The directory's answer for space that cannot be booked, and for a code that is not a room.
        when(roomDirectory.require(eq(CASUALTY_BAY), nullable(String.class)))
                .thenThrow(new BadRequestException(
                        "Casualty (GF-CAS) cannot take a booking. Pick a room from the bookable list."));
        when(roomDirectory.require(eq("NO-SUCH-ROOM"), nullable(String.class)))
                .thenThrow(new BadRequestException("No such room: 'NO-SUCH-ROOM'"));
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

    /** A future weekday instant at a minute offset unique to the caller, so tests never collide. */
    private Instant slot(int minutesFromNoon) {
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(21);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date.atStartOfDay(ZoneOffset.UTC).toInstant()
                .plus(12, ChronoUnit.HOURS)
                .plus(minutesFromNoon, ChronoUnit.MINUTES);
    }

    /**
     * The day {@link #slot} builds its instants on.
     *
     * <p>`GET /appointments` defaults to today when no range is given, and every fixture here is
     * three weeks out, so a list assertion without this looks at an empty page and proves nothing.
     */
    private String slotDate() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(21);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date.toString();
    }

    private Map<String, Object> booking(UUID clinicianId, String roomCode, Instant startsAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", UUID.randomUUID());
        body.put("patientMrn", "MRN-ROOM-" + System.nanoTime() % 100000);
        body.put("clinicianId", clinicianId);
        body.put("clinicianName", "Dr Test");
        body.put("departmentCode", "GEN");
        body.put("startsAt", startsAt.toString());
        body.put("durationMinutes", 15);
        if (roomCode != null) {
            body.put("roomCode", roomCode);
        }
        return body;
    }

    private org.springframework.test.web.servlet.ResultActions book(Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post("/appointments").with(as("RECEPTIONIST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    @Test
    @DisplayName("two clinicians cannot share a room at the same time")
    void roomCannotBeDoubleBooked() throws Exception {
        Instant at = slot(0);
        book(booking(UUID.randomUUID(), CONSULT_ROOM, at))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.room.code").value(CONSULT_ROOM))
                .andExpect(jsonPath("$.room.resolved").value(true));

        // A different clinician, so the clinician constraint does not fire. Only the room clashes.
        book(booking(UUID.randomUUID(), CONSULT_ROOM, at))
                .andExpect(status().isConflict())
                // The message must name the room. "That slot has just been taken" would send
                // whoever is standing at the desk looking at the wrong thing.
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString(CONSULT_ROOM)))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("already in use")));
    }

    @Test
    @DisplayName("the adjacent slot in the same room is free")
    void adjacentSlotInTheSameRoomIsBookable() throws Exception {
        Instant at = slot(60);
        book(booking(UUID.randomUUID(), CONSULT_ROOM, at)).andExpect(status().isCreated());
        // Immediately after the first ends: the range is half-open, so this does not overlap.
        book(booking(UUID.randomUUID(), CONSULT_ROOM, at.plus(15, ChronoUnit.MINUTES)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("two clinicians at the same time in different rooms are both fine")
    void differentRoomsSameTimeAreIndependent() throws Exception {
        Instant at = slot(120);
        book(booking(UUID.randomUUID(), CONSULT_ROOM, at)).andExpect(status().isCreated());
        book(booking(UUID.randomUUID(), OTHER_ROOM, at)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a clinician clash still reports the clinician, not the room")
    void clinicianClashKeepsItsOwnMessage() throws Exception {
        UUID clinician = UUID.randomUUID();
        Instant at = slot(180);
        book(booking(clinician, CONSULT_ROOM, at)).andExpect(status().isCreated());

        // Same clinician, different room: the clinician constraint fires, and the two constraints
        // must not be conflated into one message.
        book(booking(clinician, OTHER_ROOM, at))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("slot has just been taken")));
    }

    @Test
    @DisplayName("a cancelled appointment frees its room")
    void cancellingFreesTheRoom() throws Exception {
        Instant at = slot(240);
        String id = objectMapper.readTree(
                        book(booking(UUID.randomUUID(), CONSULT_ROOM, at))
                                .andExpect(status().isCreated())
                                .andReturn().getResponse().getContentAsString())
                .get("id").asString();

        mockMvc.perform(delete("/appointments/{id}", id).with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "patient rescheduled"))))
                .andExpect(status().isOk());

        // The exclusion constraint's WHERE clause excludes CANCELLED, so the room is bookable again
        // rather than blocked until the end of time.
        book(booking(UUID.randomUUID(), CONSULT_ROOM, at)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("an appointment with no room is allowed — a teleconsultation has none")
    void aRoomIsOptional() throws Exception {
        Instant at = slot(300);
        book(booking(UUID.randomUUID(), null, at))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.room").doesNotExist());

        // And two roomless appointments at the same time do not collide with each other: the
        // constraint's partial WHERE excludes null room_id, so a null is not a room they share.
        book(booking(UUID.randomUUID(), null, at)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a room that cannot be booked is refused before anything is written")
    void unbookableSpaceIsRefused() throws Exception {
        // Casualty is clinical but bed-allocated: arrivals are unscheduled, and a booked outpatient
        // must never be sent to a resuscitation position.
        book(booking(UUID.randomUUID(), CASUALTY_BAY, slot(360)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("cannot take a booking")));
    }

    @Test
    @DisplayName("an unknown room code fails the booking rather than being written through")
    void unknownRoomFailsTheBooking() throws Exception {
        // Not a degraded booking with a bad code on it: a patient sent to a room that does not
        // exist is worse than a booking that had to be retried.
        book(booking(UUID.randomUUID(), "NO-SUCH-ROOM", slot(420)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("No such room")));
    }

    @Test
    @DisplayName("the response carries wayfinding a patient can act on")
    void responseCarriesWayfinding() throws Exception {
        book(booking(UUID.randomUUID(), CONSULT_ROOM, slot(480)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.room.name").value("General OPD"))
                .andExpect(jsonPath("$.room.floorName").value("Ground Floor"))
                // The one field that turns a room code into something a person can follow.
                .andExpect(jsonPath("$.room.directions").value(
                        org.hamcrest.Matchers.containsString("reception")));
    }

    /**
     * The booking response was the <em>only</em> place wayfinding ever resolved.
     *
     * <p>Every read path passed a null room into the mapper, so `room.resolved` came back false and
     * the name, floor and directions came back null — on the appointment book, on the patient's
     * chart, on the lapsed list, and after every check-in. The DTO's own comment claimed they were
     * "resolved live". Nothing caught it because no screen displayed a room until the appointment
     * book became writable, and the one test that looked only ever looked at the 201.
     *
     * <p>So this asserts the reads, one per shape: by id, in a page, and per patient.
     */
    @Test
    @DisplayName("wayfinding resolves on reads too, not only on the booking response")
    void wayfindingResolvesOnReads() throws Exception {
        Map<String, Object> body = booking(UUID.randomUUID(), CONSULT_ROOM, slot(495));
        String created = book(body).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();
        String mrn = String.valueOf(body.get("patientMrn"));
        String patientId = String.valueOf(body.get("patientId"));

        mockMvc.perform(get("/appointments/" + id).with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room.resolved").value(true))
                .andExpect(jsonPath("$.room.name").value("General OPD"));

        mockMvc.perform(get("/appointments")
                        .param("mrn", mrn)
                        .param("from", slotDate())
                        .param("to", slotDate())
                        .with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].room.resolved").value(true))
                .andExpect(jsonPath("$.content[0].room.floorName").value("Ground Floor"));

        mockMvc.perform(get("/appointments/patients/" + patientId).with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].room.resolved").value(true))
                .andExpect(jsonPath("$[0].room.directions").value(
                        org.hamcrest.Matchers.containsString("reception")));

        // And after a transition, which is where the front desk looks immediately after check-in.
        mockMvc.perform(post("/appointments/" + id + "/check-in").with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room.resolved").value(true))
                .andExpect(jsonPath("$.room.name").value("General OPD"));
    }

    @Test
    @DisplayName("a room the directory cannot answer for still renders its code, and says so")
    void anUnresolvableRoomIsHonestAboutIt() throws Exception {
        String id = objectMapper.readTree(
                        book(booking(UUID.randomUUID(), CONSULT_ROOM, slot(510)))
                                .andExpect(status().isCreated())
                                .andReturn().getResponse().getContentAsString())
                .get("id").asText();

        // The room is decommissioned, or patient-service is briefly unreachable. Rendering is not
        // booking: the read must degrade to the bare code rather than failing the whole page.
        when(roomDirectory.find(eq(CONSULT_ROOM), nullable(String.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/appointments/" + id).with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room.code").value(CONSULT_ROOM))
                .andExpect(jsonPath("$.room.resolved").value(false))
                .andExpect(jsonPath("$.room.name").doesNotExist());
    }

    @Test
    @DisplayName("a page of appointments in one room asks the directory once, not once per row")
    void theDirectoryIsNotAskedPerRow() throws Exception {
        // Without the per-request cache a 200-row page would make 200 calls to patient-service for
        // one answer. Three bookings in the same room must cost one lookup for the list.
        String mrn = "MRN-CACHE-" + System.nanoTime() % 100000;
        for (int i = 0; i < 3; i++) {
            Map<String, Object> body = booking(UUID.randomUUID(), CONSULT_ROOM, slot(525 + i * 15));
            body.put("patientMrn", mrn);
            book(body).andExpect(status().isCreated());
        }
        clearInvocations(roomDirectory);

        mockMvc.perform(get("/appointments")
                        .param("mrn", mrn)
                        .param("from", slotDate())
                        .param("to", slotDate())
                        .with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[2].room.resolved").value(true));

        verify(roomDirectory, times(1)).find(eq(CONSULT_ROOM), nullable(String.class));
    }
}
