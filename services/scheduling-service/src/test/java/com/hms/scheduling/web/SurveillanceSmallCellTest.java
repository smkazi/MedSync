package com.hms.scheduling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Small-cell suppression, which ships <strong>off</strong>.
 *
 * <p>Its own class because it needs the threshold set, and the default has to stay zero everywhere
 * else — a suite that ran the whole surveillance surface with suppression on would be testing a
 * configuration nobody ships.
 *
 * <p><strong>Why the default is off.</strong> A statutory return needs exact counts: a district
 * filing "fewer than five" cannot be aggregated upward by whoever receives it, and a threshold
 * applied silently would understate an outbreak. The mechanism exists because the risk is real — a
 * rare condition in a small population is re-identifying by arithmetic — and a deployment
 * publishing these numbers more widely than to its own authority turns it on. The same posture as
 * the ABDM gateway and the imaging store: the decision written down, the mechanism present, and the
 * default honest about which one is in force.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "hms.surveillance.small-cell-threshold=5")
class SurveillanceSmallCellTest {

    private static final LocalDate THE_DAY = LocalDate.now().minusDays(400);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clearTheDay() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from scheduling.encounters where started_at >= ? and started_at < ?",
                java.sql.Timestamp.from(THE_DAY.atStartOfDay(ZoneId.of("UTC")).toInstant()),
                java.sql.Timestamp.from(THE_DAY.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant()));
    }

    private static RequestPostProcessor asEpidemiologist() {
        return jwt()
                .jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user")
                        .claim("roles", List.of("EPIDEMIOLOGIST")))
                .authorities(new SimpleGrantedAuthority("ROLE_EPIDEMIOLOGIST"));
    }

    private void diagnose(String icd10Code, int howManyPatients) {
        for (int i = 0; i < howManyPatients; i++) {
            UUID encounterId = UUID.randomUUID();
            jdbc.update("""
                    insert into scheduling.encounters
                        (id, patient_id, patient_mrn, clinician_id, department_code, encounter_type,
                         started_at, status)
                    values (?, ?, ?, ?, 'GEN', 'OUTPATIENT', ?, 'CLOSED')
                    """, encounterId, UUID.randomUUID(),
                    "MRN-SC-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(),
                    java.sql.Timestamp.from(
                            THE_DAY.atTime(10, 0).atZone(ZoneId.of("UTC")).toInstant()));
            jdbc.update("""
                    insert into scheduling.diagnoses
                        (id, encounter_id, icd10_code, description, category, recorded_by)
                    values (gen_random_uuid(), ?, ?, 'Recorded by a test', 'PRIMARY', 'test')
                    """, encounterId, icd10Code);
        }
    }

    private JsonNode report() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/surveillance/notifiable")
                        .param("from", THE_DAY.toString())
                        .param("to", THE_DAY.toString())
                        .with(asEpidemiologist()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static JsonNode line(JsonNode report, String code) {
        for (JsonNode row : report.get("conditions")) {
            if (row.get("icd10Code").asString().equals(code)) {
                return row;
            }
        }
        throw new AssertionError("no line for " + code);
    }

    @Test
    @DisplayName("a count below the threshold is withheld as null, and says it was withheld")
    void aSmallCountIsWithheld() throws Exception {
        diagnose("A82", 2);
        diagnose("B05", 7);

        JsonNode report = report();

        // Null rather than zero. A suppressed count and no cases are different facts, and rendering
        // them identically would make the report lie in the safer-looking direction -- which is
        // exactly the direction a reader would not question.
        JsonNode rabies = line(report, "A82");
        assertThat(rabies.get("cases").isNull()).isTrue();
        assertThat(rabies.get("suppressed").asBoolean()).isTrue();

        // Above the threshold, unchanged.
        assertThat(line(report, "B05").get("cases").asLong()).isEqualTo(7);
        assertThat(line(report, "B05").get("suppressed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a zero is not suppressed: there is nobody in it to re-identify")
    void zeroIsNotWithheld() throws Exception {
        JsonNode report = report();

        // Suppressing the zeroes would hide which conditions the district is watching for, which is
        // the opposite of what this report is for -- and there is no small cell to protect: nobody
        // is re-identified by a count of nought.
        assertThat(line(report, "A00").get("cases").asLong()).isZero();
        assertThat(line(report, "A00").get("suppressed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("the report says the threshold was in force, and the total is still exact")
    void theThresholdIsDeclared() throws Exception {
        diagnose("A82", 2);

        JsonNode report = report();

        // Echoed so a reader knows whether anything could have been withheld. Without it, a report
        // with no suppressed lines is indistinguishable from one with suppression off.
        assertThat(report.get("smallCellThreshold").asInt()).isEqualTo(5);
        assertThat(report.get("suppressed").asBoolean()).isTrue();
        // The total is deliberately NOT adjusted. Withholding the line protects the two people in
        // it; withholding them from the total as well would make the arithmetic on the page wrong,
        // and a return whose lines do not add up is a return somebody sends back.
        assertThat(report.get("totalCases").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("the CSV marks the suppression rather than shipping a silent gap")
    void theCsvSaysSo() throws Exception {
        diagnose("A82", 1);

        String csv = mockMvc.perform(get("/surveillance/notifiable.csv")
                        .accept("text/csv")
                        .param("from", THE_DAY.toString())
                        .param("to", THE_DAY.toString())
                        .with(asEpidemiologist()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).contains("A82,Rabies,SUPPRESSED,24");
        assertThat(csv).contains("TRUNCATED");
        assertThat(csv).contains("small-cell threshold of 5");
    }
}
