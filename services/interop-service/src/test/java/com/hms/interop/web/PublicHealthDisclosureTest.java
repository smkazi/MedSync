package com.hms.interop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
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
 * The fourth kind of disclosure: a notifiable-disease line list.
 *
 * <p>Two halves, and the second is the one worth reading. {@link TheEndpoint} proves that recording
 * one works and that only an administrator can; {@link TheDatabaseRefuses} goes round the
 * application with {@code JdbcTemplate} and proves the two CHECK constraints, because a service
 * method is a promise and a constraint is a guarantee. The migration's comment claims the register
 * cannot be made to say a patient consented to a statutory notification — that claim is only worth
 * anything if something tries.
 *
 * <p>Includes {@link TheDatabaseRefuses#aConsentedShareStillNeedsItsConsent()} on purpose: the new
 * constraint is one half of a pair, and a schema that had lost the older half would satisfy every
 * other assertion in this file.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicHealthDisclosureTest {

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
    }

    private static RequestPostProcessor as(String username, String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt()
                .jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", username)
                        .claim("roles", List.of(roles)))
                .authorities(authorities);
    }

    private static String body(String recipient, UUID... patientIds) {
        StringBuilder subjects = new StringBuilder();
        for (UUID patientId : patientIds) {
            if (!subjects.isEmpty()) {
                subjects.append(',');
            }
            subjects.append("""
                    {"patientId":"%s","patientMrn":"MRN-PH-%s","rowCount":1}"""
                    .formatted(patientId, patientId.toString().substring(0, 8)));
        }
        return "{\"recipient\":\"%s\",\"subjects\":[%s]}".formatted(recipient, subjects);
    }

    // ---- the endpoint --------------------------------------------------------

    @Nested
    @DisplayName("the endpoint")
    class TheEndpoint {

        @Test
        @DisplayName("writes one row per patient, so each of them can see theirs")
        void oneRowPerPatient() throws Exception {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();

            JsonNode response = objectMapper.readTree(
                    mockMvc.perform(post("/interop/disclosures")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body("A district authority", first, second))
                                    .with(as("admin", "ADMIN")))
                            .andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString());

            assertThat(response.get("patients").asInt()).isEqualTo(2);
            assertThat(response.get("disclosureIds").size()).isEqualTo(2);
            // A run-level row would need a fabricated patient id and would be invisible to every
            // patient on the list -- which is the one question the register exists to answer.
            for (UUID patientId : List.of(first, second)) {
                JsonNode register = objectMapper.readTree(
                        mockMvc.perform(get("/interop/disclosures")
                                        .param("patientId", patientId.toString())
                                        .with(as("admin", "ADMIN")))
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString());
                assertThat(register.size()).isEqualTo(1);
                assertThat(register.get(0).get("kind").asString()).isEqualTo("PUBLIC_HEALTH_REPORT");
                assertThat(register.get(0).get("recipient").asString())
                        .isEqualTo("A district authority");
                // Notification is compelled by law and needs no permission. A row naming a consent
                // would tell this patient, through the portal, that they agreed to something they
                // were never asked about -- worse than an incomplete record, because it is a false
                // one.
                assertThat(register.get(0).get("consentId").isNull()).isTrue();
                assertThat(register.get(0).get("artefactId").isNull()).isTrue();
            }
        }

        @Test
        @DisplayName("releasedBy comes from the token and there is nowhere in the body to put it")
        void theReleaserIsTheCaller() throws Exception {
            UUID patientId = UUID.randomUUID();

            mockMvc.perform(post("/interop/disclosures")
                            .contentType(MediaType.APPLICATION_JSON)
                            // A releasedBy in the body, which the request record has no component
                            // for: Jackson drops it, and the register records who actually called.
                            .content(("{\"recipient\":\"A district authority\","
                                    + "\"releasedBy\":\"somebody.else\",\"subjects\":"
                                    + "[{\"patientId\":\"%s\",\"patientMrn\":\"MRN-PH-1\","
                                    + "\"rowCount\":3}]}").formatted(patientId))
                            .with(as("the.administrator", "ADMIN")))
                    .andExpect(status().isCreated());

            JsonNode row = objectMapper.readTree(
                    mockMvc.perform(get("/interop/disclosures")
                                    .param("patientId", patientId.toString())
                                    .with(as("admin", "ADMIN")))
                            .andReturn().getResponse().getContentAsString()).get(0);

            // The whole value of this register is that the name in it is the person who did it.
            assertThat(row.get("releasedBy").asString()).isEqualTo("the.administrator");
            assertThat(row.get("resourceCount").asInt()).isEqualTo(3);
            // Nothing was built and nothing was sent: this endpoint records a disclosure the
            // caller is about to make.
            assertThat(row.get("byteCount").asInt()).isZero();
        }

        @Test
        @DisplayName("only an administrator may register one — the epidemiologist most pointedly not")
        void theGateIsNarrow() throws Exception {
            String request = body("A district authority", UUID.randomUUID());

            // EPIDEMIOLOGIST is the interesting refusal. It holds the whole surveillance surface
            // and is outside the care-team narrowing (isNarrowed() is allow-list shaped), which is
            // safe exactly as long as it holds no per-patient endpoint. This is one of the rows
            // that keeps it that way.
            for (String role : List.of("EPIDEMIOLOGIST", "DOCTOR", "NURSE", "RECEPTIONIST",
                    "LAB_TECH", "PATHOLOGIST", "PHARMACIST", "CASHIER")) {
                mockMvc.perform(post("/interop/disclosures")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request)
                                .with(as("someone", role)))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("a disclosure of nobody, or to nobody, is refused rather than recorded")
        void theRequestIsValidated() throws Exception {
            // An empty subject list would write no rows and answer 201, which reads to the caller
            // as "registered" -- and the caller produces its file on that answer.
            mockMvc.perform(post("/interop/disclosures")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"recipient\":\"A district authority\",\"subjects\":[]}")
                            .with(as("admin", "ADMIN")))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(post("/interop/disclosures")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("  ", UUID.randomUUID()))
                            .with(as("admin", "ADMIN")))
                    .andExpect(status().isBadRequest());

            // And the element constraints are live, which they are only because the list component
            // carries @Valid -- the hole S1e found in the laboratory's results batch.
            mockMvc.perform(post("/interop/disclosures")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(("{\"recipient\":\"A district authority\",\"subjects\":"
                                    + "[{\"patientId\":\"%s\",\"patientMrn\":\"\",\"rowCount\":0}]}")
                                    .formatted(UUID.randomUUID()))
                            .with(as("admin", "ADMIN")))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---- the database refuses -------------------------------------------------

    @Nested
    @DisplayName("the database refuses")
    class TheDatabaseRefuses {

        private void insert(String kind, String consentId) {
            jdbc.update("""
                    insert into interop.disclosures
                        (id, consent_id, patient_id, patient_mrn, hi_type, kind, recipient,
                         resource_count, byte_count, released_by)
                    values (gen_random_uuid(), %s, gen_random_uuid(), 'MRN-CHK-1',
                            'OP_CONSULTATION', ?, 'A district authority', 1, 0, 'test')
                    """.formatted(consentId == null ? "null" : "'" + consentId + "'"), kind);
        }

        /** A granted consent to hang the negative case off: consent_id has a foreign key. */
        private UUID aConsent() {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into interop.consent_artefacts
                        (id, artefact_id, patient_id, patient_mrn, requester, purpose_code,
                         status, covers_from, covers_to, expires_at, granted_at)
                    values (?, ?, gen_random_uuid(), 'MRN-CHK-1', 'A requester', 'CAREMGT',
                            'GRANTED', current_date - 30, current_date,
                            now() + interval '30 days', now())
                    """, id, "ART-" + id.toString().substring(0, 8));
            return id;
        }

        @Test
        @DisplayName("a public-health report carrying a consent, which is the whole point")
        void aStatutoryNotificationCannotClaimConsent() {
            UUID consentId = aConsent();

            // The mirror of chk_share_names_a_consent, in the opposite direction. A consented share
            // cannot exist WITHOUT a consent; a public-health report cannot exist WITH one.
            assertThatThrownBy(() -> insert("PUBLIC_HEALTH_REPORT", consentId.toString()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_public_health_has_no_consent");
        }

        @Test
        @DisplayName("a consented share still needs its consent, so both halves of the pair hold")
        void aConsentedShareStillNeedsItsConsent() {
            // Here so this file cannot pass on a schema that has lost the older constraint: a
            // database refusing everything would satisfy every other assertion above.
            assertThatThrownBy(() -> insert("CONSENTED_SHARE", null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_share_names_a_consent");
        }

        @Test
        @DisplayName("a kind nobody implemented")
        void anUnknownKindIsRefused() {
            assertThatThrownBy(() -> insert("IMMUNIZATION_RECORD", null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_disclosure_kind");
        }

        @Test
        @DisplayName("and the well-formed shape is accepted, or the rest of this proves nothing")
        void theWellFormedShapeIsAccepted() {
            insert("PUBLIC_HEALTH_REPORT", null);
            // Twenty-one characters into a column that held twenty until the migration widened it.
            // A CHECK naming a value the column cannot store passes its own migration and fails
            // every insert, so this row is the one that proves the widening happened.
            assertThat(jdbc.queryForObject("""
                    select count(*) from interop.disclosures where kind = 'PUBLIC_HEALTH_REPORT'
                    """, Integer.class)).isPositive();
        }
    }
}
