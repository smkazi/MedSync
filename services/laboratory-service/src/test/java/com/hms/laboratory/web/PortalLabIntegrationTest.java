package com.hms.laboratory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.HashMap;
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
 * A patient's own results, and the release step that stands between them and a number.
 *
 * <p>The rule under test is one sentence: released means verified, and nothing short of it is
 * reachable. A result entered at the bench is provisional — it may be an analyzer artefact, a
 * mislabelled tube or a dilution nobody has repeated — and the first person to read an unverified
 * number must not be the one least equipped to know it might be wrong.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalLabIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder
                        .subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user")
                        .claim("roles", List.of(roles)))
                .authorities(authorities);
    }

    private static RequestPostProcessor asPatient(UUID patientId) {
        return jwt().jwt(builder -> builder
                        .subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "MRN-LAB-1")
                        .claim("roles", List.of("PATIENT"))
                        .claim("patient_id", patientId.toString()))
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_PATIENT")));
    }

    private JsonNode createOrder(UUID patientId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId.toString());
        body.put("patientMrn", "MRN-PORTAL-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("patientSex", "F");
        body.put("testCodes", List.of("CBC"));
        body.put("priority", "ROUTINE");
        String response = mockMvc.perform(post("/lab/orders").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    /** Takes an order all the way to a bench result, stopping short of a pathologist. */
    private String orderWithProvisionalResults(UUID patientId) throws Exception {
        String orderId = createOrder(patientId).get("id").asString();
        mockMvc.perform(post("/lab/orders/" + orderId + "/specimens").with(as("LAB_TECH"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/lab/orders/" + orderId + "/results").with(as("LAB_TECH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("results", List.of(
                                Map.of("parameter", "HGB", "value", "8.1", "unit", "g/dL"))))))
                .andExpect(status().isOk());
        return orderId;
    }

    private String releasedOrder(UUID patientId) throws Exception {
        String orderId = orderWithProvisionalResults(patientId);
        mockMvc.perform(post("/lab/orders/" + orderId + "/verify").with(as("PATHOLOGIST")))
                .andExpect(status().isOk());
        return orderId;
    }

    @Test
    @DisplayName("a bench result is listed as in the laboratory, and its value is not published")
    void provisionalResultsAreNotPublished() throws Exception {
        UUID patient = UUID.randomUUID();
        orderWithProvisionalResults(patient);

        String body = mockMvc.perform(get("/portal/reports").with(asPatient(patient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].progress").value("In the laboratory"))
                .andExpect(jsonPath("$[0].reportAvailable").value(false))
                .andReturn().getResponse().getContentAsString();

        // The staff summary carries resultCount and hasAbnormalResults, both live before a
        // pathologist has looked at anything. A portal reusing that shape would tell a patient one
        // of their results is abnormal hours before a clinician has seen it — the exact disclosure
        // the release step exists to prevent, delivered by a status field instead of by a report.
        //
        // Asserted on the shape rather than by grepping the body: an ISO timestamp carries
        // fractional seconds, so "orderedAt":"...:38.123Z" contains the substring "8.1" perhaps one
        // run in a thousand. A test that fails on the clock is worse than no test, and this one
        // says exactly what it means — these five fields and no others.
        assertThat(objectMapper.readTree(body).get(0).propertyNames())
                .containsExactlyInAnyOrder("orderId", "orderedAt", "tests", "progress",
                        "reportAvailable");
    }

    @Test
    @DisplayName("an unreleased report is refused in words that say why, not as a missing page")
    void anUnreleasedReportIsRefusedPlainly() throws Exception {
        UUID patient = UUID.randomUUID();
        String orderId = orderWithProvisionalResults(patient);

        // 400 rather than 404, and deliberately: the patient knows the test was taken, so "not
        // found" would be a lie about their own record.
        mockMvc.perform(get("/portal/reports/" + orderId).with(asPatient(patient)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("checked by a pathologist")));
        mockMvc.perform(get("/portal/reports/" + orderId + ".pdf").with(asPatient(patient)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a released report carries the numbers and the range each was read against")
    void aReleasedReportCarriesTheNumbers() throws Exception {
        UUID patient = UUID.randomUUID();
        String orderId = releasedOrder(patient);

        mockMvc.perform(get("/portal/reports").with(asPatient(patient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].progress").value("Report ready"))
                .andExpect(jsonPath("$[0].reportAvailable").value(true));

        String body = mockMvc.perform(get("/portal/reports/" + orderId).with(asPatient(patient)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The same view a clinician reads. "Abnormal" without the number asks the patient to take
        // the platform's word for a judgement that depends on which reference interval was applied.
        assertThat(body).contains("8.1").contains("HGB");
    }

    @Test
    @DisplayName("another patient's results are not found, rather than forbidden")
    void anotherPatientsResultsAreNotFound() throws Exception {
        UUID patient = UUID.randomUUID();
        String orderId = releasedOrder(patient);

        mockMvc.perform(get("/portal/reports/" + orderId).with(asPatient(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/portal/reports/" + orderId + ".pdf").with(asPatient(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the portal list is the session's own, from a URL naming no patient")
    void theListIsTheSessionsOwn() throws Exception {
        UUID patient = UUID.randomUUID();
        String mine = releasedOrder(patient);
        releasedOrder(UUID.randomUUID());

        // Two orders exist against two patients; the request that distinguishes them carries no
        // patient at all, only the token.
        mockMvc.perform(get("/portal/reports").with(asPatient(patient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(mine))
                .andExpect(jsonPath("$[0].tests").isNotEmpty());
    }

    @Test
    @DisplayName("staff are refused the portal, and a patient is refused the worklist")
    void neitherSideCanUseTheOther() throws Exception {
        UUID patient = UUID.randomUUID();
        mockMvc.perform(get("/portal/reports").with(as("PATHOLOGIST"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/portal/reports").with(as("ADMIN"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/lab/orders").with(asPatient(patient))).andExpect(status().isForbidden());
    }
}
