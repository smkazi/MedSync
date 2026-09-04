package com.hms.scheduling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The notifiable-disease return.
 *
 * <p><strong>Fixtures go in with {@code JdbcTemplate}, deliberately.</strong> What is under test is
 * the aggregate — a cross-patient, code-filtered, date-ranged count that must answer without ever
 * selecting an identifier — and the write path that produces diagnoses is covered thoroughly by
 * {@code CareApiIntegrationTest}. Driving encounters through the booking and care-team machinery to
 * get rows into one table would make this file a test of that machinery instead.
 *
 * <p>Every case pins its encounters to <strong>one historical day</strong> and asks for exactly that
 * day. Nothing else in this suite writes encounters into the past — the rest book into the future —
 * so the counts are the fixtures' own and cannot drift when another test runs beside them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SurveillanceReportIntegrationTest {

    /** Far enough back that no other test in this suite has an encounter there. */
    private static final LocalDate THE_DAY = LocalDate.now().minusDays(200);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void jdbc() {
        jdbc = new JdbcTemplate(dataSource);
        // Each test owns THE_DAY, so it starts by clearing it. Cheaper and clearer than a unique
        // day per test: the assertions read as absolute counts rather than as deltas, and an
        // absolute count is what a statutory return is.
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

    /** One encounter on THE_DAY for the given patient, carrying one diagnosis. */
    private void diagnose(UUID patientId, String icd10Code) {
        diagnose(patientId, icd10Code, THE_DAY);
    }

    private void diagnose(UUID patientId, String icd10Code, LocalDate on) {
        UUID encounterId = UUID.randomUUID();
        jdbc.update("""
                insert into scheduling.encounters
                    (id, patient_id, patient_mrn, clinician_id, department_code, encounter_type,
                     started_at, status)
                values (?, ?, ?, ?, 'GEN', 'OUTPATIENT', ?, 'CLOSED')
                """, encounterId, patientId, "MRN-SUR-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(),
                java.sql.Timestamp.from(on.atTime(10, 0)
                        .atZone(java.time.ZoneId.of("UTC")).toInstant()));
        jdbc.update("""
                insert into scheduling.diagnoses
                    (id, encounter_id, icd10_code, description, category, recorded_by)
                values (gen_random_uuid(), ?, ?, 'Recorded by a test', 'PRIMARY', 'test')
                """, encounterId, icd10Code);
    }

    private JsonNode report(String... roles) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/surveillance/notifiable")
                        .param("from", THE_DAY.toString())
                        .param("to", THE_DAY.toString())
                        .with(as(roles.length == 0 ? new String[] {"EPIDEMIOLOGIST"} : roles)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static JsonNode line(JsonNode report, String code) {
        for (JsonNode row : report.get("conditions")) {
            if (row.get("icd10Code").asString().equals(code)) {
                return row;
            }
        }
        throw new AssertionError("no line for " + code + " in " + report.get("conditions"));
    }

    // ---- the configured list -------------------------------------------------

    @Test
    @DisplayName("the reportable conditions are rows, one per code and never a prefix")
    void theListIsRows() throws Exception {
        JsonNode conditions = objectMapper.readTree(
                mockMvc.perform(get("/surveillance/notifiable-conditions").with(as("ADMIN")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(conditions.size()).isGreaterThanOrEqualTo(18);
        // Every code is a whole code. A prefix would widen the return invisibly -- 'A0' sweeps
        // cholera through typhoid into amoebiasis -- and could not be an equality join, so the
        // index this feature added would go unused.
        for (JsonNode condition : conditions) {
            assertThat(condition.get("icd10Code").asString()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(condition.get("notifyWithinHours").asInt()).isPositive();
        }
    }

    // ---- the counts ----------------------------------------------------------

    @Test
    @DisplayName("a case is a patient, not a visit: two encounters for one person count once")
    void casesAreDistinctPatients() throws Exception {
        UUID theSamePerson = UUID.randomUUID();
        diagnose(theSamePerson, "B05");
        diagnose(theSamePerson, "B05");
        diagnose(UUID.randomUUID(), "B05");

        JsonNode measles = line(report(), "B05");

        // An incidence figure that counted the follow-up would report an outbreak made of
        // second appointments.
        assertThat(measles.get("cases").asLong()).isEqualTo(2);
        // Three diagnoses, two people, two cases.
        assertThat(report().get("totalCases").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("every configured condition appears, including the ones with no cases")
    void theZeroesAreOnTheReport() throws Exception {
        diagnose(UUID.randomUUID(), "A00");

        JsonNode report = report();

        // A report that omitted the zeroes would render "no cholera this fortnight" and "cholera is
        // not on our list" identically, and those are very different facts about a district.
        assertThat(line(report, "A00").get("cases").asLong()).isEqualTo(1);
        assertThat(line(report, "A82").get("cases").asLong()).isZero();
        assertThat(report.get("conditions").size()).isGreaterThanOrEqualTo(18);
    }

    @Test
    @DisplayName("a diagnosis nobody has to report is not on the return at all")
    void unlistedCodesAreNotCounted() throws Exception {
        diagnose(UUID.randomUUID(), "J06");
        diagnose(UUID.randomUUID(), "E11.9");

        assertThat(report().get("totalCases").asLong()).isZero();
    }

    @Test
    @DisplayName("the period bounds the encounter, so yesterday's case is not today's")
    void thePeriodBoundsTheEncounter() throws Exception {
        diagnose(UUID.randomUUID(), "B05", THE_DAY.minusDays(1));

        // Half-open and exclusive of the day after, the same boundary the audit report uses -- and
        // resolved in the hospital's zone, which is why scheduling had to join the HMS_ZONE chain
        // before this existed. A notifiable week cut in UTC by an IST hospital puts five and a half
        // hours of every Sunday into the next week's return.
        assertThat(line(report(), "B05").get("cases").asLong()).isZero();
    }

    @Test
    @DisplayName("the report echoes the zone it cut the days in")
    void theReportSaysWhichZone() throws Exception {
        // A statutory boundary. A return whose days were cut somewhere else is a different return,
        // so the answer says where they were cut rather than leaving a reader to assume.
        assertThat(report().get("zone").asString()).isNotBlank();
    }

    // ---- what it does not carry ----------------------------------------------

    @Test
    @DisplayName("the aggregate carries no identifier, because the query never selects one")
    void nothingIdentifiesAnybody() throws Exception {
        diagnose(UUID.randomUUID(), "A36");

        JsonNode report = report();

        // Asserted on property names rather than by grepping the body, so a field somebody adds
        // later has to pass this test rather than merely avoid matching a substring. The stronger
        // half of the guarantee is in the repository: the projection the query returns through has
        // nowhere to put a patient id, so adding one is a compile error rather than a disclosure.
        assertThat(report.propertyNames()).doesNotContain("patientId", "mrn", "patients",
                "fullName", "dateOfBirth", "encounterId");
        for (JsonNode row : report.get("conditions")) {
            assertThat(row.propertyNames()).containsExactlyInAnyOrder("icd10Code", "conditionName",
                    "cases", "notifyWithinHours", "suppressed");
        }
    }

    @Test
    @DisplayName("there is no condition-by-department breakdown, however useful one would be")
    void thereIsNoCrossTab() throws Exception {
        diagnose(UUID.randomUUID(), "A82");

        // Deliberately absent. A rare condition against a small department re-identifies by
        // arithmetic: one case of rabies in a four-bed unit names a patient to anybody who works
        // there, and the whole point of an aggregate is that it does not do that. This row is what
        // makes the absence a decision rather than a gap somebody fills in.
        JsonNode report = report();
        assertThat(report.propertyNames()).doesNotContain("departments", "byDepartment");
        for (JsonNode row : report.get("conditions")) {
            assertThat(row.propertyNames()).doesNotContain("departmentCode", "departments");
        }
    }

    // ---- the download --------------------------------------------------------

    @Test
    @DisplayName("the CSV names its period and is not cached")
    void theCsvIsNamedAndUncached() throws Exception {
        diagnose(UUID.randomUUID(), "B05");

        String csv = mockMvc.perform(get("/surveillance/notifiable.csv")
                        .accept("text/csv")
                        .param("from", THE_DAY.toString())
                        .param("to", THE_DAY.toString())
                        .with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"notifiable-%s-to-%s.csv\"".formatted(THE_DAY, THE_DAY)))
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).startsWith("icd10Code,condition,cases,notifyWithinHours");
        assertThat(csv).contains("B05,Measles,1,24");
        // Not truncated: this report has as many rows as there are configured conditions, so there
        // is no input that makes it large.
        assertThat(csv).doesNotContain("TRUNCATED");
    }

    // ---- the gate ------------------------------------------------------------

    @Test
    @DisplayName("a statutory return is the administrator's and the epidemiologist's, and nobody else's")
    void theReturnIsNarrowlyHeld() throws Exception {
        report("ADMIN");
        report("EPIDEMIOLOGIST");

        // Deliberately not the ward, unlike the coverage measure. A coverage rate is about a
        // clinic's own work; a notifiable-disease return is a statutory filing about a district, and
        // in a small one the list of conditions being watched for is itself a statement about what
        // has been seen. The clinician who diagnosed a case already knows about it.
        for (String role : List.of("DOCTOR", "NURSE", "RECEPTIONIST", "LAB_TECH", "CASHIER")) {
            mockMvc.perform(get("/surveillance/notifiable")
                            .param("from", THE_DAY.toString())
                            .param("to", THE_DAY.toString())
                            .with(as(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/surveillance/notifiable-conditions").with(as(role)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("a period that runs backwards is refused rather than answered empty")
    void aBackwardsPeriodIsRefused() throws Exception {
        mockMvc.perform(get("/surveillance/notifiable")
                        .param("from", THE_DAY.toString())
                        .param("to", THE_DAY.minusDays(7).toString())
                        .with(as("ADMIN")))
                .andExpect(status().isBadRequest());
    }
}
