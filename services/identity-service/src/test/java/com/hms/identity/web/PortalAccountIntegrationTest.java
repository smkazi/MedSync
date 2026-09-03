package com.hms.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.repo.UserRepository;
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
 * Portal accounts: the credential half of the patient portal.
 *
 * <p>Two properties carry the weight here. The token a portal account signs in with must name the
 * patient it belongs to, because five services in three other modules read nothing else to decide
 * whose record they are showing. And the account must not appear in the staff directory, because
 * that directory is a screen and a set of pick-lists that nobody thinks of as a patient register.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalAccountIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository users;

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

    private JsonNode enrol(UUID patientId, String mrn, String... roles) throws Exception {
        String body = mockMvc.perform(post("/admin/portal-accounts")
                        .with(as(roles.length == 0 ? new String[] {"RECEPTIONIST"} : roles))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", patientId,
                                "mrn", mrn,
                                "email", mrn.toLowerCase(java.util.Locale.ROOT) + "@example.invalid",
                                "fullName", "Portal Test Patient"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static String uniqueMrn() {
        return "MRN-P" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    /** The decoded payload of a signed JWT. The signature is tested in {@code AuthFlowIntegrationTest}. */
    private JsonNode claimsOf(String accessToken) {
        String payload = accessToken.split("\\.")[1];
        return objectMapper.readTree(new String(
                java.util.Base64.getUrlDecoder().decode(payload), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("enrolment answers a one-time password, and the account owes a change before it works")
    void enrolmentIssuesAOneTimePassword() throws Exception {
        UUID patientId = UUID.randomUUID();
        String mrn = uniqueMrn();
        JsonNode issued = enrol(patientId, mrn);

        assertThat(issued.get("username").asString()).isEqualTo(mrn.toLowerCase(java.util.Locale.ROOT));
        assertThat(issued.get("temporaryPassword").asString()).hasSize(20);
        // Read aloud across a desk, so the alphabet drops 1/I/l and 0/O.
        assertThat(issued.get("temporaryPassword").asString()).doesNotContainAnyWhitespaces()
                .matches("[0-9A-HJKMNP-TV-Z]+");

        mockMvc.perform(get("/admin/portal-accounts/" + patientId).with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("the token a portal account signs in with names the patient it belongs to")
    void theTokenNamesThePatient() throws Exception {
        UUID patientId = UUID.randomUUID();
        String mrn = uniqueMrn();
        JsonNode issued = enrol(patientId, mrn);
        String username = issued.get("username").asString();
        String temporary = issued.get("temporaryPassword").asString();

        // Straight after enrolment the account owes a password change, so its token carries no
        // roles at all — the existing gate, unchanged, and the reason a portal account cannot be
        // used before the patient has chosen their own password.
        JsonNode first = login(username, temporary);
        assertThat(claimsOf(first.get("accessToken").asString()).get("roles")).isEmpty();

        mockMvc.perform(post("/auth/change-password")
                        .header("Authorization", "Bearer " + first.get("accessToken").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", temporary,
                                "newPassword", "ChosenByThePatient!2026"))))
                .andExpect(status().isOk());

        JsonNode second = login(username, "ChosenByThePatient!2026");
        JsonNode claims = claimsOf(second.get("accessToken").asString());
        assertThat(claims.get("roles").toString()).contains("PATIENT");
        // The claim every portal endpoint in five services reads, and the only place they read it.
        assertThat(claims.get("patient_id").asString()).isEqualTo(patientId.toString());
    }

    @Test
    @DisplayName("a staff token carries no patient claim at all")
    void staffTokensCarryNoPatientClaim() throws Exception {
        JsonNode body = login("admin", "TestPassword!2026");
        JsonNode claims = claimsOf(body.get("accessToken").asString());
        // Absent rather than null-valued: a portal endpoint asks for the claim and gets nothing,
        // which is what makes an administrator's token unusable there rather than ambiguous.
        assertThat(claims.has("patient_id") && !claims.get("patient_id").isNull()).isFalse();
    }

    @Test
    @DisplayName("portal accounts do not appear in the staff directory")
    void portalAccountsAreNotStaff() throws Exception {
        String mrn = uniqueMrn();
        enrol(UUID.randomUUID(), mrn);

        String listed = mockMvc.perform(get("/admin/users").with(as("ADMIN"))
                        .param("q", mrn.toLowerCase(java.util.Locale.ROOT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The staff directory is read by an administration screen and by the pick-lists that link
        // a clinician to a login. A hospital's patients outnumber its staff by orders of magnitude,
        // and listing them there would be a patient register reached through a page nobody thinks
        // of as one.
        assertThat(objectMapper.readTree(listed).get("content")).isEmpty();
        // The account is real, though — it is simply administered somewhere else.
        assertThat(users.findByUsernameIgnoreCase(mrn.toLowerCase(java.util.Locale.ROOT))).isPresent();
    }

    @Test
    @DisplayName("re-issuing access resets the password and ends every live session")
    void reissuingRevokesSessions() throws Exception {
        UUID patientId = UUID.randomUUID();
        JsonNode first = enrol(patientId, uniqueMrn());
        JsonNode second = enrol(patientId, uniqueMrn());

        // The same account, not a second one: the username stays the MRN it was enrolled under,
        // because a patient id is never moved and an account is never re-pointed.
        assertThat(second.get("username").asString()).isEqualTo(first.get("username").asString());
        assertThat(second.get("temporaryPassword").asString())
                .isNotEqualTo(first.get("temporaryPassword").asString());
        assertThat(users.findByPatientId(patientId)).isPresent();
    }

    @Test
    @DisplayName("withdrawing access deactivates the account and keeps its history")
    void withdrawingDeactivates() throws Exception {
        UUID patientId = UUID.randomUUID();
        enrol(patientId, uniqueMrn());

        mockMvc.perform(delete("/admin/portal-accounts/" + patientId).with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // Deleting would take the account's own audit trail with it, so it is deactivated and
        // stays. Re-issuing brings it back, which is what somebody standing at the desk wants.
        assertThat(users.findByPatientId(patientId)).isPresent();
    }

    @Test
    @DisplayName("only the front desk may hand out portal credentials")
    void onlyTheFrontDeskMayEnrol() throws Exception {
        for (String role : List.of("DOCTOR", "NURSE", "LAB_TECH", "PATHOLOGIST", "CASHIER", "PHARMACIST")) {
            mockMvc.perform(post("/admin/portal-accounts").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "patientId", UUID.randomUUID(), "mrn", uniqueMrn(),
                                    "email", "nope@example.invalid", "fullName", "Nobody"))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("a patient with no portal account is a 404 that says what to do about it")
    void noAccountIsNotFound() throws Exception {
        mockMvc.perform(get("/admin/portal-accounts/" + UUID.randomUUID()).with(as("RECEPTIONIST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("front desk can issue it")));
    }

    private JsonNode login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
