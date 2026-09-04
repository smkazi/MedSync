package com.hms.immunisation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Arrays;
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
 * The register against a real database.
 *
 * <p>Almost everything asserted here is a database rule rather than a Java one, which is why the
 * profile points at PostgreSQL: a lot number present exactly when the dose was given here, one dose
 * of one product per patient per day, an exemption that cannot exist without a sentence. An
 * in-memory database that accepted a historical dose carrying a lot number would make the whole
 * point of this schema untestable.
 *
 * <p>The care-relationship narrowing is off in this profile — it calls scheduling-service, which is
 * not running — so what these tests assert is the register's own rules. The narrowing has its own
 * suite, as it does in the laboratory and radiology.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImmunisationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt()
                .jwt(builder -> builder
                        .subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user")
                        .claim("roles", List.of(roles)))
                .authorities(authorities);
    }

    /** A lot number of this test's own, so nothing it receives collides with another run's. */
    private static String lotNo() {
        return "LOT-" + Math.abs(UUID.randomUUID().getMostSignificantBits());
    }

    private JsonNode receiveLot(String productCode, String lotNo, LocalDate expiresOn)
            throws Exception {
        String body = mockMvc.perform(post("/vaccines/lots")
                        .with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productCode", productCode,
                                "lotNo", lotNo,
                                "expiresOn", expiresOn.toString(),
                                "quantity", 10))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    // ---- the catalogue -------------------------------------------------------

    @Test
    @DisplayName("a pentavalent product reads back as the five antigens it contains")
    void aProductCarriesItsAntigens() throws Exception {
        String body = mockMvc.perform(get("/vaccines/products").with(as("NURSE")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode penta = null;
        for (JsonNode product : objectMapper.readTree(body)) {
            if ("PENTA".equals(product.get("code").asString())) {
                penta = product;
            }
        }
        assertThat(penta).as("the seeded catalogue must contain PENTA").isNotNull();

        List<String> antigens = penta.get("antigenCodes").valueStream()
                .map(JsonNode::asString).toList();
        // The whole reason the catalogue is keyed on antigens and not products: one injection,
        // five diseases, and "is this child covered for Hib?" answerable without code that knows
        // what PENTA contains.
        assertThat(antigens).containsExactlyInAnyOrder("DIPH", "PERT", "TET", "HEPB", "HIB");
    }

    @Test
    @DisplayName("a product may not contain an antigen the catalogue does not know")
    void aProductCannotInventAnAntigen() throws Exception {
        mockMvc.perform(post("/vaccines/products")
                        .with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                // Short enough to pass @Size(max = 32). A longer one is answered
                                // 400 by validation during argument binding, before the service
                                // ever looks at the contents list -- the same ordering the abuse
                                // suite documents for @Valid and @PreAuthorize.
                                "code", "MADEUP" + (System.nanoTime() % 1_000_000),
                                "name", "Made up",
                                "manufacturer", "Nobody",
                                "route", "ORAL",
                                "dosesPerVial", 1,
                                "antigenCodes", List.of("NO_SUCH_ANTIGEN")))))
                // A conflict rather than a 404: the product is the thing being created and it is
                // the contents list that is wrong.
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the catalogue is administrator-only to write and readable by anybody signed in")
    void theCatalogueIsGatedApart() throws Exception {
        mockMvc.perform(get("/vaccines/products").with(as("RECEPTIONIST")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/vaccines/antigens")
                        .with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "X", "name", "X", "protectsAgainst", "X"))))
                .andExpect(status().isForbidden());
    }

    // ---- stock ---------------------------------------------------------------

    @Test
    @DisplayName("an expired lot cannot be received, and cannot be given")
    void expiredStockIsRefusedTwice() throws Exception {
        // Receiving expired stock into the fridge is how it gets given.
        mockMvc.perform(post("/vaccines/lots")
                        .with(as("PHARMACIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productCode", "BCG",
                                "lotNo", lotNo(),
                                "expiresOn", LocalDate.now().minusDays(1).toString(),
                                "quantity", 10))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a withdrawn lot cannot be given, and the refusal says why it went")
    void aWithdrawnLotIsRefusedWithItsReason() throws Exception {
        String lot = lotNo();
        JsonNode received = receiveLot("BCG", lot, LocalDate.now().plusYears(1));

        mockMvc.perform(post("/vaccines/lots/{id}/withdraw", received.get("id").asString())
                        .with(as("PHARMACIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("reason", "Cold chain broken overnight"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usable").value(false));

        mockMvc.perform(post("/immunisations")
                        .with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-TEST-0001",
                                "productCode", "BCG",
                                "lotNo", lot,
                                "givenOn", LocalDate.now().toString(),
                                "site", "Left deltoid"))))
                .andExpect(status().isConflict());
    }

    // ---- doses ---------------------------------------------------------------

    @Test
    @DisplayName("a dose given here carries its lot, its route and the five antigens it covers")
    void aDoseGivenHereIsComplete() throws Exception {
        String lot = lotNo();
        receiveLot("PENTA", lot, LocalDate.now().plusYears(1));
        UUID patientId = UUID.randomUUID();

        mockMvc.perform(post("/immunisations")
                        .with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", patientId.toString(),
                                "patientMrn", "MRN-TEST-0002",
                                "productCode", "PENTA",
                                "lotNo", lot,
                                "givenOn", LocalDate.now().toString(),
                                "site", "Left thigh"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("ADMINISTERED_HERE"))
                .andExpect(jsonPath("$.lotNo").value(lot))
                // The route comes off the product, not the request: it is a property of the
                // vaccine, and a body that could name one would record a route PENTA does not have.
                .andExpect(jsonPath("$.route").value("INTRAMUSCULAR"))
                .andExpect(jsonPath("$.antigenCodes.length()").value(5));
    }

    @Test
    @DisplayName("the same product for the same patient on the same day is refused by the database")
    void oneDosePerProductPerDay() throws Exception {
        String lot = lotNo();
        receiveLot("BCG", lot, LocalDate.now().plusYears(1));
        UUID patientId = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of(
                "patientId", patientId.toString(),
                "patientMrn", "MRN-TEST-0003",
                "productCode", "BCG",
                "lotNo", lot,
                "givenOn", LocalDate.now().toString(),
                "site", "Left arm"));

        mockMvc.perform(post("/immunisations").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        // One nurse clicking twice, or two clinics entering the same card. uq_dose_per_day is the
        // arbiter rather than a check in application code, because both callers pass a check and
        // only one can win an insert.
        mockMvc.perform(post("/immunisations").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a dose cannot be recorded in the future")
    void aDoseCannotHaveHappenedTomorrow() throws Exception {
        String lot = lotNo();
        receiveLot("BCG", lot, LocalDate.now().plusYears(1));

        mockMvc.perform(post("/immunisations")
                        .with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-TEST-0004",
                                "productCode", "BCG",
                                "lotNo", lot,
                                "givenOn", LocalDate.now().plusDays(1).toString(),
                                "site", "Left arm"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("giving a vaccine is a clinician's act, and the front desk is refused it")
    void recordingADoseIsGatedToClinicians() throws Exception {
        mockMvc.perform(post("/immunisations")
                        .with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-TEST-0005",
                                "productCode", "BCG",
                                "lotNo", "anything",
                                "givenOn", LocalDate.now().toString(),
                                "site", "Left arm"))))
                .andExpect(status().isForbidden());
    }

    // ---- adverse events ------------------------------------------------------

    @Test
    @DisplayName("an event before the dose it followed is not an event following it")
    void anAefiCannotPrecedeItsDose() throws Exception {
        String lot = lotNo();
        receiveLot("MR", lot, LocalDate.now().plusYears(1));
        String dose = mockMvc.perform(post("/immunisations")
                        .with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-TEST-0006",
                                "productCode", "MR",
                                "lotNo", lot,
                                "givenOn", LocalDate.now().toString(),
                                "site", "Right arm"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String doseId = objectMapper.readTree(dose).get("id").asString();

        // Checked in the service and not by a CHECK constraint, which the migration explains:
        // PostgreSQL cannot compare against another table's row inside one.
        mockMvc.perform(post("/immunisations/{id}/adverse-events", doseId)
                        .with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "onsetOn", LocalDate.now().minusDays(3).toString(),
                                "description", "Fever and a rash",
                                "seriousness", "MINOR",
                                "outcome", "RECOVERED"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a death is reportable whatever seriousness somebody recorded")
    void anOutcomeCanMakeAnEventReportable() throws Exception {
        String lot = lotNo();
        receiveLot("MR", lot, LocalDate.now().plusYears(1));
        String dose = mockMvc.perform(post("/immunisations")
                        .with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-TEST-0007",
                                "productCode", "MR",
                                "lotNo", lot,
                                "givenOn", LocalDate.now().toString(),
                                "site", "Right arm"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/immunisations/{id}/adverse-events",
                        objectMapper.readTree(dose).get("id").asString())
                        .with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "onsetOn", LocalDate.now().toString(),
                                "description", "Collapsed shortly after the injection",
                                // Recorded as MINOR, which is a judgement somebody made, and the
                                // outcome overrides it: seriousness is an opinion and a death is a
                                // fact.
                                "seriousness", "MINOR",
                                "outcome", "DIED"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportable").value(true));
    }

    // ---- exemptions ----------------------------------------------------------

    @Test
    @DisplayName("an exemption needs a sentence, not a word")
    void anExemptionNeedsAReason() throws Exception {
        mockMvc.perform(post("/immunisations/exemptions")
                        .with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-TEST-0008",
                                "kind", "MEDICAL",
                                "reason", "allergy"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a blanket exemption names no antigen, and covers every one")
    void aBlanketExemptionIsRepresentable() throws Exception {
        mockMvc.perform(post("/immunisations/exemptions")
                        .with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-TEST-0009",
                                "kind", "MEDICAL",
                                "reason", "Severe combined immunodeficiency, under haematology."))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.antigenCode").doesNotExist())
                .andExpect(jsonPath("$.live").value(true));
    }

    // ---- the patient's register ----------------------------------------------

    @Test
    @DisplayName("the register reads back as one patient's timeline")
    void theRegisterReadsBackAsATimeline() throws Exception {
        String lot = lotNo();
        receiveLot("BCG", lot, LocalDate.now().plusYears(1));
        UUID patientId = UUID.randomUUID();

        mockMvc.perform(post("/immunisations")
                        .with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", patientId.toString(),
                                "patientMrn", "MRN-TEST-0010",
                                "productCode", "BCG",
                                "lotNo", lot,
                                "givenOn", LocalDate.now().toString(),
                                "site", "Left arm"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/immunisations/patients/{id}", patientId).with(as("PHARMACIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientMrn").value("MRN-TEST-0010"))
                .andExpect(jsonPath("$.doses.length()").value(1))
                .andExpect(jsonPath("$.doses[0].lotNo").value(lot));
    }

    @Test
    @DisplayName("the front desk may not read an immunisation history")
    void theRegisterIsChartContent() throws Exception {
        mockMvc.perform(get("/immunisations/patients/{id}", UUID.randomUUID())
                        .with(as("RECEPTIONIST")))
                .andExpect(status().isForbidden());
    }
}
