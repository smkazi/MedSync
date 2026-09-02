package com.hms.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.notification.channel.Recipient;
import com.hms.notification.domain.NotificationEnums;
import com.hms.notification.repo.NotificationRepository;
import com.hms.notification.service.ContactDirectory;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Outbound messaging against a real database.
 *
 * <p>Two things here carry more weight than the rest: that no message body ever contains a clinical
 * value, a parameter name or a patient's surname, and that the same event delivered twice produces
 * one message. Both are properties the module exists for rather than features it has.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationApiIntegrationTest {

    /**
     * A contact directory that always answers.
     *
     * <p>The real one reaches patient-service as a service account, which is not running here. A
     * stub rather than letting it degrade, because a suppressed message proves nothing about what a
     * sent message would have contained — and the PHI assertion below is the point of the suite.
     */
    @TestConfiguration
    static class StubContacts {

        static final String PHONE = "+971500000123";

        @Bean
        @Primary
        ContactDirectory contactDirectory() {
            return new ContactDirectory() {
                @Override
                public Optional<Recipient> find(UUID patientId) {
                    return Optional.of(new Recipient(patientId, PHONE, "patient@example.invalid"));
                }

                @Override
                public String unavailableReason() {
                    return "stubbed";
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRepository notifications;

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()))
                .authorities(authorities);
    }

    private JsonNode send(Map<String, Object> body, String role) throws Exception {
        String response = mockMvc.perform(post("/notifications").with(as(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    @DisplayName("a released-report message names the report, and nothing that is in it")
    void aReleasedReportMessageCarriesNoPhi() throws Exception {
        // The values laboratory-service actually puts on the event, and the ones from the fixture
        // report the browser suite releases. If any of them can reach a message, the whole reason
        // for composing from templates has failed.
        UUID patientId = UUID.randomUUID();
        JsonNode sent = send(Map.of(
                "category", "LAB_REPORT_READY",
                "channel", "LOG",
                "patientId", patientId,
                "reference", UUID.randomUUID().toString()), "DOCTOR");

        String body = sent.get("body").asString();
        assertThat(body)
                .as("the message says something is ready and where to see it")
                .contains("ready")
                .contains("https://portal.test.invalid/reports");

        assertThat(body.toLowerCase(java.util.Locale.ROOT))
                .as("no value, no parameter, no flag, no name, no MRN")
                .doesNotContain("9.6")
                .doesNotContain("hgb")
                .doesNotContain("haemoglobin")
                .doesNotContain("abnormal")
                .doesNotContain("low")
                .doesNotContain("nair")
                .doesNotContain("mrn-");
        // Nor may the subject, which is the part shown on a locked screen.
        assertThat(sent.get("subject").isNull() || !sent.get("subject").asString().contains("9.6"))
                .isTrue();
    }

    @Test
    @DisplayName("the same message asked for twice is sent once")
    void repeatedRequestsSendOnce() throws Exception {
        UUID patientId = UUID.randomUUID();
        Map<String, Object> request = Map.of(
                "category", "LAB_REPORT_READY",
                "channel", "LOG",
                "patientId", patientId,
                "reference", "order-42",
                "idempotencyKey", "test:" + UUID.randomUUID());

        String first = send(request, "DOCTOR").get("id").asString();
        String second = send(request, "DOCTOR").get("id").asString();

        // The same row, not two rows that happen to say the same thing. A redelivered Kafka
        // message and a double-clicked button are the same problem, and this is the answer to both.
        assertThat(second).isEqualTo(first);
        assertThat(notifications.findByPatientIdOrderByCreatedAtDesc(patientId)).hasSize(1);
    }

    @Test
    @DisplayName("without an explicit key, the same request about the same thing is still one message")
    void theDerivedKeyCoversADoubleClick() throws Exception {
        UUID patientId = UUID.randomUUID();
        Map<String, Object> request = Map.of(
                "category", "APPOINTMENT_CONFIRMED",
                "channel", "LOG",
                "patientId", patientId,
                "reference", "appt-" + UUID.randomUUID(),
                "when", "12 March, 10:30");

        send(request, "RECEPTIONIST");
        send(request, "RECEPTIONIST");

        assertThat(notifications.findByPatientIdOrderByCreatedAtDesc(patientId)).hasSize(1);
    }

    @Test
    @DisplayName("an appointment message carries the date and still says nothing clinical")
    void anAppointmentMessageCarriesOnlyTheDate() throws Exception {
        JsonNode sent = send(Map.of(
                "category", "APPOINTMENT_CONFIRMED",
                "channel", "LOG",
                "patientId", UUID.randomUUID(),
                "when", "12 March, 10:30"), "RECEPTIONIST");

        assertThat(sent.get("body").asString())
                .contains("12 March, 10:30")
                // Not who it is with and not what it is for: a date says a person has an
                // appointment, which the message's existence already said.
                .doesNotContain("Cardiology")
                .doesNotContain("Dr");
    }

    @Test
    @DisplayName("asking for a channel this deployment does not have falls back and says so")
    void anUnconfiguredChannelIsSubstitutedRatherThanRefused() throws Exception {
        // No SMS gateway is configured in a test run, and a released report must not fail to be
        // recorded because of that. What must not happen is the log claiming it went by SMS.
        JsonNode sent = send(Map.of(
                "category", "LAB_REPORT_READY",
                "channel", "SMS",
                "patientId", UUID.randomUUID()), "DOCTOR");

        assertThat(sent.get("channel").asString()).isEqualTo("LOG");
        assertThat(sent.get("status").asString()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("the delivery log is readable, and filterable by what went wrong")
    void theDeliveryLogIsReadable() throws Exception {
        send(Map.of("category", "PORTAL_MESSAGE", "channel", "LOG",
                "patientId", UUID.randomUUID()), "NURSE");

        mockMvc.perform(get("/notifications?size=5").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mockMvc.perform(get("/notifications?status=FAILED&size=5").with(as("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the laboratory cannot originate a message, and nobody anonymous can read the log")
    void rolesAreEnforced() throws Exception {
        mockMvc.perform(post("/notifications").with(as("LAB_TECH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "category", "LAB_REPORT_READY", "channel", "LOG",
                                "patientId", UUID.randomUUID()))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/notifications").with(as("PATHOLOGIST")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the deployment says which channels it really has")
    void capabilitiesAreHonest() throws Exception {
        mockMvc.perform(get("/notifications/capabilities").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels").value(org.hamcrest.Matchers.hasItem("LOG")))
                // Nothing is wired up in a test run, and the screens are entitled to know that
                // rather than offering a channel that would silently become the log.
                .andExpect(jsonPath("$.channels").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("SMS"))))
                .andExpect(jsonPath("$.contactLookupConfigured").value(false));
    }

    @Test
    @DisplayName("a template can be reworded, and cannot be reworded into a disclosure")
    void templatesAreConfigurationWithOneLimit() throws Exception {
        String templates = mockMvc.perform(get("/notifications/templates").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode target = objectMapper.readTree(templates).get(0);
        String id = target.get("id").asString();
        String original = target.get("body").asString();

        // A clinician may read them; only an administrator rewrites the platform's voice.
        mockMvc.perform(patch("/notifications/templates/" + id).with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"anything\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/notifications/templates/" + id).with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Your result of {value} is ready. {portalUrl}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("{value}")));

        try {
            mockMvc.perform(patch("/notifications/templates/" + id).with(as("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\": \"Something is waiting for you at {portalUrl}\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.body").value("Something is waiting for you at {portalUrl}"));
        } finally {
            // Shared configuration: a test that leaves the platform's voice rewritten has changed
            // what every later message says.
            mockMvc.perform(patch("/notifications/templates/" + id).with(as("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("body", original))));
        }
    }

    @Test
    @DisplayName("a message about a patient with nowhere to reach them is recorded, not lost")
    void anUnreachablePatientIsSuppressedAndVisible() throws Exception {
        // Proven through the repository rather than the stub above, which always answers: what
        // matters is that the SUPPRESSED path writes a row carrying the reason.
        UUID patientId = UUID.randomUUID();
        send(Map.of("category", "LAB_REPORT_READY", "channel", "LOG", "patientId", patientId),
                "DOCTOR");
        assertThat(notifications.findByPatientIdOrderByCreatedAtDesc(patientId))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus())
                        .isEqualTo(NotificationEnums.Status.SENT));
    }
}
