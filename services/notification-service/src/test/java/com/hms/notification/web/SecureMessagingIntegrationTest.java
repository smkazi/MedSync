package com.hms.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.notification.client.PortalIdentityClient;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
 * Secure messaging, on both sides of the conversation.
 *
 * <p>This service's founding rule is that an outbound message carries no PHI, and these tables hold
 * exactly what that rule keeps out of an SMS. The rule is about the channel: a sentence on a screen
 * behind a password the patient chose is safe, and the same sentence on a handset on a family plan
 * is not. What the tests below check is that the two never cross — a thread is readable only by the
 * patient it belongs to, and the notification that says one has arrived is a different code path
 * that says nothing about its contents.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecureMessagingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The register, stubbed.
     *
     * <p>The real client reads {@code /portal/me} over HTTP with the patient's own token; this
     * suite is about what this service does with the answer, and standing up patient-service to
     * get one would make these tests a deployment test wearing a unit test's clothes.
     */
    @MockitoBean
    private PortalIdentityClient identity;

    private UUID patient;

    @BeforeEach
    void stubTheRegister() {
        patient = UUID.randomUUID();
        when(identity.require(anyString()))
                .thenAnswer(invocation -> new PortalIdentityClient.PortalIdentity(patient, "MRN-MSG-1"));
    }

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
                        .claim("preferred_username", "MRN-MSG-1")
                        .claim("roles", List.of("PATIENT"))
                        .claim("patient_id", patientId.toString()))
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_PATIENT")));
    }

    private JsonNode startThread(UUID patientId, String subject, String body) throws Exception {
        String created = mockMvc.perform(post("/portal/messages").with(asPatient(patientId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subject", subject, "body", body))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created);
    }

    @Test
    @DisplayName("a patient asks, the hospital answers, and the thread's state follows who wrote last")
    void aConversation() throws Exception {
        JsonNode thread = startThread(patient, "My discharge medicines",
                "I was given two boxes and the labels say different things about food.");
        String id = thread.get("id").asString();

        // OPEN means somebody owes the patient an answer. The status is derived from who wrote
        // last rather than set by a caller — a settable one would be set to ANSWERED by whoever
        // wanted the queue to look shorter.
        assertThat(thread.get("status").asString()).isEqualTo("OPEN");

        mockMvc.perform(post("/notifications/messages/" + id + "/replies").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "body", "Take both with food. Ring the ward if you are unsure."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.messages.length()").value(2));

        // The patient replying puts the ball back in the hospital's court.
        mockMvc.perform(post("/portal/messages/" + id + "/replies").with(asPatient(patient))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", "Thank you — one more thing."))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @DisplayName("the unread badge counts the hospital's messages and never the patient's own")
    void unreadCountsStaffMessagesOnly() throws Exception {
        JsonNode thread = startThread(patient, "A question", "Something I would like to ask.");
        String id = thread.get("id").asString();

        // The patient has obviously read what they wrote themselves.
        mockMvc.perform(get("/portal/messages/unread").with(asPatient(patient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));

        mockMvc.perform(post("/notifications/messages/" + id + "/replies").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", "Here is the answer."))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/portal/messages/unread").with(asPatient(patient)))
                .andExpect(jsonPath("$.unread").value(1));

        // Opening the thread is what "read" means. A separate mark-as-read call is one a client can
        // forget, at which point the badge becomes a number nobody believes.
        mockMvc.perform(get("/portal/messages/" + id).with(asPatient(patient)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/portal/messages/unread").with(asPatient(patient)))
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    @DisplayName("another patient's conversation is not found, rather than forbidden")
    void anotherPatientsThreadIsNotFound() throws Exception {
        JsonNode thread = startThread(patient, "Private", "Something about my own treatment.");
        String id = thread.get("id").asString();
        UUID somebodyElse = UUID.randomUUID();

        // 404 rather than 403: a thread id that comes back "not yours" is a thread id confirmed to
        // exist, and a conversation between the hospital and a named stranger is worth more to
        // somebody enumerating ids than the distinction between the codes is worth to anybody else.
        mockMvc.perform(get("/portal/messages/" + id).with(asPatient(somebodyElse)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/portal/messages/" + id + "/replies").with(asPatient(somebodyElse))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", "Let me in"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the inbox lists subjects and never a line of anybody's message")
    void theInboxCarriesNoBody() throws Exception {
        startThread(patient, "Blood test", "My haemoglobin was mentioned as a bit low last time.");

        String body = mockMvc.perform(get("/portal/messages").with(asPatient(patient)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // An inbox that previewed the first line of each conversation would put a clinical sentence
        // into every screenshot, every shoulder-surf and every back-button cache of the list page.
        assertThat(body).contains("Blood test");
        assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("haemoglobin");
    }

    @Test
    @DisplayName("a closed conversation takes no more messages from either side")
    void closedThreadsAreFinal() throws Exception {
        JsonNode thread = startThread(patient, "Finished", "This is resolved now.");
        String id = thread.get("id").asString();

        mockMvc.perform(post("/notifications/messages/" + id + "/close").with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        mockMvc.perform(post("/portal/messages/" + id + "/replies").with(asPatient(patient))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", "One more thing"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("start a new one")));

        mockMvc.perform(post("/notifications/messages/" + id + "/replies").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", "And another"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a patient cannot close a conversation the hospital is still answering")
    void patientsCannotClose() throws Exception {
        JsonNode thread = startThread(patient, "Ongoing", "Still waiting.");
        // Closing is staff-only, which looks like an omission and is not: a thread the patient
        // closed would leave the hospital unable to add the answer it was still writing.
        mockMvc.perform(post("/notifications/messages/" + thread.get("id").asString() + "/close")
                        .with(asPatient(patient)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("every thread carries the notice that this is not the way to reach anybody urgently")
    void theEmergencyNoticeIsOnEveryThread() throws Exception {
        JsonNode thread = startThread(patient, "Chest", "I have been feeling short of breath.");
        // The person who most needs to read this is the one already typing, and they reached the
        // reply box from an email rather than from the page that carried the warning.
        assertThat(thread.get("notice").asString())
                .contains("not monitored continuously")
                .contains("casualty");
    }

    @Test
    @DisplayName("the bench is not given the correspondence queue")
    void theQueueIsNotForEverybody() throws Exception {
        for (String role : List.of("LAB_TECH", "PATHOLOGIST", "PHARMACIST", "CASHIER")) {
            mockMvc.perform(get("/notifications/messages").with(as(role)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/notifications/messages").with(as("NURSE"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a patient is not given the hospital's queue, and staff are not given the portal")
    void neitherSideCanUseTheOther() throws Exception {
        mockMvc.perform(get("/notifications/messages").with(asPatient(patient)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/portal/messages").with(as("ADMIN")))
                .andExpect(status().isForbidden());
    }
}
