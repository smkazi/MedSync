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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * A patient's own prescriptions.
 *
 * <p>Read-only, and the omission is the design. Every write in this service is a clinical or a
 * pharmacy act, each already refused to everybody outside one role; a patient asking for a repeat
 * is a request to a prescriber, not a prescription, and building it as one here would be building
 * the wrong thing in the most dangerous module on the platform.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalPharmacyIntegrationTest {

    @MockitoBean
    private AllergyClient allergyClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID patientId;

    @BeforeEach
    void noAllergiesByDefault() {
        patientId = UUID.randomUUID();
        when(allergyClient.forPatient(any(UUID.class), nullable(String.class))).thenReturn(List.of());
    }

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user"))
                .authorities(authorities);
    }

    private static RequestPostProcessor asPatient(UUID patient) {
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "MRN-RX-PORTAL")
                        .claim("roles", List.of("PATIENT"))
                        .claim("patient_id", patient.toString()))
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_PATIENT")));
    }

    private JsonNode prescribeFor(UUID patient) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientId", patient);
        body.put("patientMrn", "MRN-RX-" + Math.abs(System.nanoTime() % 1_000_000));
        body.put("items", List.of(Map.of("drugCode", "AMOX500", "dose", "1 tablet",
                "frequency", "twice daily", "durationDays", 5, "quantity", 10)));
        return objectMapper.readTree(mockMvc.perform(post("/prescriptions").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a patient sees their own medicines in full, with the dose and the instructions")
    void aPatientSeesTheirOwnMedicines() throws Exception {
        prescribeFor(patientId);

        String body = mockMvc.perform(get("/portal/prescriptions").with(asPatient(patientId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].items[0].dose").value("1 tablet"))
                .andExpect(jsonPath("$[0].items[0].frequency").value("twice daily"))
                .andReturn().getResponse().getContentAsString();

        // The dose, the frequency and the instructions are the part most often misremembered on
        // the way home, which is the whole reason the full shape is used rather than a summary.
        assertThat(body).contains("AMOX500");
    }

    @Test
    @DisplayName("another patient's prescription is not found, rather than forbidden")
    void anotherPatientsPrescriptionIsNotFound() throws Exception {
        JsonNode prescription = prescribeFor(patientId);

        mockMvc.perform(get("/portal/prescriptions/" + prescription.get("id").asString())
                        .with(asPatient(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/portal/prescriptions").with(asPatient(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("a patient cannot prescribe, dispense or administer anything")
    void thePortalIsReadOnly() throws Exception {
        Map<String, Object> body = Map.of(
                "patientId", patientId, "patientMrn", "MRN-RX-SELF",
                "items", List.of(Map.of("drugCode", "AMOX500", "dose", "1 tablet",
                        "frequency", "twice daily", "durationDays", 5, "quantity", 10)));
        mockMvc.perform(post("/prescriptions").with(asPatient(patientId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("staff are refused the portal, and a patient is refused the dispensing queue")
    void neitherSideCanUseTheOther() throws Exception {
        mockMvc.perform(get("/portal/prescriptions").with(as("PHARMACIST")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/portal/prescriptions").with(as("ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/pharmacy/stock").with(asPatient(patientId)))
                .andExpect(status().isForbidden());
    }
}
