package com.hms.immunisation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A dose given somewhere else, and the four constraints that make it say so on its face.
 *
 * <p><strong>Half of this file goes round the application deliberately.</strong> The rules under
 * test are database rules, and a test that only ever posted JSON would be testing the DTO
 * validation and the factory method — which is not where the guarantee lives. The register's own
 * class comment says the arbiter is the schema; these cases insert rows with {@code JdbcTemplate}
 * and assert PostgreSQL refuses them by name, which is the only way to know that is true. It is
 * also why this module's tests run against a real PostgreSQL: an in-memory database that accepted a
 * historical dose carrying a lot number would make the whole point of the schema untestable.
 *
 * <p>The failure mode all of this exists to prevent is not that historical doses go unrecorded. It
 * is that somebody types them in as though given here, with an invented lot number, because
 * {@code lot_id} used to be the only way to record a dose at all — and that puts fabricated
 * evidence into the one column a recall reads. A confident wrong answer is worse than an incomplete
 * record, because nobody goes looking further after one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HistoricalDoseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

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

    private static String mrn() {
        return "MRN-HIST-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A well-formed historical dose, which each test then varies one field of. */
    private static Map<String, Object> aDoseFromACard(UUID patientId, String productCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId.toString());
        body.put("patientMrn", mrn());
        body.put("productCode", productCode);
        body.put("givenOn", LocalDate.now().minusYears(2).toString());
        body.put("dateEstimated", false);
        body.put("source", "HISTORICAL_DOCUMENTED");
        body.put("evidence", "Immunisation card seen, entry dated and stamped by the clinic");
        return body;
    }

    private JsonNode postHistorical(Map<String, Object> body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post("/immunisations/historical").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return response.isBlank() ? null : objectMapper.readTree(response);
    }

    // ---- through the API -----------------------------------------------------

    @Test
    @DisplayName("a dose from a card is recorded without inventing a lot number")
    void aDoseFromACardNeedsNoLot() throws Exception {
        UUID patientId = UUID.randomUUID();
        Map<String, Object> body = aDoseFromACard(patientId, "BCG");

        JsonNode recorded = postHistorical(body, 201);

        assertThat(recorded.get("source").asString()).isEqualTo("HISTORICAL_DOCUMENTED");
        // The two fields that make it say so on its face: no lot, and a sentence saying what was
        // seen. A register that showed a lot number here would be a register a recall could not
        // trust anywhere.
        assertThat(recorded.get("lotNo").isNull()).isTrue();
        assertThat(recorded.get("evidence").asString()).contains("Immunisation card seen");
        // And the antigens still come out of the product, so a historical pentavalent dose counts
        // for its five antigens exactly as one given here does.
        assertThat(recorded.get("antigenCodes")).isNotEmpty();
    }

    @Test
    @DisplayName("what a parent remembers is kept, and flagged as a recollection")
    void whatAParentRemembersIsFlagged() throws Exception {
        Map<String, Object> body = aDoseFromACard(UUID.randomUUID(), "OPV");
        body.put("source", "HISTORICAL_PARENT_REPORTED");
        body.put("dateEstimated", true);
        body.put("evidence", "Mother recalls a dose at the village clinic at about six months");

        JsonNode recorded = postHistorical(body, 201);

        // Three sources rather than a boolean, and this is the case that argues for it: "his mother
        // says he had it" is a fact worth keeping and is not a record, and a measure may
        // legitimately count one and not the other.
        assertThat(recorded.get("source").asString()).isEqualTo("HISTORICAL_PARENT_REPORTED");
        assertThat(recorded.get("givenOnEstimated").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the register reads back a card dose beside one given here")
    void theRegisterShowsBoth() throws Exception {
        UUID patientId = UUID.randomUUID();
        postHistorical(aDoseFromACard(patientId, "BCG"), 201);

        JsonNode register = objectMapper.readTree(
                mockMvc.perform(get("/immunisations/patients/" + patientId).with(as("DOCTOR")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(register.get("doses").size()).isEqualTo(1);
        JsonNode dose = register.get("doses").get(0);
        assertThat(dose.get("source").asString()).isEqualTo("HISTORICAL_DOCUMENTED");
        // No route and no site either: nobody here saw which arm it went into, and inventing a
        // route is inventing an observation.
        assertThat(dose.get("route").isNull()).isTrue();
        assertThat(dose.get("site").isNull()).isTrue();
        assertThat(dose.get("givenBy").isNull()).isTrue();
    }

    @Test
    @DisplayName("naming this hospital as the source is a 400, not a 500")
    void theWrongSourceIsARequestError() throws Exception {
        Map<String, Object> body = aDoseFromACard(UUID.randomUUID(), "BCG");
        body.put("source", "ADMINISTERED_HERE");

        // The entity guards the same rule and throws IllegalArgumentException, which the handler
        // renders as a 500 -- so before this test the endpoint answered "we are broken" to a caller
        // who had simply named the wrong source. The factory's throw stays as a backstop and the
        // service refuses first, naming the endpoint that does take a lot number.
        mockMvc.perform(post("/immunisations/historical").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("POST /immunisations")));
    }

    @Test
    @DisplayName("evidence has a floor, because a claim with no provenance is not a record")
    void evidenceHasAFloor() throws Exception {
        Map<String, Object> body = aDoseFromACard(UUID.randomUUID(), "BCG");
        body.put("evidence", "card");

        // Eight characters is a floor rather than a rule: "card" and "told" are what a free-text box
        // collects when it does not ask for a sentence, and the next clinician cannot tell either
        // from a record.
        postHistorical(body, 400);
    }

    @Test
    @DisplayName("a card dose cannot be dated in the future")
    void aCardDoseCannotBeInTheFuture() throws Exception {
        Map<String, Object> body = aDoseFromACard(UUID.randomUUID(), "BCG");
        body.put("givenOn", LocalDate.now().plusDays(1).toString());

        postHistorical(body, 409);
    }

    @Test
    @DisplayName("a retired product still takes a card dose, and takes no new one")
    void aRetiredProductStillTakesACardDose() throws Exception {
        // The clearest illustration of why these are two methods rather than one with a flag. A
        // vaccine withdrawn from this hospital's shelf is still a vaccine a child had somewhere
        // else in 2019: refusing to record it would mean losing the dose, or recording it against a
        // product they did not receive.
        String code = "RETIRED" + UUID.randomUUID().toString().substring(0, 6);
        mockMvc.perform(post("/vaccines/products").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code, "name", "Withdrawn vaccine",
                                "manufacturer", "Generic", "route", "INTRAMUSCULAR",
                                "dosesPerVial", 1, "antigenCodes", List.of("BCG")))))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/vaccines/products/" + code).with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());

        postHistorical(aDoseFromACard(UUID.randomUUID(), code), 201);

        // And the other direction, which is the half that matters: it cannot be given today.
        mockMvc.perform(post("/immunisations").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", mrn(),
                                "productCode", code,
                                "lotNo", "anything",
                                "givenOn", LocalDate.now().toString(),
                                "site", "Left arm"))))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("retired")));
    }

    @Test
    @DisplayName("one product, one patient, one day — across both paths, not just one")
    void oneDosePerDayCrossesBothPaths() throws Exception {
        UUID patientId = UUID.randomUUID();
        Map<String, Object> body = aDoseFromACard(patientId, "BCG");
        body.put("givenOn", LocalDate.now().minusYears(1).toString());
        postHistorical(body, 201);

        // The same product, patient and day again. uq_dose_per_day does not know or care which
        // endpoint a row came through, which is what makes it the arbiter: two clinics entering the
        // same card both pass any check application code could make, and only one can win an insert.
        postHistorical(body, 409);
    }

    // ---- and round the application, where the guarantee actually lives -------

    @Nested
    @DisplayName("the database is the arbiter")
    class TheDatabaseRefuses {

        /**
         * Inserts a row directly, bypassing every Java rule.
         *
         * <p>Deliberately not going through the entity or the DTO: what is under test is whether a
         * row that broke these rules could exist at all, and a test that could only reach the
         * database through code that already refuses would prove nothing about the schema.
         */
        private void insert(String source, UUID lotId, String route, String site, String givenBy,
                            String evidence, boolean estimated) {
            new JdbcTemplate(dataSource).update("""
                    insert into immunisation.immunisations
                        (id, patient_id, patient_mrn, product_code, product_name, lot_id, source,
                         given_on, given_on_estimated, route, site, given_by, evidence, recorded_by)
                    values (gen_random_uuid(), gen_random_uuid(), ?, 'BCG', 'BCG vaccine', ?, ?,
                            current_date - 30, ?, ?, ?, ?, ?, 'raw-sql-test')
                    """, mrn(), lotId, source, estimated, route, site, givenBy, evidence);
        }

        /** A real lot row, for the cases that need a lot id the foreign key will accept. */
        private UUID aLot() {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into immunisation.vaccine_lots
                        (id, product_code, lot_no, expires_on, quantity_on_hand, received_on)
                    values (?, 'BCG', ?, current_date + 365, 5, current_date)
                    """, id, "RAW-" + UUID.randomUUID().toString().substring(0, 8));
            return id;
        }

        @Test
        @DisplayName("a dose from a card cannot carry a lot number")
        void aHistoricalDoseCannotCarryALot() {
            UUID lotId = aLot();

            assertThatThrownBy(() -> insert("HISTORICAL_DOCUMENTED", lotId, null, null, null,
                    "Immunisation card seen and dated", false))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_lot_iff_given_here");
        }

        @Test
        @DisplayName("a dose given here cannot be recorded without one")
        void aHereGivenDoseCannotOmitTheLot() {
            // The reverse half of the biconditional, and the more dangerous of the two: without it a
            // dose given here could be recorded with no lot at all, and the recall query would miss
            // it silently. A silent miss is worse than a refusal.
            assertThatThrownBy(() -> insert("ADMINISTERED_HERE", null, "INTRADERMAL", "Left arm",
                    "nurse.test", null, false))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_lot_iff_given_here");
        }

        @Test
        @DisplayName("a dose given here was witnessed: by whom, by what route, into which arm")
        void aHereGivenDoseIsComplete() {
            UUID lotId = aLot();

            assertThatThrownBy(() -> insert("ADMINISTERED_HERE", lotId, null, null, null, null, false))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_given_here_is_complete");
        }

        @Test
        @DisplayName("a dose from elsewhere carries what was seen")
        void aHistoricalDoseCarriesEvidence() {
            assertThatThrownBy(() -> insert("HISTORICAL_PARENT_REPORTED", null, null, null, null,
                    "told", false))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_historical_carries_evidence");
        }

        @Test
        @DisplayName("we know the date of a dose we gave, so it cannot be an estimate")
        void aHereGivenDateCannotBeEstimated() {
            UUID lotId = aLot();

            assertThatThrownBy(() -> insert("ADMINISTERED_HERE", lotId, "INTRADERMAL", "Left arm",
                    "nurse.test", null, true))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_estimated_only_when_historical");
        }

        @Test
        @DisplayName("a fourth source does not exist, whatever a caller writes")
        void thereIsNoFourthSource() {
            // The enum is code because each value decides which columns are required. The CHECK is
            // what stops a row appearing with a source nothing has a rule for -- a gap in the record
            // wearing a source's clothes.
            assertThatThrownBy(() -> insert("HISTORICAL_GUESSED", null, null, null, null,
                    "Somebody thought so", false))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_immunisation_source");
        }

        @Test
        @DisplayName("and the two well-formed combinations are both accepted")
        void bothWellFormedShapesAreAccepted() {
            // The half that stops all of the above from passing for the wrong reason: a schema that
            // refused everything would satisfy every assertion in this class.
            insert("HISTORICAL_DOCUMENTED", null, null, null, null,
                    "Immunisation card seen and dated", true);
            insert("ADMINISTERED_HERE", aLot(), "INTRADERMAL", "Left arm", "nurse.test", null, false);
        }
    }
}
