package com.hms.scheduling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.common.error.ConflictException;
import com.hms.scheduling.client.OrderingClient;
import com.hms.scheduling.client.RoomDirectoryClient;
import com.hms.scheduling.client.StaffDirectoryClient;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Order sets and care plans against a real database.
 *
 * <p>The assertion that matters most here is the compensation: applying a set is a saga across two
 * other services, and when the second step fails the first has to be undone. That is proven by
 * making the laboratory step throw and then checking that the prescription was cancelled — which
 * is only possible with the client stubbed, and is exactly what a stub is for. The real
 * cross-service call is exercised in {@code tests/api}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CareApiIntegrationTest {

    @MockitoBean
    private RoomDirectoryClient roomDirectory;

    /**
     * The two services an order set reaches, stubbed.
     *
     * <p>Neither is running here, and the point of these tests is what this service does with their
     * answers — including the answer nobody wants.
     */
    @MockitoBean
    private OrderingClient ordering;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID prescriptionId;
    private UUID labOrderId;

    @BeforeEach
    void bothStepsSucceedByDefault() {
        prescriptionId = UUID.randomUUID();
        labOrderId = UUID.randomUUID();
        when(ordering.prescribe(any(), nullable(String.class)))
                .thenReturn(new OrderingClient.Raised(prescriptionId, "1 medicine(s)"));
        when(ordering.orderTests(any(), nullable(String.class)))
                .thenReturn(new OrderingClient.Raised(labOrderId, "2 test(s)"));
        when(ordering.cancelPrescription(any(UUID.class), nullable(String.class))).thenReturn(true);
    }

    /**
     * Stubbed, like {@code RoomDirectoryClient} where that appears, and for the same reason: it
     * reaches patient-service over HTTP, which is not running here. What it answers is deliberately
     * permissive — every id is a clinician — because these tests are about scheduling; a test of
     * what happens when it refuses lives with the narrowing itself.
     */
    @MockitoBean
    private StaffDirectoryClient staffDirectory;

    @BeforeEach
    void everyIdIsAClinician() {
        Mockito.when(staffDirectory.require(any(UUID.class), nullable(String.class)))
                .thenAnswer(call -> new StaffDirectoryClient.Clinician(
                        call.getArgument(0), "Test Clinician", "Consultant", "GEN"));
    }

    /**
     * One identity per role, stable for the whole class.
     *
     * <p>It used to be a fresh random subject on every call, which stopped mattering the moment
     * chart access depended on who you are: the doctor who opened an encounter and the doctor who
     * read it back two lines later were different people, and every chart read in this file would
     * have been refused for a reason that has nothing to do with what the test is about. A suite
     * where "the doctor" is one doctor is also simply closer to a clinic.
     */
    private static final Map<String, UUID> IDENTITIES = new ConcurrentHashMap<>();

    private static UUID subjectFor(String... roles) {
        return IDENTITIES.computeIfAbsent(roles.length == 0 ? "anonymous" : roles[0],
                key -> UUID.randomUUID());
    }

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder.subject(subjectFor(roles).toString())
                        .claim("preferred_username", "test-user"))
                .authorities(authorities);
    }

    private Instant futureSlot(int minutesFromNine) {
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(21);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date.atTime(9, 0).toInstant(ZoneOffset.UTC).plus(minutesFromNine, ChronoUnit.MINUTES);
    }

    /** An open encounter, which is what both features hang off. */
    private JsonNode openEncounter() throws Exception {
        Map<String, Object> booking = new HashMap<>();
        booking.put("patientId", UUID.randomUUID().toString());
        booking.put("patientMrn", "MRN-CARE-" + UUID.randomUUID().toString().substring(0, 8));
        booking.put("clinicianId", UUID.randomUUID().toString());
        booking.put("departmentCode", "GEN");
        booking.put("startsAt", futureSlot((int) (Math.random() * 2000)).toString());
        booking.put("durationMinutes", 15);

        String appointment = objectMapper.readTree(
                mockMvc.perform(post("/appointments").with(as("RECEPTIONIST"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(booking)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("id").asString();

        return objectMapper.readTree(mockMvc.perform(post("/encounters").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("appointmentId", appointment))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode applySet(String code, String encounterId, int expectedStatus) throws Exception {
        return objectMapper.readTree(
                mockMvc.perform(post("/order-sets/" + code + "/apply").with(as("DOCTOR"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("encounterId", encounterId)))) 
                        .andExpect(status().is(expectedStatus))
                        .andReturn().getResponse().getContentAsString());
    }

    // ---- order sets ----------------------------------------------------------

    @Test
    @DisplayName("the seeded sets are offered, and a medication line carries a complete dose")
    void theSeededSetsAreComplete() throws Exception {
        String body = mockMvc.perform(get("/order-sets").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode sets = objectMapper.readTree(body);

        assertThat(sets.size()).isGreaterThanOrEqualTo(3);
        for (JsonNode set : sets) {
            for (JsonNode item : set.get("items")) {
                if ("MEDICATION".equals(item.get("kind").asString())) {
                    // The whole point of the CHECK constraint: an order set is applied in one
                    // click, so a line with no dose would be a dose nobody chose.
                    assertThat(item.get("dose").asString()).isNotBlank();
                    assertThat(item.get("frequency").asString()).isNotBlank();
                    assertThat(item.get("quantity").asInt()).isPositive();
                }
            }
        }
    }

    @Test
    @DisplayName("applying a set raises the medicines first and then the tests, and says what it did")
    void applyingRaisesBoth() throws Exception {
        JsonNode encounter = openEncounter();

        JsonNode applied = applySet("FEVER1", encounter.get("id").asString(), 200);

        assertThat(applied.get("prescriptionId").asString()).isEqualTo(prescriptionId.toString());
        assertThat(applied.get("labOrderId").asString()).isEqualTo(labOrderId.toString());
        assertThat(applied.get("compensated").asBoolean()).isFalse();
        assertThat(applied.get("message").asString()).contains("Fever, first line");
    }

    @Test
    @DisplayName("a set with no medicines raises no prescription at all")
    void aLabOnlySetTouchesThePharmacyNotAtAll() throws Exception {
        JsonNode encounter = openEncounter();

        JsonNode applied = applySet("ANAEMIA", encounter.get("id").asString(), 200);

        assertThat(applied.get("prescriptionId").isNull()).isTrue();
        assertThat(applied.get("labOrderId").asString()).isEqualTo(labOrderId.toString());
        verify(ordering, never()).prescribe(any(), nullable(String.class));
    }

    @Test
    @DisplayName("a refused prescription leaves nothing behind, because it is raised first")
    void aRefusedPrescriptionRaisesNoTests() throws Exception {
        // The reason the prescription goes first. It is the step that can be refused for a clinical
        // reason, and a refusal at that point means there is nothing to undo.
        when(ordering.prescribe(any(), nullable(String.class)))
                .thenThrow(new ConflictException(
                        "This order cannot be written: recorded life threatening allergy to Penicillin"));
        JsonNode encounter = openEncounter();

        JsonNode refusal = applySet("FEVER1", encounter.get("id").asString(), 409);

        // The callee's own sentence, kept: what it found, not "could not apply the set".
        assertThat(refusal.get("detail").asString()).contains("life threatening allergy");
        verify(ordering, never()).orderTests(any(), nullable(String.class));
        verify(ordering, never()).cancelPrescription(any(UUID.class), nullable(String.class));
    }

    @Test
    @DisplayName("a failed laboratory step withdraws the prescription that was already raised")
    void theSagaCompensates() throws Exception {
        when(ordering.orderTests(any(), nullable(String.class)))
                .thenThrow(new ConflictException("Unknown test code 'CBC5'"));
        JsonNode encounter = openEncounter();

        JsonNode refusal = applySet("FEVER1", encounter.get("id").asString(), 409);

        verify(ordering, times(1)).cancelPrescription(prescriptionId, null);
        assertThat(refusal.get("detail").asString())
                .contains("has been withdrawn")
                .contains("nothing from this set is outstanding");
    }

    @Test
    @DisplayName("when the withdrawal also fails, the refusal names the prescription to cancel by hand")
    void aFailedCompensationIsSaidOutLoud() throws Exception {
        // The one state that needs a person. Saying "something went wrong" here would leave a live
        // prescription nobody knows about; naming it is what makes the saga honest.
        when(ordering.orderTests(any(), nullable(String.class)))
                .thenThrow(new ConflictException("Laboratory unreachable"));
        when(ordering.cancelPrescription(any(UUID.class), nullable(String.class))).thenReturn(false);
        JsonNode encounter = openEncounter();

        JsonNode refusal = applySet("FEVER1", encounter.get("id").asString(), 409);

        assertThat(refusal.get("detail").asString())
                .contains("could not be withdrawn")
                .contains(prescriptionId.toString())
                .contains("cancelled by hand");
    }

    @Test
    @DisplayName("a nurse applying a set with medicines is refused by the pharmacy, not by a second role list")
    void theDownstreamServiceDecidesWhoMayPrescribe() throws Exception {
        // The rule lives in pharmacy-service, and the caller's own token is what goes downstream.
        // A copy of the role list here could drift from it, and the drift would be silent.
        when(ordering.prescribe(any(), nullable(String.class)))
                .thenThrow(new AccessDeniedException("Your role cannot raise the medicines in this set."));
        JsonNode encounter = openEncounter();

        mockMvc.perform(post("/order-sets/FEVER1/apply").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("encounterId", encounter.get("id").asString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a laboratory technician cannot apply a set at all")
    void applyingIsAClinicalAct() throws Exception {
        JsonNode encounter = openEncounter();
        mockMvc.perform(post("/order-sets/ANAEMIA/apply").with(as("LAB_TECH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("encounterId", encounter.get("id").asString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("composing a set is administrative, and an incomplete medication line is refused")
    void writingASetIsAdminAndTheLinesMustBeComplete() throws Exception {
        String code = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT);

        // A doctor may apply a set and not write one: it is a template somebody else clicks.
        mockMvc.perform(post("/order-sets").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code, "name", "Test set",
                                "items", List.of(Map.of("kind", "LAB", "code", "CBC"))))))
                .andExpect(status().isForbidden());

        // A medication line with no dose is refused with a message about the line, not a constraint.
        String refusal = mockMvc.perform(post("/order-sets").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code, "name", "Test set",
                                "items", List.of(Map.of("kind", "MEDICATION", "code", "PARA500"))))))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(refusal).get("detail").asString())
                .contains("needs a dose");

        // And a laboratory line carrying a dose is a medicine typed as a test.
        mockMvc.perform(post("/order-sets").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code, "name", "Test set",
                                "items", List.of(Map.of("kind", "LAB", "code", "CBC",
                                        "dose", "1 tablet"))))))
                .andExpect(status().isBadRequest());

        // A complete one is accepted, and comes back with both kinds in display order.
        mockMvc.perform(post("/order-sets").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code, "name", "Test set", "departmentCode", "GEN",
                                "items", List.of(
                                        Map.of("kind", "LAB", "code", "CBC", "priority", "URGENT"),
                                        Map.of("kind", "MEDICATION", "code", "PARA500",
                                                "dose", "1 tablet", "frequency", "twice daily",
                                                "durationDays", 3, "quantity", 6))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    @DisplayName("a retired set cannot be applied")
    void aRetiredSetIsRefused() throws Exception {
        String code = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT);
        mockMvc.perform(post("/order-sets").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code, "name", "Retired set",
                                "items", List.of(Map.of("kind", "LAB", "code", "CBC"))))))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/order-sets/" + code).with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk());

        JsonNode encounter = openEncounter();
        JsonNode refusal = applySet(code, encounter.get("id").asString(), 400);
        assertThat(refusal.get("detail").asString()).contains("retired");
    }

    // ---- care plans ----------------------------------------------------------

    @Test
    @DisplayName("a plan is written with goals, and a second plan for one encounter is refused")
    void onePlanPerEncounter() throws Exception {
        JsonNode encounter = openEncounter();
        String encounterId = encounter.get("id").asString();

        mockMvc.perform(post("/care-plans").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "encounterId", encounterId, "title", "Admission plan",
                                "goals", List.of(
                                        Map.of("description", "Mobilising independently",
                                                "targetDate", LocalDate.now().plusDays(3).toString()),
                                        Map.of("description", "Pain controlled on oral analgesia"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.goals.length()").value(2));

        String refusal = mockMvc.perform(post("/care-plans").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "encounterId", encounterId, "title", "Another plan"))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(refusal).get("detail").asString())
                .contains("already has a care plan");
    }

    @Test
    @DisplayName("a goal cannot name a problem the patient has not been diagnosed with")
    void goalsAreLinkedToRealProblems() throws Exception {
        JsonNode encounter = openEncounter();
        String encounterId = encounter.get("id").asString();

        String refusal = mockMvc.perform(post("/care-plans").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "encounterId", encounterId, "title", "Plan",
                                "goals", List.of(Map.of("description", "HbA1c below 7",
                                        "problemCode", "E11.9"))))))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(refusal).get("detail").asString())
                .contains("no diagnosis of E11.9");

        // Record the diagnosis, and the same goal is accepted.
        mockMvc.perform(post("/encounters/" + encounterId + "/diagnoses").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "icd10Code", "E11.9", "description", "Type 2 diabetes mellitus",
                                "category", "PRIMARY"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/care-plans").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "encounterId", encounterId, "title", "Plan",
                                "goals", List.of(Map.of("description", "HbA1c below 7",
                                        "problemCode", "E11.9"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.goals[0].problemCode").value("E11.9"));
    }

    @Test
    @DisplayName("an outcome other than met needs a note, and the plan will not close with an open goal")
    void closingAPlanForcesADecisionOnEveryGoal() throws Exception {
        JsonNode encounter = openEncounter();
        JsonNode plan = objectMapper.readTree(
                mockMvc.perform(post("/care-plans").with(as("DOCTOR"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "encounterId", encounter.get("id").asString(),
                                        "title", "Plan",
                                        "goals", List.of(
                                                Map.of("description", "Walking to the bathroom"),
                                                Map.of("description", "Off oxygen"))))))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString());
        String planId = plan.get("id").asString();
        String firstGoal = plan.get("goals").get(0).get("id").asString();
        String secondGoal = plan.get("goals").get(1).get("id").asString();

        // "Not met" with nothing beside it is a record nobody can learn from.
        mockMvc.perform(patch("/care-plans/goals/" + firstGoal).with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"NOT_MET\"}"))
                .andExpect(status().isBadRequest());

        // An open goal blocks completion, which is the point: it makes somebody decide.
        String refusal = mockMvc.perform(post("/care-plans/" + planId + "/close?outcome=COMPLETED")
                        .with(as("DOCTOR")))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(refusal).get("detail").asString())
                .contains("still open");

        mockMvc.perform(patch("/care-plans/goals/" + firstGoal).with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"MET\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/care-plans/goals/" + secondGoal).with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ABANDONED\", \"progressNote\": \"Home oxygen arranged instead\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/care-plans/" + planId + "/close?outcome=COMPLETED").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("the front desk cannot read a care plan, and the laboratory cannot either")
    void aCarePlanIsChartContent() throws Exception {
        JsonNode encounter = openEncounter();
        String encounterId = encounter.get("id").asString();
        mockMvc.perform(post("/care-plans").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "encounterId", encounterId, "title", "Plan"))))
                .andExpect(status().isCreated());

        for (String role : List.of("RECEPTIONIST", "LAB_TECH")) {
            mockMvc.perform(get("/care-plans/encounters/" + encounterId).with(as(role)))
                    .andExpect(status().isForbidden());
        }
        // A pathologist may read a chart, and a care plan is chart content.
        mockMvc.perform(get("/care-plans/encounters/" + encounterId).with(as("PATHOLOGIST")))
                .andExpect(status().isOk());
    }
}
