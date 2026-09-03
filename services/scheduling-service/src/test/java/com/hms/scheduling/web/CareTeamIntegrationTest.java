package com.hms.scheduling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.common.audit.AuditSink;
import com.hms.common.error.BadRequestException;
import com.hms.common.events.DomainEvent;
import com.hms.scheduling.client.StaffDirectoryClient;
import com.hms.scheduling.domain.CareTeamMember;
import com.hms.scheduling.repo.CareTeamRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
 * The care-team narrowing, and the glass over it.
 *
 * <p>These are the tests that demonstrate the control exists at all. The rest of the scheduling
 * suite exercises the paths a clinician takes on their own patients and passes unchanged, which is
 * the point — but a suite where every test passes both before and after a security change proves
 * nothing about the change. So this file is deliberately about the negative case and its escape
 * hatch: a second clinician, holding the identical role, refused; and the same clinician admitted
 * once they have said why.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CareTeamIntegrationTest.CapturingAudit.class)
class CareTeamIntegrationTest {

    /**
     * Captures the audit events this service emits.
     *
     * <p>Needed because they cannot be read back through {@code /admin/audit} from here, or from
     * the API suite either: an audit event crosses to identity-service over the event topic, and
     * neither the local stack nor CI runs a broker — the transport is {@code log}. identity's own
     * security events are persisted by a sink it registers for itself, which is why sign-ins show
     * up on the report and a scheduling event does not. So the assertion that matters here — that
     * a break-glass reason never reaches the audit trail — is made where the event is produced,
     * on the payload, rather than at a table it does not reach in this configuration.
     */
    @TestConfiguration
    static class CapturingAudit {
        static final List<DomainEvent> EVENTS = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Bean
        AuditSink capturingSink() {
            return EVENTS::add;
        }
    }

    /** The clinician who will treat the patient, and open the encounter. */
    private static final UUID TREATING_DOCTOR = UUID.randomUUID();

    /** A doctor with exactly the same role and nothing to do with this patient. */
    private static final UUID OTHER_DOCTOR = UUID.randomUUID();

    private static final UUID WARD_NURSE = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CareTeamRepository careTeam;

    @MockitoBean
    private StaffDirectoryClient staffDirectory;

    @BeforeEach
    void everyIdIsAClinician() {
        Mockito.when(staffDirectory.require(any(UUID.class), nullable(String.class)))
                .thenAnswer(call -> new StaffDirectoryClient.Clinician(
                        call.getArgument(0), "Test Clinician", "Consultant", "GEN"));
    }

