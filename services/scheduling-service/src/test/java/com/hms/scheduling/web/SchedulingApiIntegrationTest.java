package com.hms.scheduling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Booking and charting against a real database — including the double-booking guard, which is a
 * PostgreSQL exclusion constraint and cannot be exercised any other way.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SchedulingApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user")
                        .claim("roles", List.of(roles)))
                .authorities(authorities);
    }

    /** A clinician nobody else in this suite is using, so slots never collide between tests. */
    private UUID freshClinician() {
        return UUID.randomUUID();
    }

    /** A future weekday instant, at a minute offset unique to the caller. */
    private Instant futureSlot(int minutesFromNoon) {
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(7);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date.atTime(9, 0).toInstant(ZoneOffset.UTC).plus(minutesFromNoon, ChronoUnit.MINUTES);
    }

    private JsonNode book(UUID clinicianId, Instant startsAt, int durationMinutes) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", UUID.randomUUID().toString());
        body.put("patientMrn", "MRN-TEST-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("clinicianId", clinicianId.toString());
        body.put("departmentCode", "GEN");
        body.put("startsAt", startsAt.toString());
        body.put("durationMinutes", durationMinutes);

        String response = mockMvc.perform(post("/appointments").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String openEncounterFor(String appointmentId) throws Exception {
        String response = mockMvc.perform(post("/encounters").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("appointmentId", appointmentId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asString();
    }

    private void writeNote(String encounterId, String assessment) throws Exception {
        mockMvc.perform(put("/encounters/" + encounterId + "/note").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjective", "Reported symptoms", "objective", "Examination findings",
                                "assessment", assessment, "plan", "Agreed plan"))))
                .andExpect(status().isOk());
    }

    // ---- booking ----------------------------------------------------------

    @Test
    @DisplayName("a booking is created as BOOKED and attributed to the caller")
    void bookingIsCreated() throws Exception {
        JsonNode appointment = book(freshClinician(), futureSlot(0), 15);

        assertThat(appointment.get("status").asString()).isEqualTo("BOOKED");
        assertThat(appointment.get("bookedBy").asString()).isEqualTo("test-user");
    }

    @Test
    @DisplayName("the database refuses a second booking overlapping the same clinician")
    void overlappingBookingIsRefused() throws Exception {
        UUID clinician = freshClinician();
        Instant start = futureSlot(30);
        book(clinician, start, 30);

        // Starts 15 minutes in, so it overlaps the second half of the first booking.
        Map<String, Object> clashing = new HashMap<>();
        clashing.put("patientId", UUID.randomUUID().toString());
        clashing.put("patientMrn", "MRN-CLASH");
        clashing.put("clinicianId", clinician.toString());
        clashing.put("departmentCode", "GEN");
        clashing.put("startsAt", start.plus(15, ChronoUnit.MINUTES).toString());

        mockMvc.perform(post("/appointments").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clashing)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an adjacent booking is allowed")
    void adjacentBookingIsAllowed() throws Exception {
        UUID clinician = freshClinician();
        Instant start = futureSlot(90);
        book(clinician, start, 15);

        // Half-open ranges: a booking starting exactly when the previous one ends is not a clash.
        book(clinician, start.plus(15, ChronoUnit.MINUTES), 15);
    }

    @Test
    @DisplayName("two different clinicians may hold the same time")
    void differentCliniciansMayShareATime() throws Exception {
        Instant start = futureSlot(150);
        book(freshClinician(), start, 15);
        book(freshClinician(), start, 15);
    }

    @Test
    @DisplayName("cancelling releases the slot for rebooking")
    void cancellingReleasesTheSlot() throws Exception {
        UUID clinician = freshClinician();
        Instant start = futureSlot(180);
        String appointmentId = book(clinician, start, 15).get("id").asString();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/appointments/" + appointmentId).with(as("RECEPTIONIST")))
                .andExpect(status().isOk());

        // The exclusion constraint excludes cancelled rows, so the time is free again.
        book(clinician, start, 15);
    }

    @Test
    @DisplayName("a booking in the past is rejected")
    void pastBookingIsRejected() throws Exception {
        Map<String, Object> body = Map.of(
                "patientId", UUID.randomUUID().toString(), "patientMrn", "MRN-PAST",
                "clinicianId", freshClinician().toString(), "departmentCode", "GEN",
                "startsAt", Instant.now().minus(2, ChronoUnit.DAYS).toString());

        mockMvc.perform(post("/appointments").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a future appointment cannot be marked as a no-show")
    void futureAppointmentCannotBeANoShow() throws Exception {
        String appointmentId = book(freshClinician(), futureSlot(210), 15).get("id").asString();

        mockMvc.perform(post("/appointments/" + appointmentId + "/no-show").with(as("RECEPTIONIST")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an illegal status transition is refused")
    void illegalTransitionIsRefused() throws Exception {
        String appointmentId = book(freshClinician(), futureSlot(240), 15).get("id").asString();

        // Straight from BOOKED to COMPLETED skips arrival and consultation.
        mockMvc.perform(post("/appointments/" + appointmentId + "/complete").with(as("DOCTOR")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("availability reflects a booking made against the pattern")
    void availabilityReflectsBookings() throws Exception {
        UUID clinician = freshClinician();
        LocalDate date = LocalDate.ofInstant(futureSlot(0), ZoneOffset.UTC);
        mockMvc.perform(post("/schedules").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clinicianId", clinician.toString(), "departmentCode", "GEN",
                                "dayOfWeek", date.getDayOfWeek().getValue(),
                                "startTime", "09:00:00", "endTime", "10:00:00", "slotMinutes", 15))))
                .andExpect(status().isCreated());

        String before = mockMvc.perform(get("/appointments/availability")
                        .param("clinicianId", clinician.toString())
                        .param("date", date.toString()).with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long availableBefore = countAvailable(before);

        book(clinician, date.atTime(9, 0).toInstant(ZoneOffset.UTC), 15);

        String after = mockMvc.perform(get("/appointments/availability")
                        .param("clinicianId", clinician.toString())
                        .param("date", date.toString()).with(as("RECEPTIONIST")))
                .andReturn().getResponse().getContentAsString();

        assertThat(countAvailable(after)).isEqualTo(availableBefore - 1);
    }

    private long countAvailable(String availabilityJson) {
        JsonNode slots = objectMapper.readTree(availabilityJson).get("slots");
        long available = 0;
        for (JsonNode slot : slots) {
            if (slot.get("available").asBoolean()) {
                available++;
            }
        }
        return available;
    }

    // ---- encounters and notes ---------------------------------------------

    @Test
    @DisplayName("opening an encounter from an appointment moves it to in progress")
    void openingAnEncounterStartsTheAppointment() throws Exception {
        String appointmentId = book(freshClinician(), futureSlot(300), 15).get("id").asString();
        mockMvc.perform(post("/appointments/" + appointmentId + "/check-in").with(as("RECEPTIONIST")));

        String encounterId = openEncounterFor(appointmentId);

        mockMvc.perform(get("/appointments/" + appointmentId).with(as("DOCTOR")))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.encounterId").value(encounterId));
    }

    @Test
    @DisplayName("one appointment cannot have two encounters")
    void oneEncounterPerAppointment() throws Exception {
        String appointmentId = book(freshClinician(), futureSlot(330), 15).get("id").asString();
        openEncounterFor(appointmentId);

        mockMvc.perform(post("/encounters").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("appointmentId", appointmentId))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an unsigned note is edited in place")
    void unsignedNoteIsEditedInPlace() throws Exception {
        String encounterId = openEncounterFor(book(freshClinician(), futureSlot(360), 15)
                .get("id").asString());

        writeNote(encounterId, "First impression");
        writeNote(encounterId, "Revised impression");

        mockMvc.perform(get("/encounters/" + encounterId + "/note/history").with(as("DOCTOR")))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].assessment").value("Revised impression"));
    }

    @Test
    @DisplayName("editing a signed note creates an addendum and leaves the original intact")
    void signedNoteBecomesAnAddendum() throws Exception {
        String encounterId = openEncounterFor(book(freshClinician(), futureSlot(390), 15)
                .get("id").asString());
        writeNote(encounterId, "Signed impression");
        mockMvc.perform(post("/encounters/" + encounterId + "/note/sign").with(as("DOCTOR")))
                .andExpect(status().isOk());

        writeNote(encounterId, "Corrected impression");

        mockMvc.perform(get("/encounters/" + encounterId + "/note/history").with(as("DOCTOR")))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].assessment").value("Signed impression"))
                .andExpect(jsonPath("$[0].signed").value(true))
                .andExpect(jsonPath("$[1].assessment").value("Corrected impression"))
                .andExpect(jsonPath("$[1].amendsId").exists());
    }

    @Test
    @DisplayName("only a doctor may sign a note")
    void signingIsDoctorOnly() throws Exception {
        String encounterId = openEncounterFor(book(freshClinician(), futureSlot(420), 15)
                .get("id").asString());
        writeNote(encounterId, "Impression");

        mockMvc.perform(post("/encounters/" + encounterId + "/note/sign").with(as("NURSE")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/encounters/" + encounterId + "/note/sign").with(as("DOCTOR")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an empty note cannot be signed")
    void emptyNoteCannotBeSigned() throws Exception {
        String encounterId = openEncounterFor(book(freshClinician(), futureSlot(450), 15)
                .get("id").asString());
        mockMvc.perform(put("/encounters/" + encounterId + "/note").with(as("DOCTOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("subjective", "   "))));

        mockMvc.perform(post("/encounters/" + encounterId + "/note/sign").with(as("DOCTOR")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an encounter cannot be closed while its note is unsigned")
    void cannotCloseWithAnUnsignedNote() throws Exception {
        String encounterId = openEncounterFor(book(freshClinician(), futureSlot(480), 15)
                .get("id").asString());
        writeNote(encounterId, "Unsigned impression");

        mockMvc.perform(post("/encounters/" + encounterId + "/close").with(as("DOCTOR")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("closing a charted encounter completes its appointment")
    void closingCompletesTheAppointment() throws Exception {
        String appointmentId = book(freshClinician(), futureSlot(510), 15).get("id").asString();
        mockMvc.perform(post("/appointments/" + appointmentId + "/check-in").with(as("RECEPTIONIST")));
        String encounterId = openEncounterFor(appointmentId);
        writeNote(encounterId, "Impression");
        mockMvc.perform(post("/encounters/" + encounterId + "/note/sign").with(as("DOCTOR")));

        mockMvc.perform(post("/encounters/" + encounterId + "/close").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(get("/appointments/" + appointmentId).with(as("DOCTOR")))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("vitals are recorded with a derived BMI")
    void vitalsDeriveBodyMassIndex() throws Exception {
        String encounterId = openEncounterFor(book(freshClinician(), futureSlot(540), 15)
                .get("id").asString());

        mockMvc.perform(post("/encounters/" + encounterId + "/vitals").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "heartRate", 92, "systolicBp", 112, "diastolicBp", 70,
                                "weightKg", 58.5, "heightCm", 162.0))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bodyMassIndex").value(22.3))
                .andExpect(jsonPath("$.recordedBy").value("test-user"));
    }

    @Test
    @DisplayName("observations come back with a NEWS2 score, its parts, and the local policy")
    void vitalsCarryAnEarlyWarningScore() throws Exception {
        String encounterId = openEncounterFor(book(freshClinician(), futureSlot(1080), 15)
                .get("id").asString());

        // Respirations 22 (2), SpO2 93 (2), air (0), systolic 105 (1), pulse 115 (2),
        // alert (0), 37.2 (0) = 7, which bands HIGH on the published chart.
        mockMvc.perform(post("/encounters/" + encounterId + "/vitals").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "respiratoryRate", 22, "oxygenSaturation", 93, "systolicBp", 105,
                                "heartRate", 115, "consciousness", "ALERT",
                                "temperatureC", 37.2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.news2.total").value(7))
                .andExpect(jsonPath("$.news2.band").value("HIGH"))
                // The parts, not just the number: a score a clinician cannot see the working of is
                // not a score they should be asked to act on.
                .andExpect(jsonPath("$.news2.components.length()").value(7))
                .andExpect(jsonPath("$.news2.missing").isEmpty())
                // And the hospital's own response, which is configuration while the score is not.
                .andExpect(jsonPath("$.news2.escalation.response").value(
                        org.hamcrest.Matchers.containsString("Emergency assessment")));
    }

    @Test
    @DisplayName("supplemental oxygen is worth two points, and it has to be recorded to count")
    void supplementalOxygenIsRecordedNotInferred() throws Exception {
        String encounterId = openEncounterFor(book(freshClinician(), futureSlot(1110), 15)
                .get("id").asString());

        // The same saturation twice. 98% on oxygen is a different patient from 98% on air, and it
        // cannot be read off the number - which is why the flag exists at all.
        mockMvc.perform(post("/encounters/" + encounterId + "/vitals").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "respiratoryRate", 16, "oxygenSaturation", 98, "systolicBp", 120,
                                "heartRate", 70, "consciousness", "ALERT", "temperatureC", 36.8))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.news2.total").value(0))
                .andExpect(jsonPath("$.onSupplementalOxygen").value(false));

        mockMvc.perform(post("/encounters/" + encounterId + "/vitals").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "respiratoryRate", 16, "oxygenSaturation", 98, "systolicBp", 120,
                                "heartRate", 70, "consciousness", "ALERT", "temperatureC", 36.8,
                                "onSupplementalOxygen", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.news2.total").value(2))
                .andExpect(jsonPath("$.onSupplementalOxygen").value(true));
    }

    @Test
    @DisplayName("an incomplete set of observations names what is missing rather than assuming it")
    void missingObservationsAreNamed() throws Exception {
        String encounterId = openEncounterFor(book(freshClinician(), futureSlot(1140), 15)
                .get("id").asString());

        // The most dangerous thing this could do is treat an absent respiratory rate as normal,
        // because that turns an incomplete set into a reassuring number.
        mockMvc.perform(post("/encounters/" + encounterId + "/vitals").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "systolicBp", 120, "heartRate", 70))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.news2.total").value(0))
                .andExpect(jsonPath("$.news2.missing").value(
                        org.hamcrest.Matchers.hasItems("Respiration rate", "SpO2",
                                "Consciousness", "Temperature")));
    }

    @Test
    @DisplayName("the escalation policy is readable by a clinician and writable by an administrator")
    void escalationPolicyIsConfiguration() throws Exception {
        String body = mockMvc.perform(get("/escalation-policies").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andReturn().getResponse().getContentAsString();
        JsonNode high = null;
        for (JsonNode policy : objectMapper.readTree(body)) {
            if ("HIGH".equals(policy.get("band").asString())) {
                high = policy;
            }
        }
        org.assertj.core.api.Assertions.assertThat(high).isNotNull();
        String id = high.get("id").asString();
        String original = high.get("monitoring").asString();

        mockMvc.perform(patch("/escalation-policies/" + id).with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monitoring\": \"Every 5 minutes\"}"))
                .andExpect(status().isForbidden());

        try {
            mockMvc.perform(patch("/escalation-policies/" + id).with(as("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"monitoring\": \"Continuous, with a consultant at the bedside\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.monitoring")
                            .value("Continuous, with a consultant at the bedside"))
                    // The band is the calculator's output, not a name somebody chose, so it is not
                    // editable and a request that tried would be ignored rather than obeyed.
                    .andExpect(jsonPath("$.band").value("HIGH"));
        } finally {
            mockMvc.perform(patch("/escalation-policies/" + id).with(as("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("monitoring", original))));
        }
    }

    @Test
    @DisplayName("an out-of-range observation is rejected")
    void impossibleVitalsAreRejected() throws Exception {
        String encounterId = openEncounterFor(book(freshClinician(), futureSlot(570), 15)
                .get("id").asString());

        mockMvc.perform(post("/encounters/" + encounterId + "/vitals").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("oxygenSaturation", 150))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the same diagnosis code cannot be recorded twice on one encounter")
    void duplicateDiagnosisIsRejected() throws Exception {
        String encounterId = openEncounterFor(book(freshClinician(), futureSlot(600), 15)
                .get("id").asString());
        String body = objectMapper.writeValueAsString(Map.of(
                "icd10Code", "D50.9", "description", "Iron deficiency anaemia", "category", "PRIMARY"));

        mockMvc.perform(post("/encounters/" + encounterId + "/diagnoses").with(as("DOCTOR"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/encounters/" + encounterId + "/diagnoses").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a receptionist cannot write clinical content")
    void receptionistCannotChart() throws Exception {
        String encounterId = openEncounterFor(book(freshClinician(), futureSlot(630), 15)
                .get("id").asString());

        mockMvc.perform(put("/encounters/" + encounterId + "/note").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("assessment", "x"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an anonymous request reaches nothing")
    void anonymousAccessIsRejected() throws Exception {
        mockMvc.perform(get("/appointments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/encounters/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
}
