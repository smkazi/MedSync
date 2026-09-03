package com.hms.scheduling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.scheduling.client.PortalIdentityClient;
import com.hms.scheduling.client.RoomDirectoryClient;
import java.time.Duration;
import java.time.Instant;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Self-booking, and everything a patient is not allowed to choose while doing it.
 *
 * <p>The booking itself goes through the same {@code AppointmentService} the front desk uses, so
 * the overlap constraint and the blackout check are not retested here. What is tested is the layer
 * on top: the patient comes from the token, the priority is forced to routine, no room is assigned,
 * and an appointment belonging to somebody else is not found.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalSchedulingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RoomDirectoryClient roomDirectory;

    /** The register, stubbed: this suite is about what scheduling does with the answer. */
    @MockitoBean
    private PortalIdentityClient identity;

    private UUID patient;
    private UUID clinician;

    @BeforeEach
    void stubTheWorld() {
        patient = UUID.randomUUID();
        clinician = UUID.randomUUID();
        when(identity.require(anyString()))
                .thenAnswer(invocation -> new PortalIdentityClient.PortalIdentity(patient, "MRN-PORTAL-1"));
        when(roomDirectory.find(nullable(String.class), nullable(String.class)))
                .thenReturn(Optional.empty());
    }

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder
                        .subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user")
                        .claim("roles", List.of(roles)))
                .authorities(authorities);
    }

    private static RequestPostProcessor asPatient(UUID patientId) {
        return jwt().jwt(builder -> builder
                        .subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "MRN-PORTAL-1")
                        .claim("roles", List.of("PATIENT"))
                        .claim("patient_id", patientId.toString()))
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_PATIENT")));
    }

    /** A slot a comfortable distance ahead, so nothing in this class contends with anything else. */
    private static Instant slot(int minutesFromNow) {
        return Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.HOURS)
                .plus(minutesFromNow, ChronoUnit.MINUTES);
    }

    private JsonNode book(UUID patientId, Instant at, Map<String, Object> extras) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "clinicianId", clinician,
                "departmentCode", "GEN",
                "startsAt", at.toString(),
                "durationMinutes", 15));
        body.putAll(extras);
        String created = mockMvc.perform(post("/portal/appointments").with(asPatient(patientId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created);
    }

    @Test
    @DisplayName("a patient books for themselves, and the booking names the patient the token does")
    void aPatientBooksForThemselves() throws Exception {
        JsonNode appointment = book(patient, slot(0), Map.of());

        assertThat(appointment.get("patientId").asString()).isEqualTo(patient.toString());
        // The MRN comes off the register rather than out of the request: a booking whose MRN a
        // caller supplied is a booking that can be filed against somebody else at the desk.
        assertThat(appointment.get("patientMrn").asString()).isEqualTo("MRN-PORTAL-1");
        assertThat(appointment.get("status").asString()).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("a patient cannot mark their own appointment urgent")
    void selfBookingIsAlwaysRoutine() throws Exception {
        // The staff request carries a priority; this one has nowhere to put it, which is the point.
        // Urgency is a triage decision belonging to a clinician, and self-declared urgency degrades
        // to noise within a week — at which point the flag stops meaning anything for the patients
        // who really are urgent.
        JsonNode appointment = book(patient, slot(60), Map.of("priority", "EMERGENCY"));
        assertThat(appointment.get("priority").asString()).isEqualTo("ROUTINE");
    }

    @Test
    @DisplayName("a patient cannot choose the room they are seen in")
    void selfBookingAssignsNoRoom() throws Exception {
        JsonNode appointment = book(patient, slot(120), Map.of("roomCode", "GF-GEN"));
        // Rooms are allocated by the desk against the day's whole list. A patient choosing one
        // would be choosing on behalf of everybody booked after them — and the portal request has
        // no field to name one in, so "GF-GEN" above is ignored rather than refused.
        assertThat(appointment.get("room") == null || appointment.get("room").isNull()).isTrue();
    }

    @Test
    @DisplayName("booking beyond the published horizon is refused, and the refusal says how far ahead")
    void theHorizonIsEnforced() throws Exception {
        Map<String, Object> body = Map.of(
                "clinicianId", clinician, "departmentCode", "GEN",
                "startsAt", Instant.now().plus(Duration.ofDays(400)).toString(),
                "durationMinutes", 15);
        mockMvc.perform(post("/portal/appointments").with(asPatient(patient))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("days ahead")));
    }

    @Test
    @DisplayName("a patient sees their own appointments and nobody else's, from a URL naming none")
    void theListIsTheSessionsOwn() throws Exception {
        book(patient, slot(180), Map.of());
        UUID somebodyElse = UUID.randomUUID();
        when(identity.require(anyString()))
                .thenAnswer(invocation -> new PortalIdentityClient.PortalIdentity(somebodyElse, "MRN-OTHER"));
        book(somebodyElse, slot(240), Map.of());

        String mine = mockMvc.perform(get("/portal/appointments").with(asPatient(patient)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode list = objectMapper.readTree(mine);
        assertThat(list).isNotEmpty();
        list.forEach(appointment ->
                assertThat(appointment.get("patientId").asString()).isEqualTo(patient.toString()));
    }

    @Test
    @DisplayName("cancelling somebody else's appointment is not found, rather than forbidden")
    void cancellingSomebodyElsesIsNotFound() throws Exception {
        JsonNode appointment = book(patient, slot(300), Map.of());
        String id = appointment.get("id").asString();

        // 404 rather than 403: an appointment id that comes back "not yours" is an appointment id
        // confirmed to exist, which is the first half of what an enumeration attack wants.
        mockMvc.perform(post("/portal/appointments/" + id + "/cancel").with(asPatient(UUID.randomUUID())))
                .andExpect(status().isNotFound());

        // And their own cancels normally.
        mockMvc.perform(post("/portal/appointments/" + id + "/cancel").with(asPatient(patient)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("the portal's availability is the same calculation the front desk sees")
    void availabilityIsShared() throws Exception {
        String date = java.time.LocalDate.now().plusDays(31).toString();
        String portal = mockMvc.perform(get("/portal/availability").with(asPatient(patient))
                        .param("clinicianId", clinician.toString()).param("date", date))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String desk = mockMvc.perform(get("/appointments/availability").with(as("RECEPTIONIST"))
                        .param("clinicianId", clinician.toString()).param("date", date))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // One calculator and one answer. A portal that computed availability separately would
        // eventually disagree with the desk about whether 10:30 is free, and the patient standing
        // at the counter would be the one told they are wrong.
        assertThat(portal).isEqualTo(desk);
    }

    @Test
    @DisplayName("staff are refused the portal, and a patient is refused the appointment book")
    void neitherSideCanUseTheOther() throws Exception {
        mockMvc.perform(get("/portal/appointments").with(as("ADMIN"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/portal/appointments").with(as("DOCTOR"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/appointments").with(asPatient(patient))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a patient sees their own visits, and an unsigned note is not one of them")
    void unsignedNotesAreNotPublished() throws Exception {
        JsonNode appointment = book(patient, slot(360), Map.of());
        String appointmentId = appointment.get("id").asString();
        mockMvc.perform(post("/appointments/" + appointmentId + "/check-in").with(as("RECEPTIONIST")))
                .andExpect(status().isOk());
        String encounter = mockMvc.perform(post("/encounters").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("appointmentId", appointmentId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String encounterId = objectMapper.readTree(encounter).get("id").asString();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/encounters/" + encounterId + "/note").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjective", "A draft nobody has stood behind yet."))))
                .andExpect(status().isOk());

        String mine = mockMvc.perform(get("/portal/encounters/" + encounterId).with(asPatient(patient)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // A draft is a sentence somebody is still deciding whether they believe. Showing it to its
        // subject makes it a statement they never made, and clinicians respond to that by not
        // drafting in the system.
        assertThat(objectMapper.readTree(mine).get("notes")).isEmpty();
        assertThat(mine).doesNotContain("nobody has stood behind");
    }
}
