package com.hms.scheduling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * The line list, and the disclosure register that has to record it first.
 *
 * <p><strong>The case worth reading first is {@link TheRegisterDecides#anUnreachableRegisterProducesNoFile()}.</strong>
 * Everything else here is ordinary — a gate, a projection, a CSV — but that one row is the whole
 * design: a list of named patients that went out with no record of having gone out is the single
 * outcome this module is built to refuse, so the register is written before a byte of file exists
 * and an unreachable register cancels the request rather than degrading it.
 *
 * <p>Driven against a stub standing in for interop-service, the shape {@code DueListTest} and the
 * three {@code CareRelationshipNarrowingTest}s use: no client on this platform has a test of its
 * own, and stubbing the far side is what makes the interesting cases — refused, unreachable,
 * answering something unexpected — testable at all.
 *
 * <p>Fixtures go in with {@code JdbcTemplate} and pin themselves to one historical day, for the
 * reasons {@link SurveillanceReportIntegrationTest} states: what is under test is a cross-patient
 * read, and driving encounters through the booking and care-team machinery to get rows into one
 * table would make this a test of that machinery instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LineListTest {

    /** Far enough back that no other test in this suite has an encounter there. */
    private static final LocalDate THE_DAY = LocalDate.now().minusDays(240);

    private static HttpServer interopService;

    /** What the stub answers, how, and what it was asked. */
    private static final AtomicInteger registerStatus = new AtomicInteger(201);
    private static final AtomicInteger registerCalls = new AtomicInteger();
    private static final AtomicReference<String> seenBody = new AtomicReference<>();
    private static final AtomicReference<String> seenAuthorization = new AtomicReference<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeAll
    static void startStub() throws IOException {
        interopService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        interopService.createContext("/interop/disclosures", exchange -> {
            registerCalls.incrementAndGet();
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int code = registerStatus.get();
            if (code >= 400) {
                exchange.sendResponseHeaders(code, -1);
                exchange.close();
                return;
            }
            // Echoes a patient count, which is what the client reads back so the file can report
            // how many disclosures were recorded for it.
            int patients = seenBody.get().split("\"patientId\"", -1).length - 1;
            byte[] body = ("{\"disclosureIds\":[],\"recipient\":\"stub\",\"patients\":" + patients + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        interopService.start();
    }

    @AfterAll
    static void stopStub() {
        interopService.stop(0);
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("hms.interop.base-url",
                () -> "http://127.0.0.1:" + interopService.getAddress().getPort());
        registry.add("hms.surveillance.authority", () -> "A district authority");
    }

    @BeforeEach
    void healthy() {
        jdbc = new JdbcTemplate(dataSource);
        registerStatus.set(201);
        registerCalls.set(0);
        seenBody.set(null);
        // Each test owns THE_DAY, so it starts by clearing it: the assertions then read as
        // absolute counts rather than as deltas, which is what a notification is.
        jdbc.update("""
                delete from scheduling.encounters
                 where started_at >= ? and started_at < ?
                """, java.sql.Timestamp.from(THE_DAY.atStartOfDay(
                        java.time.ZoneId.of("UTC")).toInstant()),
                java.sql.Timestamp.from(THE_DAY.plusDays(1).atStartOfDay(
                        java.time.ZoneId.of("UTC")).toInstant()));
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

    /** One encounter on THE_DAY carrying one diagnosis, with an MRN a test can recognise. */
    private String diagnose(UUID patientId, String icd10Code) {
        String mrn = "MRN-LL-" + UUID.randomUUID().toString().substring(0, 8);
        UUID encounterId = UUID.randomUUID();
        jdbc.update("""
                insert into scheduling.encounters
                    (id, patient_id, patient_mrn, clinician_id, department_code, encounter_type,
                     started_at, status)
                values (?, ?, ?, ?, 'GEN', 'OUTPATIENT', ?, 'CLOSED')
                """, encounterId, patientId, mrn, UUID.randomUUID(),
                java.sql.Timestamp.from(THE_DAY.atTime(10, 0)
                        .atZone(java.time.ZoneId.of("UTC")).toInstant()));
        jdbc.update("""
                insert into scheduling.diagnoses
                    (id, encounter_id, icd10_code, description, category, recorded_by)
                values (gen_random_uuid(), ?, ?, 'Recorded by a test', 'PRIMARY', 'test')
                """, encounterId, icd10Code);
        return mrn;
    }

    private JsonNode preview(String... roles) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/surveillance/notifiable/line-list")
                        .param("from", THE_DAY.toString())
                        .param("to", THE_DAY.toString())
                        .with(as(roles.length == 0 ? new String[] {"ADMIN"} : roles)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    // ---- the preview ---------------------------------------------------------

    @Nested
    @DisplayName("the preview")
    class ThePreview {

        @Test
        @DisplayName("names the patients behind the counts")
        void itNamesThem() throws Exception {
            String mrn = diagnose(UUID.randomUUID(), "B05");

            JsonNode list = preview();

            assertThat(list.get("patients").asInt()).isEqualTo(1);
            assertThat(list.get("cases").size()).isEqualTo(1);
            JsonNode row = list.get("cases").get(0);
            assertThat(row.get("patientMrn").asString()).isEqualTo(mrn);
            assertThat(row.get("icd10Code").asString()).isEqualTo("B05");
            assertThat(row.get("conditionName").asString()).isEqualTo("Measles");
            assertThat(row.get("notifyWithinHours").asInt()).isEqualTo(24);
            assertThat(row.get("diagnosedOn").asString()).isEqualTo(THE_DAY.toString());
        }

        @Test
        @DisplayName("registers nothing, and says so rather than leaving a screen to encode the rule")
        void lookingIsNotNotifying() throws Exception {
            diagnose(UUID.randomUUID(), "B05");

            JsonNode list = preview();

            // The distinction the platform draws everywhere: reading a record inside the hospital
            // is audited, and handing one to somebody outside is registered. An administrator
            // looking at who is on this fortnight's return has notified nobody, and a disclosure
            // row for it would fill the register -- the thing a patient reads to find out who has
            // seen their record -- with rows about a screen being opened.
            assertThat(registerCalls.get()).isZero();
            assertThat(list.get("registered").asBoolean()).isFalse();
            assertThat(list.get("note").asString()).contains("not notified");
        }

        @Test
        @DisplayName("counts patients and not rows, so two conditions for one person is one patient")
        void patientsAreDistinct() throws Exception {
            UUID theSamePerson = UUID.randomUUID();
            diagnose(theSamePerson, "B05");
            diagnose(theSamePerson, "A00");

            JsonNode list = preview();

            assertThat(list.get("cases").size()).isEqualTo(2);
            assertThat(list.get("patients").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("carries the recipient, so a reader knows who this would go to")
        void itNamesTheAuthority() throws Exception {
            diagnose(UUID.randomUUID(), "B05");

            assertThat(preview().get("recipient").asString()).isEqualTo("A district authority");
        }
    }

    // ---- the register decides -------------------------------------------------

    @Nested
    @DisplayName("the register decides whether a file exists")
    class TheRegisterDecides {

        @Test
        @DisplayName("one disclosure is registered per distinct patient, before the file is produced")
        void oneRowPerPatient() throws Exception {
            UUID twice = UUID.randomUUID();
            diagnose(twice, "B05");
            diagnose(twice, "A00");
            diagnose(UUID.randomUUID(), "B05");

            String csv = mockMvc.perform(get("/surveillance/notifiable/line-list.csv")
                            .accept("text/csv")
                            .param("from", THE_DAY.toString())
                            .param("to", THE_DAY.toString())
                            .with(as("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Disclosures-Registered", "2"))
                    .andReturn().getResponse().getContentAsString();

            // One register call carrying two subjects, not three and not one. disclosures.patient_id
            // is NOT NULL and its index is what answers a patient asking who has seen their record:
            // a single run-level row would need a fabricated patient id and would be invisible to
            // every patient on the list.
            assertThat(registerCalls.get()).isEqualTo(1);
            assertThat(seenBody.get().split("\"patientId\"", -1).length - 1).isEqualTo(2);
            // And the patient with two conditions is registered once, carrying a count of two.
            assertThat(seenBody.get()).contains("\"rowCount\":2");
            assertThat(csv.lines().count()).isEqualTo(4);
        }

        @Test
        @DisplayName("an unreachable register produces no file, and answers 503 rather than 500")
        void anUnreachableRegisterProducesNoFile() throws Exception {
            diagnose(UUID.randomUUID(), "B05");
            // A status the client cannot read as a refusal, standing in for interop being down.
            registerStatus.set(500);

            String body = mockMvc.perform(get("/surveillance/notifiable/line-list.csv")
                            .accept("text/csv", "application/json")
                            .param("from", THE_DAY.toString())
                            .param("to", THE_DAY.toString())
                            .with(as("ADMIN")))
                    // 503 and not 500: the request was refused whole, nothing partial was left
                    // behind, and the same request works once the register is back. An operator
                    // told "try again" retries; an operator told the platform is broken sends the
                    // list another way.
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString();

            // No file. Not an empty one, not a partial one -- the CSV is produced after the
            // register write and there is nothing here but the refusal.
            assertThat(body).doesNotContain("patientMrn");
            assertThat(body).contains("could not record");
        }

        @Test
        @DisplayName("a register that refuses the caller's own token produces no file either")
        void aRefusedTokenProducesNoFile() throws Exception {
            diagnose(UUID.randomUUID(), "B05");
            // The administrator gate is enforced twice, once here and once there, because this
            // service forwards the caller's token and mints no credential of its own. A service
            // credential able to write disclosures unattended could fabricate the register that is
            // supposed to hold this hospital to account.
            registerStatus.set(403);

            String body = mockMvc.perform(get("/surveillance/notifiable/line-list.csv")
                            .accept("text/csv", "application/json")
                            .param("from", THE_DAY.toString())
                            .param("to", THE_DAY.toString())
                            .with(as("ADMIN")))
                    .andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("patientMrn");
        }

        @Test
        @DisplayName("the caller's own token is what goes to the register")
        void theTokenIsForwarded() throws Exception {
            diagnose(UUID.randomUUID(), "B05");

            mockMvc.perform(get("/surveillance/notifiable/line-list.csv")
                            .accept("text/csv")
                            .param("from", THE_DAY.toString())
                            .param("to", THE_DAY.toString())
                            .with(as("ADMIN")))
                    .andExpect(status().isOk());

            assertThat(seenAuthorization.get()).startsWith("Bearer ");
        }

        @Test
        @DisplayName("a period with no cases registers nothing, because there is nobody to record")
        void anEmptyPeriodRegistersNothing() throws Exception {
            mockMvc.perform(get("/surveillance/notifiable/line-list.csv")
                            .accept("text/csv")
                            .param("from", THE_DAY.toString())
                            .param("to", THE_DAY.toString())
                            .with(as("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Disclosures-Registered", "0"));

            // A disclosure row for an empty file would be a record of a notification that named
            // nobody, sitting in the accounting some patient reads.
            assertThat(registerCalls.get()).isZero();
        }
    }

    // ---- the file ------------------------------------------------------------

    @Nested
    @DisplayName("the file")
    class TheFile {

        @Test
        @DisplayName("names its period and carries an MRN rather than an internal id")
        void theFileIsNamedAndUsable() throws Exception {
            String mrn = diagnose(UUID.randomUUID(), "A00");

            String csv = mockMvc.perform(get("/surveillance/notifiable/line-list.csv")
                            .accept("text/csv")
                            .param("from", THE_DAY.toString())
                            .param("to", THE_DAY.toString())
                            .with(as("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"notifiable-line-list-%s-to-%s.csv\""
                                    .formatted(THE_DAY, THE_DAY)))
                    .andReturn().getResponse().getContentAsString();

            assertThat(csv).startsWith("patientMrn,icd10Code,condition,diagnosedOn,notifyWithinHours");
            assertThat(csv).contains(mrn + ",A00,Cholera,%s,24".formatted(THE_DAY));
            // The authority has to be able to ask the hospital about a case, and an internal UUID
            // is a number nobody outside can use. The id is on the JSON so a screen can link to a
            // chart, and off the file.
            assertThat(csv).doesNotContain("patientId");
        }
    }

    // ---- the gate ------------------------------------------------------------

    @Nested
    @DisplayName("the gate")
    class TheGate {

        @Test
        @DisplayName("the epidemiologist reads the counts and is refused the names")
        void theEpidemiologistGetsCountsAndNotNames() throws Exception {
            diagnose(UUID.randomUUID(), "B05");

            // 200 on the aggregate.
            mockMvc.perform(get("/surveillance/notifiable")
                            .param("from", THE_DAY.toString())
                            .param("to", THE_DAY.toString())
                            .with(as("EPIDEMIOLOGIST")))
                    .andExpect(status().isOk());

            // 403 on the names, both ways in. This is the row that goes red the day somebody adds
            // the role to this constant because a screen needed a name -- and it matters more than
            // it looks, because EPIDEMIOLOGIST sits outside the care-team narrowing (isNarrowed()
            // is allow-list shaped and names DOCTOR and NURSE). A role outside the narrowing is
            // harmless exactly as long as it holds no per-patient surface.
            mockMvc.perform(get("/surveillance/notifiable/line-list")
                            .param("from", THE_DAY.toString())
                            .param("to", THE_DAY.toString())
                            .with(as("EPIDEMIOLOGIST")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/surveillance/notifiable/line-list.csv")
                            .accept("text/csv", "application/json")
                            .param("from", THE_DAY.toString())
                            .param("to", THE_DAY.toString())
                            .with(as("EPIDEMIOLOGIST")))
                    .andExpect(status().isForbidden());
            assertThat(registerCalls.get()).isZero();
        }

        @Test
        @DisplayName("nobody else on the platform gets the names, clinician or otherwise")
        void nobodyElseGetsTheNames() throws Exception {
            diagnose(UUID.randomUUID(), "B05");

            // The doctor who diagnosed a case already knows about it; compiling a list of every
            // named case in a district is a different act, and it is one person's.
            for (String role : List.of("DOCTOR", "NURSE", "RECEPTIONIST", "LAB_TECH", "CASHIER",
                    "PHARMACIST", "PATHOLOGIST")) {
                mockMvc.perform(get("/surveillance/notifiable/line-list")
                                .param("from", THE_DAY.toString())
                                .param("to", THE_DAY.toString())
                                .with(as(role)))
                        .andExpect(status().isForbidden());
            }
            assertThat(registerCalls.get()).isZero();
        }

        @Test
        @DisplayName("a period that runs backwards is refused before anything is registered")
        void aBackwardsPeriodRegistersNothing() throws Exception {
            mockMvc.perform(get("/surveillance/notifiable/line-list.csv")
                            .accept("text/csv", "application/json")
                            .param("from", THE_DAY.toString())
                            .param("to", THE_DAY.minusDays(7).toString())
                            .with(as("ADMIN")))
                    .andExpect(status().isBadRequest());

            assertThat(registerCalls.get()).isZero();
        }
    }
}
