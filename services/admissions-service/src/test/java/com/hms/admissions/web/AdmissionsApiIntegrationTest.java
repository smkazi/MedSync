package com.hms.admissions.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.admissions.client.BedDirectoryClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Casualty and in-patient care against a real database.
 *
 * <p>Two assertions here carry more weight than the rest. One bed can hold one patient, proven by
 * racing real threads at the last bed rather than by reading the code — and the casualty board is
 * ordered by acuity before arrival, proven by putting the sickest patient in last.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdmissionsApiIntegrationTest {

    /**
     * The facility directory, stubbed.
     *
     * <p>It reaches patient-service over HTTP, which is not running here, and the client fails
     * closed — so without this every allocation would refuse rather than exercising the occupancy
     * index. The cross-service call itself is covered by the journey in {@code tests/api} and was
     * verified by hand against a live stack.
     */
    @MockitoBean
    private BedDirectoryClient bedDirectory;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private List<BedDirectoryClient.Bed> casualtyBeds;
    private List<BedDirectoryClient.Bed> wardBeds;

    @BeforeEach
    void stubTheDirectory() {
        casualtyBeds = beds("CAS", 4, "GF-CAS", "Casualty");
        wardBeds = beds("WARD", 4, "F1-W1", "Ward One");

        when(bedDirectory.bedsOfTypes(anyList(), nullable(String.class))).thenAnswer(call -> {
            List<String> types = call.getArgument(0);
            return types.contains("WARD") ? wardBeds : casualtyBeds;
        });
        // The real `require` filters the list it fetched; the stub does the same rather than
        // answering anything, so a test that asks for a ward bed on the casualty path still gets
        // the refusal a clinician would.
        when(bedDirectory.require(nullable(UUID.class), anyList(), nullable(String.class)))
                .thenAnswer(call -> {
                    UUID wanted = call.getArgument(0);
                    List<String> types = call.getArgument(1);
                    List<BedDirectoryClient.Bed> pool = types.contains("WARD") ? wardBeds : casualtyBeds;
                    return pool.stream().filter(bed -> bed.id().equals(wanted)).findFirst()
                            .orElseThrow(() -> new com.hms.common.error.BadRequestException(
                                    "Bed " + wanted + " is not an available bed"));
                });
    }

    /** A fresh pool per test, so one test's occupancy cannot make another's bed look taken. */
    private static List<BedDirectoryClient.Bed> beds(String prefix, int count, String roomCode,
                                                     String roomName) {
        List<BedDirectoryClient.Bed> pool = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            pool.add(new BedDirectoryClient.Bed(UUID.randomUUID(),
                    "%s-%s-%d".formatted(prefix, UUID.randomUUID().toString().substring(0, 4), index),
                    "Bed " + index, roomCode, roomName, "Ground Floor"));
        }
        return pool;
    }

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user"))
                .authorities(authorities);
    }

    private JsonNode arrive(int acuity, String complaint) throws Exception {
        String body = mockMvc.perform(post("/casualty").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID(),
                                "patientMrn", "MRN-CAS-" + System.nanoTime() % 1000000,
                                "triageAcuity", acuity,
                                "presentingComplaint", complaint))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private <T> List<T> inParallel(int n, Callable<T> task) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> futures = pool.invokeAll(java.util.Collections.nCopies(n, task));
            List<T> results = new ArrayList<>(n);
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    @Test
    @DisplayName("the board is ordered sickest first, whatever order people arrived in")
    void theBoardIsOrderedByAcuity() throws Exception {
        // Deliberately backwards: the least urgent arrives first and the most urgent last. A queue
        // served in arrival order kills the person who arrived last and is the sickest, and that
        // is the whole clinical point of this module.
        String nonUrgent = arrive(5, "Sore throat for two days").get("id").asString();
        String standard = arrive(3, "Ankle injury").get("id").asString();
        String immediate = arrive(1, "Collapsed, not breathing normally").get("id").asString();

        String body = mockMvc.perform(get("/casualty").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> order = new ArrayList<>();
        for (JsonNode row : objectMapper.readTree(body)) {
            order.add(row.get("id").asString());
        }

        assertThat(order.indexOf(immediate))
                .as("acuity 1 comes before acuity 3, which comes before acuity 5")
                .isLessThan(order.indexOf(standard));
        assertThat(order.indexOf(standard)).isLessThan(order.indexOf(nonUrgent));
    }

    @Test
    @DisplayName("two patients of the same acuity are served in the order they arrived")
    void tiesAreBrokenByArrival() throws Exception {
        String first = arrive(3, "Wrist pain").get("id").asString();
        String second = arrive(3, "Wrist pain").get("id").asString();

        String body = mockMvc.perform(get("/casualty").with(as("NURSE")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> order = new ArrayList<>();
        for (JsonNode row : objectMapper.readTree(body)) {
            if (row.get("triageAcuity").asInt() == 3) {
                order.add(row.get("id").asString());
            }
        }
        assertThat(order.indexOf(first)).isLessThan(order.indexOf(second));
    }

    @Test
    @DisplayName("re-triage moves a patient up the board, because waiting makes people worse")
    void retriageReordersTheBoard() throws Exception {
        String deteriorating = arrive(4, "Abdominal pain").get("id").asString();
        String sicker = arrive(2, "Chest pain").get("id").asString();

        List<String> before = boardOrder();
        assertThat(before.indexOf(sicker))
                .as("at acuity 4 they are behind the acuity-2 patient")
                .isLessThan(before.indexOf(deteriorating));

        mockMvc.perform(patch("/casualty/" + deteriorating + "/triage").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triageAcuity\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triageAcuity").value(1));

        // Relative to the patient they overtook, not "at the top" — the board is shared with
        // every other test in this class, several of which arrive at acuity 1, so a position
        // assertion would be right on the first test to run and wrong on the rest.
        List<String> after = boardOrder();
        assertThat(after.indexOf(deteriorating))
                .as("re-triaged to 1, they are now ahead of the acuity-2 patient")
                .isLessThan(after.indexOf(sicker));
    }

    @Test
    @DisplayName("an untriaged arrival is refused: there is no default acuity")
    void acuityIsRequired() throws Exception {
        // No default, deliberately. An untriaged patient sorted as though they were a 3 is exactly
        // the failure the board exists to prevent, and it would be invisible.
        mockMvc.perform(post("/casualty").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID(), "patientMrn", "MRN-X",
                                "presentingComplaint", "Unwell"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/casualty").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID(), "patientMrn", "MRN-X",
                                "triageAcuity", 6, "presentingComplaint", "Unwell"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("two clinicians allocating the last bed: one succeeds, one is told the bed is gone")
    void oneBedHoldsOnePatient() throws Exception {
        UUID bed = casualtyBeds.get(0).id();
        int contenders = 8;
        // A concurrent queue, and each thread takes one: an ArrayList handed out with remove(0)
        // from eight threads is itself a race, which would make the test's own bookkeeping the
        // thing under test.
        java.util.Queue<String> waiting = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int i = 0; i < contenders; i++) {
            waiting.add(arrive(2, "Chest pain").get("id").asString());
        }

        // Eight clinicians, one bed, at the same instant. The application-level "is it free?"
        // check passes for all of them; the partial unique index is what decides.
        List<Integer> statuses = inParallel(contenders, () -> {
            String attendance = waiting.poll();
            return mockMvc.perform(post("/casualty/" + attendance + "/bed").with(as("NURSE"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bedId\": \"" + bed + "\"}"))
                    .andReturn().getResponse().getStatus();
        });

        assertThat(statuses.stream().filter(status -> status == 200).count())
                .as("exactly one patient gets the bed")
                .isEqualTo(1);
        assertThat(statuses.stream().filter(status -> status == 409).count())
                .as("everybody else is told it has gone, as a conflict rather than a 500")
                .isEqualTo(contenders - 1L);
    }

    @Test
    @DisplayName("a refusal names the bed, because \"conflict\" tells a nurse nothing")
    void theConflictSaysWhichBed() throws Exception {
        UUID bed = casualtyBeds.get(1).id();
        String first = arrive(3, "Laceration").get("id").asString();
        String second = arrive(3, "Laceration").get("id").asString();

        mockMvc.perform(post("/casualty/" + first + "/bed").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bedId\": \"" + bed + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/casualty/" + second + "/bed").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bedId\": \"" + bed + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString(casualtyBeds.get(1).code())));
    }

    @Test
    @DisplayName("a discharged bed becomes allocatable again")
    void dischargeFreesTheBed() throws Exception {
        UUID bed = casualtyBeds.get(2).id();
        String first = arrive(3, "Headache").get("id").asString();
        String second = arrive(3, "Headache").get("id").asString();

        mockMvc.perform(post("/casualty/" + first + "/bed").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bedId\": \"" + bed + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/casualty/" + first + "/discharge").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCHARGED"));

        // The occupancy row is released rather than deleted - "who was in bed 4 last Tuesday" is
        // a real question after an infection-control incident - so this proves the index sees a
        // released row as free.
        mockMvc.perform(post("/casualty/" + second + "/bed").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bedId\": \"" + bed + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("leaving without being seen is its own outcome, not a discharge")
    void leftWithoutBeingSeenIsRecordedSeparately() throws Exception {
        String id = arrive(4, "Sprained wrist").get("id").asString();

        // A standard emergency-department quality metric: a department where this rises is a
        // department people are giving up on, and recording it as a discharge would delete the
        // only signal that says so.
        mockMvc.perform(post("/casualty/" + id + "/left").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LEFT_WITHOUT_BEING_SEEN"));

        mockMvc.perform(get("/casualty").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").isEmpty());
    }

    @Test
    @DisplayName("a closed attendance cannot be reopened, discharged twice, or given a bed")
    void aClosedAttendanceStaysClosed() throws Exception {
        String id = arrive(3, "Cough").get("id").asString();
        mockMvc.perform(post("/casualty/" + id + "/discharge").with(as("DOCTOR")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/casualty/" + id + "/discharge").with(as("DOCTOR")))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/casualty/" + id + "/triage").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"triageAcuity\": 1}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/casualty/" + id + "/bed").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bedId\": \"" + casualtyBeds.get(3).id() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a casualty attendance becomes an admission, and its bay is freed")
    void casualtyBecomesAnAdmission() throws Exception {
        UUID bay = casualtyBeds.get(0).id();
        UUID ward = wardBeds.get(0).id();
        JsonNode attendance = arrive(2, "Shortness of breath");
        String attendanceId = attendance.get("id").asString();

        mockMvc.perform(post("/casualty/" + attendanceId + "/bed").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bedId\": \"" + bay + "\"}"))
                .andExpect(status().isOk());

        String admission = mockMvc.perform(post("/admissions").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", attendance.get("patientId").asString(),
                                "patientMrn", attendance.get("patientMrn").asString(),
                                "attendanceId", attendanceId,
                                "bedId", ward,
                                "admittingClinicianId", UUID.randomUUID(),
                                "source", "CASUALTY"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.bedCode").value(wardBeds.get(0).code()))
                .andReturn().getResponse().getContentAsString();

        // The attendance is closed and joined to the admission, so the two halves of one visit
        // are one story.
        mockMvc.perform(get("/casualty/patients/" + attendance.get("patientId").asString())
                        .with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ADMITTED"))
                .andExpect(jsonPath("$[0].admissionId")
                        .value(objectMapper.readTree(admission).get("id").asString()));

        // And the resus bay is free again. Leaving it held would be a bay the department believes
        // it does not have.
        String next = arrive(1, "Collapse").get("id").asString();
        mockMvc.perform(post("/casualty/" + next + "/bed").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bedId\": \"" + bay + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a transfer leaves no window in which the patient is in two beds")
    void transferIsAtomic() throws Exception {
        String admission = admitTo(wardBeds.get(1).id());

        mockMvc.perform(post("/admissions/" + admission + "/transfer").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "toBedId", wardBeds.get(2).id(),
                                "reason", "Side room needed for isolation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bedCode").value(wardBeds.get(2).code()))
                // The move is a fact with a time, kept as its own row: "how many times was this
                // patient moved overnight" is an infection-control question that overwriting a
                // bed code could not answer.
                .andExpect(jsonPath("$.transfers.length()").value(1))
                .andExpect(jsonPath("$.transfers[0].fromBedCode").value(wardBeds.get(1).code()))
                .andExpect(jsonPath("$.transfers[0].reason")
                        .value("Side room needed for isolation"));

        // The bed they left is free, and exactly one bed is occupied by them.
        String board = mockMvc.perform(get("/admissions/beds").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long occupied = 0;
        for (JsonNode bed : objectMapper.readTree(board)) {
            if (bed.get("occupied").asBoolean()
                    && admission.equals(bed.get("occupantId").asString())) {
                occupied++;
            }
        }
        assertThat(occupied).as("in one bed, not two").isEqualTo(1);
    }

    @Test
    @DisplayName("a transfer into an occupied bed is refused, and the patient stays where they were")
    void aRefusedTransferRollsBack() throws Exception {
        String staying = admitTo(wardBeds.get(0).id());
        String moving = admitTo(wardBeds.get(1).id());

        mockMvc.perform(post("/admissions/" + moving + "/transfer").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "toBedId", wardBeds.get(0).id(), "reason", "Closer to the desk"))))
                .andExpect(status().isConflict());

        // The whole transaction rolled back, so the patient is still in the bed they started in
        // rather than in none at all - which is what makes releasing before claiming safe.
        mockMvc.perform(get("/admissions/" + moving).with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bedCode").value(wardBeds.get(1).code()))
                .andExpect(jsonPath("$.transfers").isEmpty());
        mockMvc.perform(get("/admissions/" + staying).with(as("DOCTOR")))
                .andExpect(jsonPath("$.bedCode").value(wardBeds.get(0).code()));
    }

    @Test
    @DisplayName("a transfer to the bed the patient is already in is refused as a bad request")
    void aTransferMustMove() throws Exception {
        String admission = admitTo(wardBeds.get(3).id());

        mockMvc.perform(post("/admissions/" + admission + "/transfer").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "toBedId", wardBeds.get(3).id(), "reason", "No reason"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a ward bed cannot be used for casualty, or the other way round")
    void bedTypesAreEnforced() throws Exception {
        String attendance = arrive(3, "Fall").get("id").asString();

        // Asking for a ward bed on the casualty path: the directory is asked for casualty types
        // and the ward bed is not among them, so it is the caller's mistake and a 400.
        mockMvc.perform(post("/casualty/" + attendance + "/bed").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bedId\": \"" + wardBeds.get(0).id() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the census lists who is in, and a discharge takes them off it")
    void theCensusFollowsTheStay() throws Exception {
        String admission = admitTo(wardBeds.get(2).id());

        mockMvc.perform(get("/admissions").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + admission + "')]").isNotEmpty());

        mockMvc.perform(post("/admissions/" + admission + "/discharge").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\": \"Improved on oral antibiotics.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCHARGED"))
                // At least one day: a patient admitted and discharged the same day has occupied a
                // bed for a day as far as a ward and a bill are concerned.
                .andExpect(jsonPath("$.lengthOfStayDays").value(1));

        mockMvc.perform(get("/admissions").with(as("NURSE")))
                .andExpect(jsonPath("$[?(@.id == '" + admission + "')]").isEmpty());
        mockMvc.perform(post("/admissions/" + admission + "/discharge").with(as("DOCTOR")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("admitting against another patient's attendance is refused")
    void anAttendanceBelongsToOnePatient() throws Exception {
        JsonNode attendance = arrive(3, "Vomiting");

        // Cheap, and it catches the copy-paste that would join two people's records together.
        mockMvc.perform(post("/admissions").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID(),
                                "patientMrn", "MRN-OTHER",
                                "attendanceId", attendance.get("id").asString(),
                                "bedId", wardBeds.get(0).id(),
                                "admittingClinicianId", UUID.randomUUID(),
                                "source", "CASUALTY"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the front desk cannot read the casualty board or the census")
    void receptionIsNotClinical() throws Exception {
        // Deliberately narrower than CLINICAL_READ: a list of who is in casualty with what
        // complaint and how sick they are is a chart in table form.
        mockMvc.perform(get("/casualty").with(as("RECEPTIONIST"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admissions").with(as("RECEPTIONIST"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/casualty").with(as("LAB_TECH"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/casualty")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the bed map shows free and occupied beds together")
    void theBedMapJoinsTheDirectoryToOccupancy() throws Exception {
        String admission = admitTo(wardBeds.get(0).id());

        String body = mockMvc.perform(get("/admissions/beds").with(as("NURSE")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode map = objectMapper.readTree(body);
        assertThat(map.size()).isEqualTo(wardBeds.size());
        long occupied = 0;
        for (JsonNode bed : map) {
            if (bed.get("occupied").asBoolean()) {
                occupied++;
                assertThat(bed.get("occupantType").asString()).isEqualTo("ADMISSION");
            }
        }
        assertThat(occupied).isEqualTo(1);
        assertThat(admission).isNotBlank();
    }

    /** The board's ids, in the order it renders them. */
    private List<String> boardOrder() throws Exception {
        String body = mockMvc.perform(get("/casualty").with(as("NURSE")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> order = new ArrayList<>();
        for (JsonNode row : objectMapper.readTree(body)) {
            order.add(row.get("id").asString());
        }
        return order;
    }

    private String admitTo(UUID bedId) throws Exception {
        String body = mockMvc.perform(post("/admissions").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID(),
                                "patientMrn", "MRN-ADM-" + System.nanoTime() % 1000000,
                                "bedId", bedId,
                                "admittingClinicianId", UUID.randomUUID(),
                                "source", "ELECTIVE"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asString();
    }
}
