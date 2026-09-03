package com.hms.patient.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * The patient's own record, through the portal.
 *
 * <p>The assertion this class exists for is the one in {@code aPortalSessionSeesOnlyItsOwnRecord}:
 * there is no patient id in the request, so a portal session reading somebody else's record is not
 * refused — it is unrepresentable. Everything else here checks the two edges around that: what the
 * profile deliberately omits, and who is refused the endpoint entirely.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** A staff caller, exactly as {@code PatientApiIntegrationTest} builds one. */
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

    /**
     * A portal session for one patient.
     *
     * <p>The {@code patient_id} claim is the whole of the identity: no header, no parameter and no
     * body carries it, which is what the endpoints under test rely on.
     */
    private static RequestPostProcessor asPatient(String patientId) {
        return jwt().jwt(builder -> builder
                        .subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "MRN-TEST")
                        .claim("roles", List.of("PATIENT"))
                        .claim("patient_id", patientId))
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_PATIENT")));
    }

    private JsonNode register(String surname, Map<String, Object> extras) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "firstName", "Meera", "lastName", surname, "dateOfBirth", "1988-04-12",
                "sex", "FEMALE", "phone", "+91 98200 11223"));
        body.putAll(extras);
        String created = mockMvc.perform(post("/patients").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created);
    }

    private String uniqueSurname() {
        return "Portal" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Nested
    @DisplayName("the patient's own record")
    class TheProfile {

        @Test
        @DisplayName("a portal session sees its own record, and names no patient to do it")
        void aPortalSessionSeesOnlyItsOwnRecord() throws Exception {
            String surname = uniqueSurname();
            JsonNode patient = register(surname, Map.of("email", "meera@example.invalid"));
            String id = patient.get("id").asString();

            mockMvc.perform(get("/portal/me").with(asPatient(id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.mrn").value(patient.get("mrn").asString()))
                    .andExpect(jsonPath("$.lastName").value(surname));
        }

        @Test
        @DisplayName("two portal sessions read two different records from the same URL")
        void twoSessionsReadTwoRecords() throws Exception {
            String first = register(uniqueSurname(), Map.of()).get("id").asString();
            String second = register(uniqueSurname(), Map.of()).get("id").asString();

            // The same request line, twice, differing only in the token. This is the property that
            // makes the portal safe: the URL carries no identity at all.
            mockMvc.perform(get("/portal/me").with(asPatient(first)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(first));
            mockMvc.perform(get("/portal/me").with(asPatient(second)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(second));
        }

        @Test
        @DisplayName("the registration notes staff write about a patient are not shown to them")
        void notesAreNotPublished() throws Exception {
            JsonNode patient = register(uniqueSurname(),
                    Map.of("notes", "Difficult to reach on the mobile; try the landline after six."));
            String id = patient.get("id").asString();

            String body = mockMvc.perform(get("/portal/me").with(asPatient(id)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // Greping the rendered body rather than inspecting the DTO: what matters is what goes
            // out on the wire. Staff free text about a patient is written by people who have never
            // considered that its subject would read it, and publishing it changes what gets
            // written there — which is a worse outcome than a portal that omits it.
            assertThat(body.toLowerCase(Locale.ROOT))
                    .doesNotContain("difficult to reach")
                    .doesNotContain("landline");
        }

        @Test
        @DisplayName("an allergy is shown to its subject, without the name of who recorded it")
        void allergiesAreShownWithoutTheStaffName() throws Exception {
            JsonNode patient = register(uniqueSurname(), Map.of());
            String id = patient.get("id").asString();
            mockMvc.perform(post("/patients/" + id + "/allergies").with(as("DOCTOR"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "substance", "Penicillin", "severity", "LIFE_THREATENING"))))
                    .andExpect(status().isCreated());

            String body = mockMvc.perform(get("/portal/me").with(asPatient(id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allergies[0].substance").value("Penicillin"))
                    .andExpect(jsonPath("$.allergies[0].critical").value(true))
                    .andReturn().getResponse().getContentAsString();

            // An allergy recorded wrongly is the single most valuable thing on this screen for a
            // patient to notice, so it is here. Who typed it is the hospital's record of its own
            // work and is not.
            assertThat(objectMapper.readTree(body).get("allergies").get(0).propertyNames())
                    .doesNotContain("recordedBy");
        }

        @Test
        @DisplayName("a session that names no patient is refused, rather than shown somebody")
        void aSessionWithNoPatientIsRefused() throws Exception {
            RequestPostProcessor rolelessPatient = jwt().jwt(builder -> builder
                            .subject(UUID.randomUUID().toString())
                            .claim("preferred_username", "MRN-NOBODY")
                            .claim("roles", List.of("PATIENT")))
                    .authorities(List.of(new SimpleGrantedAuthority("ROLE_PATIENT")));

            mockMvc.perform(get("/portal/me").with(rolelessPatient))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("not linked to a patient record")));
        }

        @Test
        @DisplayName("staff are refused the portal, including an administrator")
        void staffAreRefusedThePortal() throws Exception {
            // Every other constant in Roles carries ADMIN. This one does not, and deliberately:
            // /portal/me answers "the signed-in patient's own record", and there is no patient an
            // administrator is. 403 is the honest answer; an empty record would read as "you have
            // no allergies", which is a clinically dangerous thing to tell the wrong person.
            for (String role : List.of("ADMIN", "DOCTOR", "NURSE", "RECEPTIONIST", "LAB_TECH")) {
                mockMvc.perform(get("/portal/me").with(as(role)))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("no session at all is 401, not 403")
        void anonymousIsUnauthorized() throws Exception {
            mockMvc.perform(get("/portal/me")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("issuing portal access")
    class Enrolment {

        @Test
        @DisplayName("a record with no email address cannot be enrolled, and the refusal says why")
        void noEmailNoEnrolment() throws Exception {
            String id = register(uniqueSurname(), Map.of()).get("id").asString();

            // An account nobody can reach is an account nobody can recover, and the first thing it
            // would need is a password reset sent to an address that does not exist.
            mockMvc.perform(post("/patients/" + id + "/portal-account").with(as("RECEPTIONIST")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("no email address")));
        }

        @Test
        @DisplayName("a clinician may not hand out portal credentials")
        void cliniciansMayNotEnrol() throws Exception {
            String id = register(uniqueSurname(), Map.of("email", "meera@example.invalid"))
                    .get("id").asString();

            // Enrolment is an identity check somebody performs face to face, and a doctor who could
            // mint a portal account against any patient id could mint one against a neighbour's
            // record and read it at home, with the audit line naming a routine enrolment.
            for (String role : List.of("DOCTOR", "NURSE", "LAB_TECH", "PATHOLOGIST", "CASHIER")) {
                mockMvc.perform(post("/patients/" + id + "/portal-account").with(as(role)))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("enrolling a patient who does not exist is a 404 from the register")
        void unknownPatientIsNotFound() throws Exception {
            // The point of enrolment living in this service: an id that names no patient is refused
            // where the register is, rather than becoming a portal account pointing at nothing.
            mockMvc.perform(post("/patients/" + UUID.randomUUID() + "/portal-account")
                            .with(as("RECEPTIONIST")))
                    .andExpect(status().isNotFound());
        }
    }
}
