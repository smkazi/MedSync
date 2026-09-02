package com.hms.pharmacy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.pharmacy.client.AllergyClient;
import java.time.Instant;
import java.time.LocalDate;
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
 * The medication loop against a real database.
 *
 * <p>What is worth proving here rather than in the pure tests is everything the database decides:
 * that stock cannot go negative when two pharmacists reach for the last box at once, that one dose
 * cannot be recorded twice, and that a refused prescription leaves nothing behind. The rules
 * themselves — which allergy blocks what, which pairing refuses — are tested without any of this in
 * {@code SafetyCheckerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PharmacyApiIntegrationTest {

    /**
     * The allergy list, stubbed.
     *
     * <p>It reaches patient-service over HTTP, which is not running here, and the client fails
     * closed — so without this every prescription would be refused rather than exercising the
     * checks. The real cross-service call is covered by the journey in {@code tests/api}.
     */
    @MockitoBean
    private AllergyClient allergyClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID patientId;
    private String mrn;

    @BeforeEach
    void noAllergiesByDefault() {
        patientId = UUID.randomUUID();
        mrn = "MRN-RX-" + Math.abs(System.nanoTime() % 1_000_000);
        when(allergyClient.forPatient(any(UUID.class), nullable(String.class)))
                .thenReturn(List.of());
    }

    private void allergicTo(String substance, String severity) {
        when(allergyClient.forPatient(any(UUID.class), nullable(String.class)))
                .thenReturn(List.of(new AllergyClient.Allergy(substance, "anaphylaxis", severity,
                        "LIFE_THREATENING".equals(severity))));
    }

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user"))
                .authorities(authorities);
    }

    private Map<String, Object> line(String drugCode, int quantity) {
        return Map.of("drugCode", drugCode, "dose", "1 tablet", "frequency", "twice daily",
                "durationDays", 5, "quantity", quantity);
    }

    private JsonNode prescribe(List<Map<String, Object>> items, String overrideReason,
                               int expectedStatus) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("patientId", patientId);
        body.put("patientMrn", mrn);
        body.put("items", items);
        if (overrideReason != null) {
            body.put("overrideReason", overrideReason);
        }
        String response = mockMvc.perform(post("/prescriptions").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode receive(String drugCode, String batchNo, LocalDate expiry, int quantity)
            throws Exception {
        String response = mockMvc.perform(post("/pharmacy/stock").with(as("PHARMACIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "drugCode", drugCode, "batchNo", batchNo,
                                "expiresOn", expiry.toString(), "quantity", quantity))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    /**
     * A formulary entry nobody else uses.
     *
     * <p>For the tests whose subject is stock: batches are shared per drug code, and a test that
     * asserts which batch was chosen has to own all of them.
     */
    private String ownDrug(String ingredient) throws Exception {
        String code = "T" + UUID.randomUUID().toString().substring(0, 7)
                .toUpperCase(java.util.Locale.ROOT);
        mockMvc.perform(post("/pharmacy/formulary").with(as("PHARMACIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code, "name", "Test " + code, "form", "TABLET",
                                "strength", "100 mg", "unit", "tablet",
                                "ingredients", List.of(ingredient)))))
                .andExpect(status().isCreated());
        return code;
    }

    private static String batchNo() {
        return "B-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
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

    // ---- prescribing ---------------------------------------------------------

    @Test
    @DisplayName("a clear prescription is written, and the drug name is snapshotted onto the line")
    void aClearPrescriptionIsWritten() throws Exception {
        JsonNode written = prescribe(List.of(line("PARA500", 10)), null, 201);

        assertThat(written.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(written.get("items").get(0).get("drugName").asString())
                .as("the name as it was when this was written, not a join")
                .isEqualTo("Paracetamol 500 mg tablet");
        assertThat(written.get("items").get(0).get("outstanding").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("a life-threatening allergy refuses, names the substance, and writes nothing")
    void aCriticalAllergyRefuses() throws Exception {
        allergicTo("Penicillin", "LIFE_THREATENING");

        JsonNode refusal = prescribe(List.of(line("AMOX500", 21)), null, 409);
        assertThat(refusal.get("detail").asString())
                .contains("Penicillin")
                .contains("PENICILLIN")
                .contains("cannot be written");

        // Nothing behind it. A service that saved first and checked afterwards would have a moment
        // in which an unsafe order existed and was dispensable.
        mockMvc.perform(get("/prescriptions?patientId=" + patientId).with(as("PHARMACIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("a severe allergy cannot be overridden however good the reason")
    void aSevereAllergyIsNotOverridable() throws Exception {
        allergicTo("Penicillin", "SEVERE");
        prescribe(List.of(line("AMOX500", 21)), "Consultant reviewed, benefit outweighs risk", 409);
    }

    @Test
    @DisplayName("a mild allergy needs a reason, and the reason is kept where the pharmacist reads it")
    void aMildAllergyIsOverridableWithAReason() throws Exception {
        allergicTo("Ibuprofen", "MILD");

        JsonNode asked = prescribe(List.of(line("IBU400", 20)), null, 409);
        assertThat(asked.get("detail").asString()).contains("override reason");

        JsonNode written = prescribe(List.of(line("IBU400", 20)),
                "Rash only, previously tolerated at this dose", 201);
        assertThat(written.get("overrideReason").asString())
                .isEqualTo("Rash only, previously tolerated at this dose");
    }

    @Test
    @DisplayName("a contraindicated pairing refuses even with a reason")
    void contraindicatedPairingRefuses() throws Exception {
        JsonNode refusal = prescribe(
                List.of(line("CLARITH500", 14), line("SIMVA20", 28)),
                "Patient has taken both before", 409);

        assertThat(refusal.get("detail").asString())
                .contains("contraindicated")
                .contains("Do not co-prescribe");
    }

    @Test
    @DisplayName("a major pairing goes through once somebody says why")
    void aMajorPairingIsOverridable() throws Exception {
        prescribe(List.of(line("WARF5", 28), line("IBU400", 20)), null, 409);
        JsonNode written = prescribe(List.of(line("WARF5", 28), line("IBU400", 20)),
                "Short course, gastroprotection added, INR in 3 days", 201);
        assertThat(written.get("items")).hasSize(2);
    }

    @Test
    @DisplayName("a moderate pairing is reported and does not block")
    void aModeratePairingDoesNotBlock() throws Exception {
        JsonNode written = prescribe(List.of(line("ASPIRIN75", 28), line("IBU400", 20)), null, 201);
        assertThat(written.get("status").asString()).isEqualTo("ACTIVE");

        JsonNode check = objectMapper.readTree(mockMvc.perform(post("/pharmacy/check")
                        .with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("patientId", patientId,
                                "drugCodes", List.of("ASPIRIN75", "IBU400")))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(check.get("outcome").asString()).isEqualTo("CLEAR");
        assertThat(check.get("interactions")).hasSize(1);
    }

    @Test
    @DisplayName("the same medicine twice on one prescription is refused rather than guessed at")
    void duplicateLinesAreRefused() throws Exception {
        JsonNode refusal = prescribe(List.of(line("PARA500", 10), line("PARA500", 20)), null, 400);
        assertThat(refusal.get("detail").asString()).contains("appears twice");
    }

    @Test
    @DisplayName("an unknown drug code is a 404 and a retired one is a 400 that says so")
    void unknownAndRetiredCodesReadDifferently() throws Exception {
        mockMvc.perform(post("/prescriptions").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("patientId", patientId,
                                "patientMrn", mrn, "items", List.of(line("NOSUCH", 1))))))
                .andExpect(status().isNotFound());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/pharmacy/formulary/METFORMIN500").with(as("PHARMACIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk());
        try {
            JsonNode refusal = prescribe(List.of(line("METFORMIN500", 30)), null, 400);
            assertThat(refusal.get("detail").asString()).contains("no longer stocked");
        } finally {
            // Shared configuration, restored: leaving it retired would make every later run of this
            // suite read a different formulary, which is the order-dependence the laboratory suite
            // was caught by.
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .patch("/pharmacy/formulary/METFORMIN500").with(as("PHARMACIST"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"active\": true}"))
                    .andExpect(status().isOk());
        }
    }

    // ---- stock and dispensing ------------------------------------------------

    @Test
    @DisplayName("expired stock is refused at the door")
    void expiredStockIsNotReceived() throws Exception {
        mockMvc.perform(post("/pharmacy/stock").with(as("PHARMACIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "drugCode", "PARA500", "batchNo", batchNo(),
                                "expiresOn", LocalDate.now().minusDays(1).toString(),
                                "quantity", 100))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("dispensing picks the batch that expires first, not the one received first")
    void dispensingIsFirstExpiryFirstOut() throws Exception {
        // Its own formulary entry, so this test owns every batch of it. The first version used the
        // seeded paracetamol and asserted the batch it had just received — which is only the
        // earliest-expiring one until another test in the class receives a shorter-dated box of the
        // same medicine, and then FEFO correctly picks that one and this test reads as a failure.
        // Sharing stock and asserting an absolute choice cannot both be right.
        String drug = ownDrug("FEFOMOL");
        String latest = batchNo();
        String soonest = batchNo();
        receive(drug, latest, LocalDate.now().plusYears(2), 50);
        // Received second and expiring sooner: FIFO would pick the other one and this box would be
        // destroyed while the long-dated one was dispensed.
        receive(drug, soonest, LocalDate.now().plusDays(30), 50);

        JsonNode written = prescribe(List.of(line(drug, 10)), null, 201);
        String itemId = written.get("items").get(0).get("id").asString();

        JsonNode dispensed = objectMapper.readTree(mockMvc.perform(post("/pharmacy/dispenses")
                        .with(as("PHARMACIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prescriptionItemId", itemId, "quantity", 10))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        assertThat(dispensed.get("batchNo").asString()).isEqualTo(soonest);
        assertThat(dispensed.get("outstanding").asInt()).isZero();
    }

    @Test
    @DisplayName("more than was prescribed cannot be handed over")
    void dispensingIsCappedByThePrescription() throws Exception {
        receive("PARA500", batchNo(), LocalDate.now().plusYears(1), 100);
        JsonNode written = prescribe(List.of(line("PARA500", 10)), null, 201);
        String itemId = written.get("items").get(0).get("id").asString();

        mockMvc.perform(post("/pharmacy/dispenses").with(as("PHARMACIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prescriptionItemId", itemId, "quantity", 11))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("two pharmacists reaching for the last box: one gets it, the other is told why")
    void stockCannotGoNegative() throws Exception {
        // The stock analogue of the bed race, and the reason the decrement is one conditional
        // UPDATE: both threads read the same quantity, both subtract, and a read-modify-write would
        // let the second silently restore what the first took.
        String batch = batchNo();
        receive("PARA500", batch, LocalDate.now().plusYears(1), 1);

        int contenders = 6;
        java.util.Queue<String> items = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int index = 0; index < contenders; index++) {
            items.add(prescribe(List.of(line("PARA500", 1)), null, 201)
                    .get("items").get(0).get("id").asString());
        }

        List<Integer> statuses = inParallel(contenders, () -> {
            String itemId = items.poll();
            return mockMvc.perform(post("/pharmacy/dispenses").with(as("PHARMACIST"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "prescriptionItemId", itemId, "quantity", 1,
                                    "batchId", batchIdOf(batch)))))
                    .andReturn().getResponse().getStatus();
        });

        assertThat(statuses.stream().filter(status -> status == 201).count())
                .as("exactly one hand-over succeeds from a batch of one")
                .isEqualTo(1);
        assertThat(statuses.stream().filter(status -> status == 409).count())
                .isEqualTo(contenders - 1L);
    }

    private String batchIdOf(String batchNo) throws Exception {
        JsonNode stock = objectMapper.readTree(
                mockMvc.perform(get("/pharmacy/stock?drugCode=PARA500").with(as("PHARMACIST")))
                        .andReturn().getResponse().getContentAsString());
        for (JsonNode row : stock) {
            if (row.get("batchNo").asString().equals(batchNo)) {
                return row.get("id").asString();
            }
        }
        throw new AssertionError("no batch " + batchNo);
    }

    @Test
    @DisplayName("a named expired batch is refused, even though it is on the shelf")
    void anExpiredBatchCannotBeDispensed() throws Exception {
        // Received while valid, then aged. Written straight to the repository because the API
        // refuses expired stock at the door, and the state being tested is a batch that expired
        // after it arrived — which is what actually happens on a shelf.
        String batch = batchNo();
        receive("PARA500", batch, LocalDate.now().plusDays(1), 20);
        String batchId = batchIdOf(batch);
        expire(batchId);

        JsonNode written = prescribe(List.of(line("PARA500", 5)), null, 201);
        String itemId = written.get("items").get(0).get("id").asString();

        JsonNode refusal = objectMapper.readTree(mockMvc.perform(post("/pharmacy/dispenses")
                        .with(as("PHARMACIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prescriptionItemId", itemId, "quantity", 5,
                                "batchId", batchId))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());
        assertThat(refusal.get("detail").asString()).contains("expired");
    }

    @Autowired
    private com.hms.pharmacy.repo.StockBatchRepository batches;

    private void expire(String batchId) {
        var batch = batches.findById(UUID.fromString(batchId)).orElseThrow();
        // Reflection rather than a setter, deliberately: nothing in the application may back-date
        // an expiry, so the entity has no method for it and the test does not add one to the
        // production API just to be able to set up this state.
        try {
            var field = batch.getClass().getDeclaredField("expiresOn");
            field.setAccessible(true);
            field.set(batch, LocalDate.now().minusDays(1));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
        batches.saveAndFlush(batch);
    }

    // ---- administration ------------------------------------------------------

    @Test
    @DisplayName("the wristband must match, and the refusal tells the nurse which chart is open")
    void theWristbandIsChecked() throws Exception {
        JsonNode written = prescribe(List.of(line("PARA500", 10)), null, 201);
        String itemId = written.get("items").get(0).get("id").asString();

        JsonNode refusal = objectMapper.readTree(mockMvc.perform(post("/emar/administer")
                        .with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prescriptionItemId", itemId,
                                "scheduledFor", Instant.now().toString(),
                                "patientScan", "MRN-SOMEBODY-ELSE",
                                "drugScan", "PARA500"))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());

        assertThat(refusal.get("detail").asString())
                .contains("MRN-SOMEBODY-ELSE")
                .contains(mrn)
                .contains("Do not give");
    }

    @Test
    @DisplayName("the medicine must match too")
    void theLabelIsChecked() throws Exception {
        JsonNode written = prescribe(List.of(line("PARA500", 10)), null, 201);
        String itemId = written.get("items").get(0).get("id").asString();

        mockMvc.perform(post("/emar/administer").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prescriptionItemId", itemId,
                                "scheduledFor", Instant.now().toString(),
                                "patientScan", mrn,
                                "drugScan", "IBU400"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("one dose, one record: the second attempt at the same dose is refused")
    void aDoseCannotBeRecordedTwice() throws Exception {
        JsonNode written = prescribe(List.of(line("PARA500", 10)), null, 201);
        String itemId = written.get("items").get(0).get("id").asString();
        String due = Instant.now().toString();

        mockMvc.perform(post("/emar/administer").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prescriptionItemId", itemId, "scheduledFor", due,
                                "patientScan", mrn, "drugScan", "PARA500"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("GIVEN"));

        JsonNode second = objectMapper.readTree(mockMvc.perform(post("/emar/administer")
                        .with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prescriptionItemId", itemId, "scheduledFor", due,
                                "patientScan", mrn, "drugScan", "PARA500"))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());
        assertThat(second.get("detail").asString()).contains("already been recorded");
    }

    @Test
    @DisplayName("a dose not given is a row with a reason, not a gap")
    void aDoseNotGivenIsRecorded() throws Exception {
        JsonNode written = prescribe(List.of(line("PARA500", 10)), null, 201);
        String itemId = written.get("items").get(0).get("id").asString();

        mockMvc.perform(post("/emar/not-given").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prescriptionItemId", itemId,
                                "scheduledFor", Instant.now().toString(),
                                "status", "REFUSED",
                                "reason", "Patient declined, says it upsets her stomach"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REFUSED"))
                .andExpect(jsonPath("$.administeredAt").doesNotExist());

        // And "given" cannot sneak through the path that skips the scans.
        mockMvc.perform(post("/emar/not-given").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prescriptionItemId", itemId,
                                "scheduledFor", Instant.now().plusSeconds(3600).toString(),
                                "status", "GIVEN", "reason", "gave it"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a dose not given needs a reason before the platform is asked")
    void notGivenNeedsAReason() throws Exception {
        JsonNode written = prescribe(List.of(line("PARA500", 10)), null, 201);
        String itemId = written.get("items").get(0).get("id").asString();

        mockMvc.perform(post("/emar/not-given").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prescriptionItemId", itemId,
                                "scheduledFor", Instant.now().toString(),
                                "status", "OMITTED", "reason", " "))))
                .andExpect(status().isBadRequest());
    }

    // ---- separation of duties ------------------------------------------------

    @Test
    @DisplayName("no role can write an order, dispense it and sign that it was given")
    void theThreeActsBelongToThreeRoles() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("patientId", patientId,
                "patientMrn", mrn, "items", List.of(line("PARA500", 10))));

        // A nurse does not prescribe.
        mockMvc.perform(post("/prescriptions").with(as("NURSE"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        // A pharmacist does not prescribe either — they read the order and fill it.
        mockMvc.perform(post("/prescriptions").with(as("PHARMACIST"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        JsonNode written = prescribe(List.of(line("PARA500", 10)), null, 201);
        String itemId = written.get("items").get(0).get("id").asString();

        // A doctor does not dispense.
        mockMvc.perform(post("/pharmacy/dispenses").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prescriptionItemId", itemId, "quantity", 1))))
                .andExpect(status().isForbidden());

        // And a pharmacist does not give the dose at the bedside.
        mockMvc.perform(post("/emar/administer").with(as("PHARMACIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prescriptionItemId", itemId,
                                "scheduledFor", Instant.now().toString(),
                                "patientScan", mrn, "drugScan", "PARA500"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the laboratory has no business here at all")
    void theLaboratoryIsRefusedEverything() throws Exception {
        mockMvc.perform(get("/prescriptions").with(as("LAB_TECH")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/pharmacy/stock").with(as("LAB_TECH")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/pharmacy/formulary").with(as("RECEPTIONIST")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a prescription cannot be cancelled once part of it has been handed over")
    void cancellingAfterDispensingIsRefused() throws Exception {
        receive("PARA500", batchNo(), LocalDate.now().plusYears(1), 50);
        JsonNode written = prescribe(List.of(line("PARA500", 10)), null, 201);
        String prescriptionId = written.get("id").asString();
        String itemId = written.get("items").get(0).get("id").asString();

        mockMvc.perform(post("/pharmacy/dispenses").with(as("PHARMACIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prescriptionItemId", itemId, "quantity", 5))))
                .andExpect(status().isCreated());

        JsonNode refusal = objectMapper.readTree(
                mockMvc.perform(post("/prescriptions/" + prescriptionId + "/cancel").with(as("DOCTOR")))
                        .andExpect(status().isConflict())
                        .andReturn().getResponse().getContentAsString());
        assertThat(refusal.get("detail").asString()).contains("already been dispensed");
    }

    @Test
    @DisplayName("dispensing everything completes the prescription without anybody setting a flag")
    void completionIsDerivedFromTheNumbers() throws Exception {
        receive("PARA500", batchNo(), LocalDate.now().plusYears(1), 50);
        JsonNode written = prescribe(List.of(line("PARA500", 10)), null, 201);
        String prescriptionId = written.get("id").asString();
        String itemId = written.get("items").get(0).get("id").asString();

        for (int half = 0; half < 2; half++) {
            mockMvc.perform(post("/pharmacy/dispenses").with(as("PHARMACIST"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "prescriptionItemId", itemId, "quantity", 5))))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/prescriptions/" + prescriptionId).with(as("PHARMACIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].outstanding").value(0));
    }
}