    private static RequestPostProcessor as(UUID subject, String... roles) {
        List<GrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder.subject(subject.toString())
                        .claim("preferred_username", "user-" + subject.toString().substring(0, 8))
                        .claim("roles", List.of(roles)))
                .authorities(authorities);
    }

    /** A standalone encounter, opened by the treating doctor. */
    private String openEncounter() throws Exception {
        Map<String, Object> body = Map.of(
                "patientId", UUID.randomUUID().toString(),
                "patientMrn", "MRN-CT-" + UUID.randomUUID().toString().substring(0, 8),
                "clinicianId", TREATING_DOCTOR.toString(),
                "departmentCode", "GEN");
        String response = mockMvc.perform(post("/encounters").with(as(TREATING_DOCTOR, "DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asString();
    }

    @Test
    @DisplayName("the treating clinician reads their own chart, and nothing about their day changed")
    void theTreatingClinicianIsAlreadyOnTheTeam() throws Exception {
        String encounterId = openEncounter();

        mockMvc.perform(get("/encounters/" + encounterId).with(as(TREATING_DOCTOR, "DOCTOR")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/encounters/" + encounterId + "/note/history")
                        .with(as(TREATING_DOCTOR, "DOCTOR")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a different doctor, holding the identical role, is refused in words that say what to do")
    void anotherDoctorIsRefused() throws Exception {
        String encounterId = openEncounter();

        String refusal = mockMvc.perform(get("/encounters/" + encounterId)
                        .with(as(OTHER_DOCTOR, "DOCTOR")))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        // 403 with a reason, not 404. The deliberate opposite of the portal's rule, where "not
        // yours" would confirm a guessed patient id is real: this caller is a clinician who can
        // already list patients, so there is nothing to enumerate and an answer that says what to
        // do next is worth more than one that pretends the encounter is not there.
        assertThat(objectMapper.readTree(refusal).get("detail").asString())
                .contains("not on this encounter's care team")
                .contains("record a reason");
    }

    @Test
    @DisplayName("break-glass opens the chart, records why, and lapses")
    void breakGlassOpensItAndLapses() throws Exception {
        String encounterId = openEncounter();
        String reason = "Covering the evening ward round while Dr Rao is in theatre.";

        String joined = mockMvc.perform(post("/encounters/" + encounterId + "/care-team")
                        .with(as(OTHER_DOCTOR, "DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", reason))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode membership = objectMapper.readTree(joined);
        assertThat(membership.get("memberRole").asString()).isEqualTo("BREAK_GLASS");
        assertThat(membership.get("reason").asString()).isEqualTo(reason);
        assertThat(membership.get("expiresAt").isNull())
                .as("cover for one evening must not become standing access for ever")
                .isFalse();

        mockMvc.perform(get("/encounters/" + encounterId).with(as(OTHER_DOCTOR, "DOCTOR")))
                .andExpect(status().isOk());

        // The reason is on the row, in the clinical schema, and it is the only place it lives:
        // audit `detail` must never carry clinical free text, and "query sepsis, unresponsive" is
        // a clinical observation. Whoever reviews a break-glass reads both halves.
        assertThat(careTeam.findByEncounterIdOrderByJoinedAtDesc(UUID.fromString(encounterId)))
                .anySatisfy(member -> {
                    assertThat(member.getUserId()).isEqualTo(OTHER_DOCTOR);
                    assertThat(member.getReason()).isEqualTo(reason);
                });

        // ...and the audit trail records that somebody broke the glass, without the words. The
        // platform's own rule is that audit detail must never carry clinical free text, and
        // "short of breath" or "query sepsis" is a clinical observation whoever wrote it.
        DomainEvent audited = CapturingAudit.EVENTS.stream()
                .filter(event -> "CHART_BREAK_GLASS".equals(event.payload().get("action")))
                .filter(event -> encounterId.equals(String.valueOf(event.payload().get("entityId"))))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no CHART_BREAK_GLASS audit event was emitted"));
        assertThat(String.valueOf(audited.payload().get("detail")))
                .as("the reason lives on the care-team row, in the clinical schema, and nowhere else")
                .doesNotContain("Covering")
                .doesNotContain("theatre")
                .contains("care team joined");

        // And it lapses. Aged by hand rather than by binding the TTL to zero in a second Spring
        // context: what is under test is that an expired row is treated as absent, not the plumbing
        // that reads a duration out of a property file.
        CareTeamMember cover = careTeam.findByEncounterIdOrderByJoinedAtDesc(UUID.fromString(encounterId))
                .stream().filter(member -> member.getUserId().equals(OTHER_DOCTOR)).findFirst()
                .orElseThrow();
        careTeam.delete(cover);
        careTeam.save(expired(UUID.fromString(encounterId), OTHER_DOCTOR, reason));

        mockMvc.perform(get("/encounters/" + encounterId).with(as(OTHER_DOCTOR, "DOCTOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a reason too short to act on is refused")
    void aReasonHasToBeASentence() throws Exception {
        String encounterId = openEncounter();

        // "cover" and "emergency" are what a free-text box collects when it does not ask for a
        // sentence, and a reason nobody can act on is the same as no reason at all.
        mockMvc.perform(post("/encounters/" + encounterId + "/care-team")
                        .with(as(OTHER_DOCTOR, "DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "cover"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/encounters/" + encounterId).with(as(OTHER_DOCTOR, "DOCTOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("recording observations puts the nurse on the team rather than refusing them")
    void providingCareEnrols() throws Exception {
        String encounterId = openEncounter();

        // The asymmetry, and the reason for it: a nurse appears in encounters.clinician_id nowhere,
        // so a symmetric rule would have every nurse recording a reason for every patient they were
        // sent to obs -- and a control everybody trips over every hour is one everybody clicks
        // through. Reading is narrowed; providing care enrols you.
        mockMvc.perform(get("/encounters/" + encounterId).with(as(WARD_NURSE, "NURSE")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/encounters/" + encounterId + "/vitals").with(as(WARD_NURSE, "NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "heartRate", 78, "systolicBp", 122, "diastolicBp", 78,
                                "respiratoryRate", 16, "temperatureC", 36.8,
                                "oxygenSaturation", 98, "consciousness", "ALERT"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/encounters/" + encounterId).with(as(WARD_NURSE, "NURSE")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/encounters/" + encounterId + "/care-team")
                        .with(as(WARD_NURSE, "NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.memberRole == 'PROVIDED_CARE')]").exists());
    }

    @Test
    @DisplayName("administrators and the service lines are not narrowed at all")
    void whoIsNotNarrowed() throws Exception {
        String encounterId = openEncounter();

        // Stated as a test because it is a decision somebody could reasonably disagree with, and
        // the README says so out loud: narrowing the account that repairs the platform is a
        // different decision, and reporting a specimen is inherently cross-patient work that a
        // care-relationship model does not describe.
        mockMvc.perform(get("/encounters/" + encounterId).with(as(UUID.randomUUID(), "ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/encounters/" + encounterId).with(as(UUID.randomUUID(), "PATHOLOGIST")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a clinician id the staff directory does not know refuses the encounter")
    void anUnknownClinicianIsRefused() throws Exception {
        Mockito.when(staffDirectory.require(any(UUID.class), nullable(String.class)))
                .thenThrow(new BadRequestException("No clinician on the staff directory holds login"));

        // The column decides who may read the chart, so an id nobody validated would be a care
        // relationship the platform invented. Fails closed, before anything is written.
        mockMvc.perform(post("/encounters").with(as(TREATING_DOCTOR, "DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-CT-UNKNOWN",
                                "clinicianId", UUID.randomUUID().toString(),
                                "departmentCode", "GEN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the index of a patient's visits stays readable, because break-glass depends on it")
    void theIndexIsNotNarrowed() throws Exception {
        String encounterId = openEncounter();
        JsonNode encounter = objectMapper.readTree(
                mockMvc.perform(get("/encounters/" + encounterId).with(as(TREATING_DOCTOR, "DOCTOR")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        String patientId = encounter.get("patientId").asString();

        // Dates, types and counts — no assessment, no note, no vital sign. Hiding from a clinician
        // that four earlier visits exist is worse medicine than showing that they do, and it would
        // break break-glass itself, which depends on somebody being able to see there is something
        // to ask for.
        mockMvc.perform(get("/encounters/patients/" + patientId).with(as(OTHER_DOCTOR, "DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(encounterId));
    }

    // ---- the patient-level question -------------------------------------------

    @Test
    @DisplayName("looking after one of a patient's encounters answers for the whole record")
    void careTeamMembershipAnswersThePatientLevelQuestion() throws Exception {
        String encounterId = openEncounter();
        String patientId = objectMapper.readTree(
                mockMvc.perform(get("/encounters/" + encounterId).with(as(TREATING_DOCTOR, "DOCTOR")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("patientId").asString();

        // The treating clinician is on the encounter's team, and that is what entitles them to the
        // rest of the patient's record -- their laboratory orders, their prescriptions -- without a
        // second table saying the same thing in different words.
        assertThat(related(patientId, TREATING_DOCTOR)).isTrue();
        assertThat(related(patientId, OTHER_DOCTOR)).isFalse();

        // Not narrowed, so the answer is yes and that is the rule rather than a bypass.
        assertThat(related(patientId, UUID.randomUUID(), "ADMIN")).isTrue();
        assertThat(related(patientId, UUID.randomUUID(), "PATHOLOGIST")).isTrue();
    }

    @Test
    @DisplayName("break-glass on a patient opens the record, says why, and expires")
    void patientBreakGlassOpensTheRecord() throws Exception {
        String encounterId = openEncounter();
        String patientId = objectMapper.readTree(
                mockMvc.perform(get("/encounters/" + encounterId).with(as(TREATING_DOCTOR, "DOCTOR")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("patientId").asString();

        assertThat(related(patientId, OTHER_DOCTOR)).isFalse();

        // "cover" and "emergency" are what a free-text box collects when it does not ask for a
        // sentence, and a reason nobody can act on is the same as no reason.
        mockMvc.perform(post("/care-relationships/" + patientId + "/break-glass")
                        .with(as(OTHER_DOCTOR, "DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "cover"))))
                .andExpect(status().isBadRequest());

        String reason = "Covering the night ward; need the blood results before prescribing.";
        JsonNode grant = objectMapper.readTree(
                mockMvc.perform(post("/care-relationships/" + patientId + "/break-glass")
                                .with(as(OTHER_DOCTOR, "DOCTOR"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("reason", reason))))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString());

        assertThat(grant.get("reason").asString()).isEqualTo(reason);
        // Never null, unlike the encounter table's expiry: a standing relationship comes from
        // looking after somebody, and an exception that never lapsed would be the standing access
        // this whole mechanism exists to stop.
        assertThat(grant.get("expiresAt").isNull()).isFalse();
        assertThat(related(patientId, OTHER_DOCTOR)).isTrue();

        // And the reason is on the row, not in the audit detail. That is the platform's own rule:
        // audit detail never carries clinical free text, and "query sepsis" is a clinical
        // observation.
        assertThat(CapturingAudit.EVENTS.stream()
                .filter(event -> "CHART_BREAK_GLASS".equals(event.payload().get("action")))
                .noneMatch(event -> String.valueOf(event.payload().get("detail")).contains(reason)))
                .isTrue();
    }

    @Test
    @DisplayName("the service lines have no glass to break, because they were never narrowed")
    void theServiceLinesCannotBreakGlass() throws Exception {
        String patientId = UUID.randomUUID().toString();
        mockMvc.perform(post("/care-relationships/" + patientId + "/break-glass")
                        .with(as(UUID.randomUUID(), "PATHOLOGIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("reason", "Reporting a specimen for this patient today."))))
                .andExpect(status().isForbidden());
    }

    /** What scheduling-service answers when another service asks on a clinician's behalf. */
    private boolean related(String patientId, UUID user, String... roles) throws Exception {
        String[] asRoles = roles.length == 0 ? new String[] {"DOCTOR"} : roles;
        return objectMapper.readTree(
                mockMvc.perform(get("/care-relationships/" + patientId).with(as(user, asRoles)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("related").asBoolean();
    }

    private static CareTeamMember expired(UUID encounterId, UUID userId, String reason) {
        CareTeamMember lapsed = CareTeamMember.breakGlass(encounterId, userId, reason,
                Instant.now().minus(1, ChronoUnit.HOURS));
        return lapsed;
    }
}
