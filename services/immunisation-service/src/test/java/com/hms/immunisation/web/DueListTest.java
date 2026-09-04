package com.hms.immunisation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The due list: the schedule, the register and patient-service's cohort, together.
 *
 * <p>Driven against a stub standing in for patient-service, which is what lets the interesting
 * cases be tested at all — the directory refusing, the directory unreachable, and a row arriving
 * with no date of birth. The same shape and the same reasons as
 * {@link CareRelationshipNarrowingTest}, which stubs scheduling-service one door along; no client
 * on this platform has a test of its own, they are proven end to end like this.
 *
 * <p><strong>The case worth reading first</strong> is {@link #thePharmacistIsRefusedTheCohort()}.
 * The due list is deliberately <em>not</em> narrowed per row — a cohort narrowed to the caller's own
 * patients is not a cohort — so what stands in for the narrowing is patient-service's own
 * {@code PATIENT_COHORT_READ}, enforced there against this caller's forwarded token. That is the row
 * that goes red the day somebody widens that constant because a screen needed a name.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DueListTest {

    private static HttpServer patientService;

    /** What the stub answers with, and how. */
    private static final AtomicReference<String> cohortBody = new AtomicReference<>("{}");
    private static final AtomicInteger cohortStatus = new AtomicInteger(200);
    private static final AtomicReference<String> seenAuthorization = new AtomicReference<>();
    private static final AtomicReference<String> seenQuery = new AtomicReference<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startStub() throws IOException {
        patientService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        patientService.createContext("/patients/cohort", exchange -> {
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenQuery.set(exchange.getRequestURI().getQuery());
            int code = cohortStatus.get();
            if (code >= 400) {
                exchange.sendResponseHeaders(code, -1);
                exchange.close();
                return;
            }
            byte[] body = cohortBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        patientService.start();
    }

    @AfterAll
    static void stopStub() {
        patientService.stop(0);
    }

    @DynamicPropertySource
    static void directory(DynamicPropertyRegistry registry) {
        registry.add("hms.patient.base-url",
                () -> "http://127.0.0.1:" + patientService.getAddress().getPort());
    }

    @BeforeEach
    void healthy() {
        cohortStatus.set(200);
    }

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt()
                .jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user")
                        .claim("roles", List.of(roles)))
                .authorities(authorities);
    }

    /** One child in the cohort the stub will answer with. */
    private static UUID aCohortOf(UUID patientId, String mrn, LocalDate bornOn) {
        cohortBody.set(("""
                {"members":[{"id":"%s","mrn":"%s","fullName":"Test Child","dateOfBirth":"%s"}],
                 "returned":1,"total":1,"truncated":false,"note":null}""")
                .formatted(patientId, mrn, bornOn));
        return patientId;
    }

    private JsonNode dueList(LocalDate bornOn, RequestPostProcessor caller) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/immunisations/due")
                        .param("bornFrom", bornOn.toString())
                        .param("bornTo", bornOn.toString())
                        .with(caller))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** The single due row for one antigen, from one child's evaluation. */
    private static JsonNode dueFor(JsonNode child, String antigen, int doseNumber) {
        for (JsonNode row : child.get("due")) {
            if (row.get("antigenCode").asString().equals(antigen)
                    && row.get("doseNumber").asInt() == doseNumber) {
                return row;
            }
        }
        throw new AssertionError("No due row for " + antigen + " dose " + doseNumber
                + " in " + child.get("due"));
    }

    /** A pentavalent dose recorded for this patient on this date. */
    private void recordPentavalent(UUID patientId, LocalDate givenOn) throws Exception {
        String lot = "DUE-" + Math.abs(UUID.randomUUID().getMostSignificantBits());
        mockMvc.perform(post("/vaccines/lots").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productCode", "PENTA",
                                "lotNo", lot,
                                "expiresOn", LocalDate.now().plusYears(1).toString(),
                                "quantity", 5))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/immunisations").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", patientId.toString(),
                                "patientMrn", "MRN-DUE-" + UUID.randomUUID().toString().substring(0, 8),
                                "productCode", "PENTA",
                                "lotNo", lot,
                                "givenOn", givenOn.toString(),
                                "site", "Left thigh"))))
                .andExpect(status().isCreated());
    }

    // ---- the schedule --------------------------------------------------------

    @Test
    @DisplayName("the seeded schedule is readable, and every dose but the first carries an interval")
    void theScheduleIsRows() throws Exception {
        JsonNode schedules = objectMapper.readTree(
                mockMvc.perform(get("/immunisations/schedules").with(as("NURSE")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(schedules.size()).isEqualTo(1);
        JsonNode uip = schedules.get(0);
        assertThat(uip.get("code").asString()).isEqualTo("UIP-2024");
        // The bounds are what stop this schedule answering for an adult, so they are asserted
        // rather than assumed: six years, in days.
        assertThat(uip.get("appliesToAgeDays").asInt()).isEqualTo(2192);
        assertThat(uip.get("doses").size()).isEqualTo(42);

        // chk_interval_iff_not_first, read back through the API. The database enforces it; this
        // asserts the seed actually satisfies it rather than that the constraint exists.
        for (JsonNode dose : uip.get("doses")) {
            boolean first = dose.get("doseNumber").asInt() == 1;
            assertThat(dose.get("minIntervalDays").isNull())
                    .as("dose %s of %s", dose.get("doseNumber"), dose.get("antigenCode"))
                    .isEqualTo(first);
        }
    }

    // ---- the due list --------------------------------------------------------

    @Test
    @DisplayName("a child with nothing recorded is overdue for the six-week visit")
    void nothingRecordedIsOverdue() throws Exception {
        LocalDate bornOn = LocalDate.now().minusDays(300);
        aCohortOf(UUID.randomUUID(), "MRN-DUE-1", bornOn);

        JsonNode child = dueList(bornOn, as("NURSE")).get("children").get(0);

        assertThat(child.get("ageDays").asInt()).isEqualTo(300);
        assertThat(child.get("inSchedule").asBoolean()).isTrue();
        // Due at 42 days with 28 days of grace, and this child is 300 days old.
        JsonNode hib = dueFor(child, "HIB", 1);
        assertThat(hib.get("status").asString()).isEqualTo("OVERDUE");
        assertThat(hib.get("dosesCounted").asInt()).isZero();
        // And the sentence a nurse can check it against.
        assertThat(hib.get("because").asString()).contains("42 days old");
    }

    @Test
    @DisplayName("one pentavalent dose advances five antigen series at once")
    void oneDoseAdvancesFiveSeries() throws Exception {
        LocalDate bornOn = LocalDate.now().minusDays(300);
        UUID patientId = aCohortOf(UUID.randomUUID(), "MRN-DUE-2", bornOn);
        recordPentavalent(patientId, bornOn.plusDays(42));

        JsonNode child = dueList(bornOn, as("NURSE")).get("children").get(0);

        // The whole argument for keying the catalogue on antigens as well as products: one vial,
        // five series, and no code anywhere that knows what PENTA contains.
        for (String antigen : List.of("DIPH", "PERT", "TET", "HIB")) {
            JsonNode row = dueFor(child, antigen, 2);
            assertThat(row.get("dosesCounted").asInt()).as("%s", antigen).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a pentavalent dose at six weeks is not counted as a hepatitis B birth dose")
    void aLateDoseDoesNotFillTheBirthDoseRow() throws Exception {
        LocalDate bornOn = LocalDate.now().minusDays(300);
        UUID patientId = aCohortOf(UUID.randomUUID(), "MRN-DUE-3", bornOn);
        recordPentavalent(patientId, bornOn.plusDays(42));

        JsonNode child = dueList(bornOn, as("NURSE")).get("children").get(0);

        // The defect this assertion was written for: the birth-dose row has a minimum age of zero,
        // so a dose at six weeks satisfied it and the register said the child had a birth dose on a
        // date that proves they did not. The window closed at 14 days; the dose belongs to dose 2.
        JsonNode birthDose = dueFor(child, "HEPB", 1);
        assertThat(birthDose.get("status").asString()).isEqualTo("NO_LONGER_GIVEN");
        // Dose 3 is next, which is what proves the six-week dose landed on dose 2 rather than
        // filling the birth-dose row.
        assertThat(dueFor(child, "HEPB", 3).get("doseNumber").asInt()).isEqualTo(3);
        // And dosesCounted is a fact about the ANTIGEN, not about the row, so every HEPB row
        // reports the one dose this child has -- including the skipped one. This assertion read
        // zero when the count was captured per row as it was built, and that was the defect: a
        // child whose birth-dose window had shut read as having none of the doses they had, because
        // the skipped row was the only row their antigen produced.
        assertThat(birthDose.get("dosesCounted").asInt()).isEqualTo(1);
        assertThat(dueFor(child, "HEPB", 3).get("dosesCounted").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("asAt answers what was due on a past date, because the calculator takes a date")
    void asAtAnswersAPastDate() throws Exception {
        LocalDate bornOn = LocalDate.now().minusDays(300);
        aCohortOf(UUID.randomUUID(), "MRN-DUE-4", bornOn);

        JsonNode past = objectMapper.readTree(mockMvc.perform(get("/immunisations/due")
                        .param("bornFrom", bornOn.toString())
                        .param("bornTo", bornOn.toString())
                        .param("asAt", bornOn.plusDays(30).toString())
                        .with(as("NURSE")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        JsonNode child = past.get("children").get(0);
        assertThat(child.get("ageDays").asInt()).isEqualTo(30);
        // At 30 days old the six-week visit had not arrived, and the register can say so — which is
        // only possible because nothing in the arithmetic reads a clock.
        assertThat(dueFor(child, "HIB", 1).get("status").asString()).isEqualTo("NOT_YET_DUE");
        assertThat(past.get("asAt").asString()).isEqualTo(bornOn.plusDays(30).toString());
    }

    @Test
    @DisplayName("the birth range is forwarded to the directory, not re-invented")
    void theRangeIsForwarded() throws Exception {
        LocalDate bornOn = LocalDate.now().minusDays(100);
        aCohortOf(UUID.randomUUID(), "MRN-DUE-5", bornOn);

        dueList(bornOn, as("DOCTOR"));

        assertThat(seenQuery.get()).contains("bornFrom=" + bornOn).contains("bornTo=" + bornOn);
    }

    @Test
    @DisplayName("the caller's own token is forwarded, not a service credential")
    void forwardsTheCallersToken() throws Exception {
        LocalDate bornOn = LocalDate.now().minusDays(100);
        aCohortOf(UUID.randomUUID(), "MRN-DUE-6", bornOn);
        seenAuthorization.set(null);

        dueList(bornOn, as("DOCTOR"));

        // A service credential able to list a birth cohort would be able to list every child in the
        // district, which is why this service holds none.
        assertThat(seenAuthorization.get()).startsWith("Bearer ");
    }

    @Test
    @DisplayName("truncation is carried through rather than dropped")
    void truncationIsCarriedThrough() throws Exception {
        LocalDate bornOn = LocalDate.now().minusDays(100);
        cohortBody.set(("""
                {"members":[{"id":"%s","mrn":"MRN-DUE-7","fullName":"Test Child","dateOfBirth":"%s"}],
                 "returned":1,"total":4001,"truncated":true,
                 "note":"1 of 4001 children came back; narrow the birth range for the rest."}""")
                .formatted(UUID.randomUUID(), bornOn));

        JsonNode list = dueList(bornOn, as("NURSE"));

        // The children past the cap are exactly the ones nobody telephones, so the list says so.
        assertThat(list.get("truncated").asBoolean()).isTrue();
        assertThat(list.get("total").asLong()).isEqualTo(4001);
        assertThat(list.get("note").asString()).contains("narrow the birth range");
    }

    // ---- the refusals --------------------------------------------------------

    @Test
    @DisplayName("a pharmacist may read the register and may not obtain a birth cohort")
    void thePharmacistIsRefusedTheCohort() throws Exception {
        LocalDate bornOn = LocalDate.now().minusDays(100);
        aCohortOf(UUID.randomUUID(), "MRN-DUE-8", bornOn);
        // What patient-service answers a caller who holds IMMUNISATION_READ but not
        // PATIENT_COHORT_READ. The due list is not narrowed per row, so this gate is what stands in
        // for the narrowing -- and this row goes red the day somebody widens that constant.
        cohortStatus.set(403);

        mockMvc.perform(get("/immunisations/due")
                        .param("bornFrom", bornOn.toString())
                        .param("bornTo", bornOn.toString())
                        .with(as("PHARMACIST")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unreachable directory refuses rather than answering that nobody is due")
    void failsClosedWhenTheDirectoryIsUnreachable() throws Exception {
        LocalDate bornOn = LocalDate.now().minusDays(100);
        cohortStatus.set(503);

        // Fails closed, and the reason is the shape of the wrong answer: an empty due list renders
        // as a screen saying no children need anything, which is a wrong answer that looks like
        // good news -- the kind nobody checks.
        mockMvc.perform(get("/immunisations/due")
                        .param("bornFrom", bornOn.toString())
                        .param("bornTo", bornOn.toString())
                        .with(as("NURSE")))
                .andExpect(status().is5xxServerError());
    }

    @Test
    @DisplayName("a cohort member with no date of birth is refused, never defaulted")
    void aMemberWithNoBirthdayIsRefused() throws Exception {
        LocalDate bornOn = LocalDate.now().minusDays(100);
        cohortBody.set(("""
                {"members":[{"id":"%s","mrn":"MRN-DUE-9","fullName":"Test Child"}],
                 "returned":1,"total":1,"truncated":false}""").formatted(UUID.randomUUID()));

        // Every date in a due list is arithmetic on the birthday, so a missing one would not show
        // up as a gap on a screen -- it would show up as a set of due dates measured from an
        // invented birthday.
        mockMvc.perform(get("/immunisations/due")
                        .param("bornFrom", bornOn.toString())
                        .param("bornTo", bornOn.toString())
                        .with(as("NURSE")))
                .andExpect(status().is5xxServerError());
    }

    @Test
    @DisplayName("a schedule nobody published is a 404, not an empty due list")
    void anUnknownScheduleIsRefused() throws Exception {
        LocalDate bornOn = LocalDate.now().minusDays(100);
        aCohortOf(UUID.randomUUID(), "MRN-DUE-10", bornOn);

        mockMvc.perform(get("/immunisations/due")
                        .param("bornFrom", bornOn.toString())
                        .param("bornTo", bornOn.toString())
                        .param("scheduleCode", "NO-SUCH-SCHEDULE")
                        .with(as("NURSE")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a birth range that runs backwards is refused rather than answered empty")
    void aBackwardsRangeIsRefused() throws Exception {
        mockMvc.perform(get("/immunisations/due")
                        .param("bornFrom", LocalDate.now().toString())
                        .param("bornTo", LocalDate.now().minusDays(30).toString())
                        .with(as("NURSE")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the front desk cannot read a due list at all")
    void theFrontDeskIsRefused() throws Exception {
        mockMvc.perform(get("/immunisations/due")
                        .param("bornFrom", LocalDate.now().minusDays(100).toString())
                        .param("bornTo", LocalDate.now().minusDays(100).toString())
                        .with(as("RECEPTIONIST")))
                .andExpect(status().isForbidden());
    }
}
