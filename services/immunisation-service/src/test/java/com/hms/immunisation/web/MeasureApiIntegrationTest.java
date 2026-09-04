package com.hms.immunisation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
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
 * The quality measure, and the property the whole design exists for.
 *
 * <p>{@link #aSecondMeasureIsRows()} is the test this module was shaped around: a second coverage
 * measure inserted at runtime by SQL computes a correct rate with no restart and no code change,
 * and a {@code kind} the CHECK does not name is refused by the database rather than published as a
 * percentage of nothing. Everything else here supports that claim or bounds it.
 *
 * <p>Driven against a stub standing in for patient-service, like {@link DueListTest}: this service
 * holds no date of birth, so a measure over a birth cohort cannot be computed without one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeasureApiIntegrationTest {

    /** A cohort of children whose second birthday falls in the period under test. */
    private static final LocalDate BORN_ON = LocalDate.now().minusDays(800);
    private static final LocalDate PERIOD_FROM = BORN_ON.plusDays(730).minusDays(5);
    private static final LocalDate PERIOD_TO = BORN_ON.plusDays(730).plusDays(5);

    private static HttpServer patientService;
    private static final AtomicReference<String> cohortBody = new AtomicReference<>("{}");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @BeforeAll
    static void startStub() throws IOException {
        patientService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Serves BOTH cohort paths. The measure reads the names-free one -- a rate needs a
        // birthday and a key to join on, not a name -- and the stub answers the same body for
        // either, because what this test is about is the arithmetic rather than the shape of the
        // disclosure. Which path the measure actually calls is asserted by the abuse suite, where
        // an epidemiologist holds one role and not the other.
        patientService.createContext("/patients/cohort", exchange -> {
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

    private static void cohortOf(UUID... patientIds) {
        StringBuilder members = new StringBuilder();
        for (int i = 0; i < patientIds.length; i++) {
            if (i > 0) {
                members.append(',');
            }
            members.append(("{\"id\":\"%s\",\"mrn\":\"MRN-MEA-%d\",\"fullName\":\"Test Child\","
                    + "\"dateOfBirth\":\"%s\"}").formatted(patientIds[i], i, BORN_ON));
        }
        cohortBody.set(("{\"members\":[%s],\"returned\":%d,\"total\":%d,\"truncated\":false,"
                + "\"note\":null}").formatted(members, patientIds.length, patientIds.length));
    }

    /** Records one dose of one product on one date, receiving a lot for it first. */
    private void record(UUID patientId, String productCode, LocalDate givenOn) throws Exception {
        String lot = "MEA-" + UUID.randomUUID().toString().substring(0, 10);
        mockMvc.perform(post("/vaccines/lots").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productCode", productCode, "lotNo", lot,
                                "expiresOn", LocalDate.now().plusYears(2).toString(),
                                "quantity", 5))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/immunisations").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", patientId.toString(),
                                "patientMrn", "MRN-MEA-" + UUID.randomUUID().toString().substring(0, 6),
                                "productCode", productCode, "lotNo", lot,
                                "givenOn", givenOn.toString(), "site", "Left thigh"))))
                .andExpect(status().isCreated());
    }

    /**
     * Everything CIS-2 asks for, given on the schedule.
     *
     * <p>Nine records rather than eight antigens, because the composite is satisfied through
     * combination products: three pentavalent doses carry diphtheria, pertussis, tetanus, hepatitis
     * B and Hib; a DPT booster brings the first three to four; four oral polio doses and one
     * measles-rubella finish it. That is the point of keying the catalogue on antigens as well as
     * products, exercised.
     */
    private void fullyVaccinate(UUID patientId) throws Exception {
        record(patientId, "OPV", BORN_ON);
        record(patientId, "PENTA", BORN_ON.plusDays(42));
        record(patientId, "OPV", BORN_ON.plusDays(42));
        record(patientId, "PENTA", BORN_ON.plusDays(70));
        record(patientId, "OPV", BORN_ON.plusDays(70));
        record(patientId, "PENTA", BORN_ON.plusDays(98));
        record(patientId, "OPV", BORN_ON.plusDays(98));
        record(patientId, "MR", BORN_ON.plusDays(270));
        record(patientId, "DPT_B", BORN_ON.plusDays(480));
    }

    private JsonNode rate(String code, String... roles) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/measures/" + code + "/rate")
                        .param("periodFrom", PERIOD_FROM.toString())
                        .param("periodTo", PERIOD_TO.toString())
                        .with(as(roles.length == 0 ? new String[] {"EPIDEMIOLOGIST"} : roles)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    // ---- the definition ------------------------------------------------------

    @Test
    @DisplayName("a measure says who publishes it, which version, and what its populations are")
    void theMeasureIsSelfDescribing() throws Exception {
        JsonNode measures = objectMapper.readTree(
                mockMvc.perform(get("/measures").with(as("NURSE")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        JsonNode cis2 = null;
        for (JsonNode measure : measures) {
            if (measure.get("code").asString().equals("CIS-2")) {
                cis2 = measure;
            }
        }
        assertThat(cis2).isNotNull();
        assertThat(cis2.get("kind").asString()).isEqualTo("ANTIGEN_COVERAGE_BY_AGE");
        assertThat(cis2.get("byAgeDays").asInt()).isEqualTo(730);
        // A rate published without saying which specification produced it is a number nobody
        // downstream can check, and this one goes into a return somebody signs.
        assertThat(cis2.get("steward").asString()).isNotBlank();
        assertThat(cis2.get("specificationVersion").asString()).isEqualTo("2024.1");
        // The three populations in the specification's own words. The exclusion sentence is the one
        // worth asserting on: it is where the refusal decision is written down.
        assertThat(cis2.get("denominatorExclusion").asString())
                .contains("medical contraindication")
                .contains("refusal is NOT an exclusion");
        assertThat(cis2.get("antigens").size()).isEqualTo(8);
    }

    // ---- the rate ------------------------------------------------------------

    @Test
    @DisplayName("a fully vaccinated child scores, and the rate names its own arithmetic")
    void aRateIsComputed() throws Exception {
        UUID patientId = UUID.randomUUID();
        cohortOf(patientId);
        fullyVaccinate(patientId);

        JsonNode rate = rate("CIS-2");

        assertThat(rate.get("initialPopulation").asInt()).isEqualTo(1);
        assertThat(rate.get("denominator").asInt()).isEqualTo(1);
        assertThat(rate.get("numerator").asInt()).isEqualTo(1);
        assertThat(rate.get("rate").asDouble()).isEqualTo(100.0);
        // The birth range the period implies, echoed so the arithmetic can be checked rather than
        // trusted: a period bounds birthdays, and the cohort is the births that produce them.
        assertThat(rate.get("bornFrom").asString()).isEqualTo(PERIOD_FROM.minusDays(730).toString());
        assertThat(rate.get("bornTo").asString()).isEqualTo(PERIOD_TO.minusDays(730).toString());
        // Not cached, so the stamp is how a reader tells a legitimately different answer from an
        // error: a dose entered from a card this morning correctly changes last quarter's rate.
        assertThat(rate.get("computedAt").isNull()).isFalse();
        assertThat(rate.get("specificationVersion").asString()).isEqualTo("2024.1");
    }

    @Test
    @DisplayName("a child short of one antigen scores nothing, because the composite is ANDed")
    void aCompositeIsAllOrNothing() throws Exception {
        UUID patientId = UUID.randomUUID();
        cohortOf(patientId);
        // Everything except the measles-rubella dose. A child protected against seven of eight
        // things is not a covered child, which is what makes this one rate rather than eight.
        record(patientId, "OPV", BORN_ON);
        record(patientId, "PENTA", BORN_ON.plusDays(42));
        record(patientId, "OPV", BORN_ON.plusDays(42));
        record(patientId, "PENTA", BORN_ON.plusDays(70));
        record(patientId, "OPV", BORN_ON.plusDays(70));
        record(patientId, "PENTA", BORN_ON.plusDays(98));
        record(patientId, "OPV", BORN_ON.plusDays(98));
        record(patientId, "DPT_B", BORN_ON.plusDays(480));

        JsonNode rate = rate("CIS-2");

        assertThat(rate.get("denominator").asInt()).isEqualTo(1);
        assertThat(rate.get("numerator").asInt()).isZero();
    }

    @Test
    @DisplayName("nobody reaching their birthday in the period is a null rate, not zero per cent")
    void anEmptyCohortIsANullRate() throws Exception {
        cohortOf();

        JsonNode rate = rate("CIS-2");

        assertThat(rate.get("initialPopulation").asInt()).isZero();
        assertThat(rate.get("rate").isNull()).isTrue();
    }

    @Test
    @DisplayName("the rate carries no patient identifier of any kind")
    void theRateCarriesNoIdentifier() throws Exception {
        UUID patientId = UUID.randomUUID();
        cohortOf(patientId);
        fullyVaccinate(patientId);

        JsonNode rate = rate("CIS-2");

        // Asserted on the property names rather than by grepping the body, so a field added later
        // has to pass this test rather than merely avoid matching a substring. This is what lets
        // EPIDEMIOLOGIST hold the endpoint while holding nothing per-patient.
        assertThat(rate.propertyNames()).doesNotContain("mrn", "patientId", "patients", "children",
                "subjects", "fullName", "dateOfBirth");
    }

    // ---- the property the design exists for ---------------------------------

    @Test
    @DisplayName("a second measure is rows: inserted at runtime, computed with no code change")
    void aSecondMeasureIsRows() throws Exception {
        UUID patientId = UUID.randomUUID();
        cohortOf(patientId);
        // One measles dose and nothing else. Under CIS-2 this child fails; under a measure that
        // asks only about measles they pass -- and no code knows the difference.
        record(patientId, "MR", BORN_ON.plusDays(270));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String code = "MEAS-1-" + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("""
                insert into immunisation.quality_measures
                    (id, code, name, kind, by_age_days, steward, specification_version,
                     initial_population, denominator, denominator_exclusion, numerator,
                     counts_estimated_dates)
                values (gen_random_uuid(), ?, 'Measles coverage by age two',
                        'ANTIGEN_COVERAGE_BY_AGE', 730, 'Inserted by a test', '1.0',
                        'Children who reached their second birthday in the period.',
                        'The initial population.',
                        'A live medical contraindication for measles.',
                        'One counted dose of measles vaccine by the second birthday.', false)
                """, code);
        jdbc.update("""
                insert into immunisation.quality_measure_antigens
                    (id, measure_code, antigen_code, doses_required)
                values (gen_random_uuid(), ?, 'MEAS', 1)
                """, code);

        // No restart, no deployment, no new class. The kind is code and everything else is rows,
        // and this is the assertion that says so.
        JsonNode second = rate(code);
        assertThat(second.get("numerator").asInt()).isEqualTo(1);
        assertThat(second.get("rate").asDouble()).isEqualTo(100.0);
        assertThat(second.get("specificationVersion").asString()).isEqualTo("1.0");

        // And the same child, same doses, under the composite: nought. Two measures, one register,
        // one definition of a counted dose.
        assertThat(rate("CIS-2").get("numerator").asInt()).isZero();
    }

    @Test
    @DisplayName("a kind with no calculator behind it cannot be inserted at all")
    void aKindWithNoCalculatorIsRefused() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // The other half of the same design. Making the kind configuration too would be the NEWS2
        // failure with the sign reversed: a deployment able to add a kind with nothing behind it
        // could publish a percentage rendered from two zeroes -- an answer with nothing behind it,
        // which is worse than a wrong one because a wrong one can be checked.
        assertThatThrownBy(() -> jdbc.update("""
                insert into immunisation.quality_measures
                    (id, code, name, kind, by_age_days, steward, specification_version,
                     initial_population, denominator, denominator_exclusion, numerator)
                values (gen_random_uuid(), 'INVENTED', 'Something else', 'TIMELINESS_BY_AGE', 730,
                        'Nobody', '1.0', 'x', 'y', 'z', 'w')
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_measure_kind");
    }

    // ---- the gates -----------------------------------------------------------

    @Test
    @DisplayName("an epidemiologist reads the rate and cannot read one child's register")
    void theEpidemiologistHoldsAggregatesOnly() throws Exception {
        cohortOf(UUID.randomUUID());

        mockMvc.perform(get("/measures/CIS-2/rate")
                        .param("periodFrom", PERIOD_FROM.toString())
                        .param("periodTo", PERIOD_TO.toString())
                        .with(as("EPIDEMIOLOGIST")))
                .andExpect(status().isOk());

        // The row that goes red the day somebody adds this role to a clinical constant because a
        // screen needed a name. The role sits outside the care-relationship narrowing by a check
        // nobody edited, and that is only safe while it holds no per-patient endpoint.
        mockMvc.perform(get("/immunisations/patients/" + UUID.randomUUID())
                        .with(as("EPIDEMIOLOGIST")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/immunisations/due")
                        .param("bornFrom", BORN_ON.toString())
                        .param("bornTo", BORN_ON.toString())
                        .with(as("EPIDEMIOLOGIST")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the ward may read its own coverage; the billing desk and the bench may not")
    void theRateIsGatedToThePeopleItIsAbout() throws Exception {
        cohortOf(UUID.randomUUID());

        // A clinic that cannot see its own coverage cannot improve it.
        rate("CIS-2", "NURSE");
        rate("CIS-2", "DOCTOR");

        for (String role : List.of("CASHIER", "LAB_TECH", "PHARMACIST", "RECEPTIONIST")) {
            mockMvc.perform(get("/measures/CIS-2/rate")
                            .param("periodFrom", PERIOD_FROM.toString())
                            .param("periodTo", PERIOD_TO.toString())
                            .with(as(role)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("a measure nobody published is a 404, and a backwards period a 400")
    void theRefusals() throws Exception {
        mockMvc.perform(get("/measures/NO-SUCH-MEASURE/rate")
                        .param("periodFrom", PERIOD_FROM.toString())
                        .param("periodTo", PERIOD_TO.toString())
                        .with(as("EPIDEMIOLOGIST")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/measures/CIS-2/rate")
                        .param("periodFrom", PERIOD_TO.toString())
                        .param("periodTo", PERIOD_FROM.toString())
                        .with(as("EPIDEMIOLOGIST")))
                .andExpect(status().isBadRequest());
    }
}
