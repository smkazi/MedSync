package com.hms.interop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.interop.client.AbdmGateway;
import com.hms.interop.client.ClinicalDataClient;
import com.hms.interop.client.dto.ClinicalViews.DiagnosisView;
import com.hms.interop.client.dto.ClinicalViews.EncounterView;
import com.hms.interop.client.dto.ClinicalViews.LabOrderView;
import com.hms.interop.client.dto.ClinicalViews.NoteView;
import com.hms.interop.client.dto.ClinicalViews.PatientView;
import com.hms.interop.client.dto.ClinicalViews.VitalsView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * The consent gate, against a real database.
 *
 * <p>What is worth proving here rather than in the pure tests is everything about *whether data
 * moves*: that each of the four conditions refuses on its own, that a refusal happens before the
 * record is read at all, that a disclosure row is written in the same transaction as a release, and
 * that no role can reach round any of it.
 *
 * <p>The clinical services are stubbed because they are not running here — and the stub is also
 * the instrument for the assertion that matters most: {@code verify(clinical, never())} is how a
 * test proves that a refused share did not fetch the chart.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InteropApiIntegrationTest {

    @MockitoBean
    private ClinicalDataClient clinical;

    /** The default adapter, stubbed so a test can assert what was handed to it. */
    @MockitoBean
    private AbdmGateway gateway;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID patientId;
    private String mrn;
    private UUID encounterId;

    @BeforeEach
    void aPatientAndAnEncounter() {
        patientId = UUID.randomUUID();
        mrn = "MRN-INT-" + Math.abs(System.nanoTime() % 1_000_000);
        encounterId = UUID.randomUUID();

        when(clinical.patient(any(UUID.class), anyString())).thenReturn(
                new PatientView(patientId, mrn, "Asha", "Menon", LocalDate.of(1984, 3, 2),
                        "FEMALE", null, null, "A city", "A state", "India", true, false));
        when(clinical.encounter(any(UUID.class), anyString())).thenReturn(encounterView(
                Instant.now().minus(2, ChronoUnit.DAYS)));
        when(gateway.send(any(), anyString(), anyString()))
                .thenReturn(new AbdmGateway.Outcome(false, "LOG", "recorded locally"));
        when(gateway.name()).thenReturn("LOG");
    }

    private EncounterView encounterView(Instant startedAt) {
        return new EncounterView(encounterId, patientId, mrn, UUID.randomUUID(), "GEN",
                "OUTPATIENT", startedAt, startedAt.plus(1, ChronoUnit.HOURS), "CLOSED",
                List.of(new NoteView(UUID.randomUUID(), 1, "Sore throat", "Pharynx inflamed",
                        "Viral pharyngitis", "Fluids", "dr.rao", true, startedAt, "dr.rao")),
                List.of(new VitalsView(UUID.randomUUID(), startedAt, "nurse.iqbal", 88, 128, 82,
                        18, null, 97, null, null)),
                List.of(new DiagnosisView(UUID.randomUUID(), "J06.9", "URTI", "PRIMARY")));
    }

    // ---- the consent register ------------------------------------------------

    @Test
    @DisplayName("a consent is requested, granted, and reads as live")
    void consentLifecycle() throws Exception {
        JsonNode requested = requestConsent(Map.of());
        assertThat(requested.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(requested.get("live").asBoolean())
                .as("a pending request is not permission")
                .isFalse();
        assertThat(requested.get("artefactId").asString()).startsWith("LOCAL-");

        JsonNode granted = grant(requested.get("artefactId").asString());
        assertThat(granted.get("status").asString()).isEqualTo("GRANTED");
        assertThat(granted.get("live").asBoolean()).isTrue();
        assertThat(granted.get("grantedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("a consent cannot last longer than the deployment allows")
    void consentCannotBeOpenEnded() throws Exception {
        String refusal = mockMvc.perform(post("/consents").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(consentBody(Map.of(
                                "expiresAt", Instant.now().plus(400, ChronoUnit.DAYS).toString())))))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(refusal).contains("at most");
    }

    @Test
    @DisplayName("a revoked consent stays revoked, with its reason on the record")
    void revocationIsARecordNotADelete() throws Exception {
        String artefactId = grant(requestConsent(Map.of()).get("artefactId").asString())
                .get("artefactId").asString();

        JsonNode revoked = objectMapper.readTree(
                mockMvc.perform(post("/consents/" + artefactId + "/revoke").with(as("RECEPTIONIST"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("reason", "The patient changed their mind"))))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertThat(revoked.get("status").asString()).isEqualTo("REVOKED");
        assertThat(revoked.get("revokedReason").asString()).contains("changed their mind");
        assertThat(revoked.get("live").asBoolean()).isFalse();

        // Still readable afterwards: the question asked later is whether the sharing was lawful at
        // the time, and a deleted consent cannot answer it.
        assertThat(objectMapper.readTree(
                mockMvc.perform(get("/consents/" + artefactId).with(as("DOCTOR")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("revokedReason").asString()).isNotBlank();
    }

    // ---- the gate ------------------------------------------------------------

    @Test
    @DisplayName("a granted consent releases the record, and the disclosure is written with it")
    void aGrantedConsentReleases() throws Exception {
        String artefactId = grant(requestConsent(Map.of()).get("artefactId").asString())
                .get("artefactId").asString();

        JsonNode shared = share(artefactId, "OP_CONSULTATION", encounterId, 200);
        assertThat(shared.get("resourceCount").asInt()).isGreaterThan(3);
        assertThat(shared.get("transmitted").asBoolean())
                .as("the default adapter records and sends nothing, and says so rather than "
                        + "reporting a success that did not happen")
                .isFalse();
        assertThat(shared.get("gateway").asString()).isEqualTo("LOG");

        JsonNode disclosures = objectMapper.readTree(
                mockMvc.perform(get("/interop/disclosures?patientId=" + patientId)
                                .with(as("DOCTOR")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertThat(disclosures.size()).isEqualTo(1);
        assertThat(disclosures.get(0).get("kind").asString()).isEqualTo("CONSENTED_SHARE");
        assertThat(disclosures.get(0).get("artefactId").asString()).isEqualTo(artefactId);
        assertThat(disclosures.get(0).get("byteCount").asInt()).isGreaterThan(100);
    }

    @Test
    @DisplayName("a consent that has not been granted releases nothing, and reads no chart")
    void aPendingConsentReleasesNothing() throws Exception {
        String artefactId = requestConsent(Map.of()).get("artefactId").asString();

        String refusal = share(artefactId, "OP_CONSULTATION", encounterId, 409).toString();
        assertThat(refusal).contains("not yet granted");
        // The assertion that matters: the refusal happened before anything was fetched, so a
        // rejected request never pulled the record into this service at all.
        verify(clinical, never()).encounter(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("a revoked consent refuses, naming the withdrawal")
    void aRevokedConsentRefuses() throws Exception {
        String artefactId = grant(requestConsent(Map.of()).get("artefactId").asString())
                .get("artefactId").asString();
        mockMvc.perform(post("/consents/" + artefactId + "/revoke").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "Withdrawn"))))
                .andExpect(status().isOk());

        assertThat(share(artefactId, "OP_CONSULTATION", encounterId, 409).toString())
                .contains("revoked").contains("Withdrawn");
        verify(clinical, never()).encounter(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("a consent for one kind of record is not consent for another")
    void hiTypeIsChecked() throws Exception {
        String artefactId = grant(requestConsent(Map.of("hiTypes", List.of("DIAGNOSTIC_REPORT")))
                .get("artefactId").asString()).get("artefactId").asString();

        assertThat(share(artefactId, "OP_CONSULTATION", encounterId, 409).toString())
                .contains("does not cover")
                .contains("diagnostic report");
        verify(clinical, never()).encounter(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("a record outside the period the consent covers is refused")
    void coveredPeriodIsChecked() throws Exception {
        String artefactId = grant(requestConsent(Map.of(
                        "coversFrom", LocalDate.now().minusDays(1).toString(),
                        "coversTo", LocalDate.now().toString()))
                .get("artefactId").asString()).get("artefactId").asString();

        // The encounter is two days old, and the consent covers yesterday onwards.
        assertThat(share(artefactId, "OP_CONSULTATION", encounterId, 409).toString())
                .contains("covers records dated")
                .contains("is not the same as how long the consent lasts");
    }

    @Test
    @DisplayName("a consent about one patient is not a key to another's record")
    void consentIsAboutOnePatient() throws Exception {
        String artefactId = grant(requestConsent(Map.of()).get("artefactId").asString())
                .get("artefactId").asString();

        UUID somebodyElse = UUID.randomUUID();
        when(clinical.encounter(any(UUID.class), anyString())).thenReturn(new EncounterView(
                encounterId, somebodyElse, "MRN-OTHER", UUID.randomUUID(), "GEN", "OUTPATIENT",
                Instant.now().minus(2, ChronoUnit.DAYS), null, "CLOSED", List.of(), List.of(),
                List.of()));

        assertThat(share(artefactId, "OP_CONSULTATION", encounterId, 409).toString())
                .contains("belongs to somebody else");
    }

    @Test
    @DisplayName("an unverified laboratory order cannot be shared at all")
    void provisionalResultsDoNotLeave() throws Exception {
        String artefactId = grant(requestConsent(Map.of("hiTypes", List.of("DIAGNOSTIC_REPORT")))
                .get("artefactId").asString()).get("artefactId").asString();
        UUID orderId = UUID.randomUUID();
        when(clinical.labOrder(any(UUID.class), anyString())).thenReturn(new LabOrderView(orderId,
                patientId, mrn, "dr.rao", null, "ROUTINE", "RESULTED",
                Instant.now().minus(1, ChronoUnit.DAYS), List.of(), List.of()));

        assertThat(share(artefactId, "DIAGNOSTIC_REPORT", orderId, 409).toString())
                .contains("Only a verified, released report")
                .contains("what verification exists to prevent");
        verify(gateway, never()).send(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("nothing is sent when the consent refuses, not even to the log adapter")
    void refusalReachesNoGateway() throws Exception {
        String artefactId = requestConsent(Map.of()).get("artefactId").asString();
        share(artefactId, "OP_CONSULTATION", encounterId, 409);
        verify(gateway, never()).send(any(), anyString(), anyString());
    }

    // ---- export --------------------------------------------------------------

    @Test
    @DisplayName("an export is a searchset of documents, and is recorded as a disclosure")
    void exportIsRecorded() throws Exception {
        JsonNode export = objectMapper.readTree(
                mockMvc.perform(post("/interop/export/" + patientId
                                + "?encounterId=" + encounterId).with(as("ADMIN")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(export.get("resourceType").asString()).isEqualTo("Bundle");
        assertThat(export.get("type").asString()).isEqualTo("searchset");
        assertThat(export.get("total").asInt()).isEqualTo(1);

        JsonNode disclosures = objectMapper.readTree(
                mockMvc.perform(get("/interop/disclosures?patientId=" + patientId)
                                .with(as("ADMIN")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertThat(disclosures.get(0).get("kind").asString()).isEqualTo("PATIENT_EXPORT");
        assertThat(disclosures.get(0).get("consentId").isNull())
                .as("handing somebody their own record is not a disclosure to a third party, and "
                        + "there is no consent artefact behind it")
                .isTrue();
    }

    // ---- the accounting of disclosures ---------------------------------------

    @Test
    @DisplayName("the register can be asked for a period, and answers for that period only")
    void theRegisterIsBoundedByAPeriod() throws Exception {
        grantAndShare();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        // Inclusive of the whole of `to`: a release this afternoon must appear in a register asked
        // for up to today, not fall off the end of it.
        assertThat(register("&from=" + today + "&to=" + today).size()).isEqualTo(1);
        assertThat(register("&from=" + today.minusDays(7)).size()).isEqualTo(1);
        assertThat(register("&from=2020-01-01&to=2020-01-02").size()).isZero();
        assertThat(register("").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a patient reads their own accounting, and it does not name the member of staff")
    void aPatientReadsTheirOwnAccounting() throws Exception {
        String artefactId = grantAndShare();

        JsonNode mine = objectMapper.readTree(
                mockMvc.perform(get("/portal/records/disclosures").with(asPatient(patientId)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).get("artefactId").asString()).isEqualTo(artefactId);
        assertThat(mine.get(0).get("recipient").asString()).isNotBlank();

        // Structural, not a substring search: the decision is that these fields are absent, and
        // asserting on the rendered text would pass for the wrong reason the moment a recipient
        // name happened to contain one of them.
        List<String> fields = new ArrayList<>(mine.get(0).propertyNames());
        assertThat(fields)
                .as("the hospital released it and the hospital answers for it; naming the "
                        + "individual who clicked turns an accounting into a complaint about a person")
                .doesNotContain("releasedBy")
                .as("there is exactly one patient this can be about, so echoing their own "
                        + "identifiers back tells them nothing")
                .doesNotContain("patientId", "patientMrn")
                .contains("recipient", "hiType", "releasedAt", "resourceCount", "byteCount");
    }

    @Test
    @DisplayName("a patient's own download appears in their accounting, with no consent behind it")
    void anExportAppearsInThePatientsAccounting() throws Exception {
        mockMvc.perform(post("/interop/export/" + patientId + "?encounterId=" + encounterId)
                        .with(as("ADMIN")))
                .andExpect(status().isOk());

        JsonNode mine = objectMapper.readTree(
                mockMvc.perform(get("/portal/records/disclosures").with(asPatient(patientId)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        // The regression this guards, found by the API suite rather than here: when no row in the
        // page has a consent, the batch artefact lookup returns Map.of() -- which throws on a null
        // key instead of answering null. So a patient whose only disclosure was their own download
        // got a 500 from the endpoint built to reassure them.
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).get("kind").asString()).isEqualTo("PATIENT_EXPORT");
        assertThat(mine.get(0).get("artefactId").isNull())
                .as("handing somebody their own record needs no consent, and the register says so "
                        + "rather than inventing one")
                .isTrue();
    }

    @Test
    @DisplayName("another patient's session sees their own empty register, never this one's")
    void oneAccountingPerPatient() throws Exception {
        grantAndShare();

        JsonNode theirs = objectMapper.readTree(
                mockMvc.perform(get("/portal/records/disclosures")
                                .with(asPatient(UUID.randomUUID())))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        // Empty rather than refused, and that is the right answer: a patient with nothing released
        // about them has an empty accounting. There is no id in the request to tamper with, so
        // there is no 404-versus-403 question to get wrong here.
        assertThat(theirs).isEmpty();
    }

    @Test
    @DisplayName("staff cannot read the patient-facing register, and a patient cannot read the staff one")
    void neitherRegisterStandsInForTheOther() throws Exception {
        mockMvc.perform(get("/portal/records/disclosures").with(as("ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/portal/records/disclosures").with(as("DOCTOR")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/interop/disclosures?patientId=" + patientId).with(asPatient(patientId)))
                .andExpect(status().isForbidden());
    }

    // ---- who may do what -----------------------------------------------------

    @Test
    @DisplayName("recording a consent decision is the front desk's, and acting on it is not")
    void consentDecisionsAndSharesAreDifferentAuthorities() throws Exception {
        String artefactId = requestConsent(Map.of()).get("artefactId").asString();

        // A clinician cannot record the patient's decision — that would be authorising their own
        // access to the record they are about to send.
        mockMvc.perform(post("/consents/" + artefactId + "/grant").with(as("DOCTOR")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/consents").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(consentBody(Map.of()))))
                .andExpect(status().isForbidden());

        // And the front desk cannot decide which record is the one to send.
        grant(artefactId);
        mockMvc.perform(post("/interop/share").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "artefactId", artefactId, "hiType", "OP_CONSULTATION",
                                "recordId", encounterId))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("exporting a whole chart is an administrator's alone")
    void exportIsNarrow() throws Exception {
        mockMvc.perform(post("/interop/export/" + patientId).with(as("DOCTOR")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/interop/export/" + patientId).with(as("NURSE")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/interop/export/" + patientId).with(as("RECEPTIONIST")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the laboratory and the pharmacy see none of this")
    void benchAndPharmacyAreOut() throws Exception {
        mockMvc.perform(get("/consents").with(as("LAB_TECH"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/consents").with(as("PHARMACIST"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/interop/disclosures?patientId=" + patientId).with(as("LAB_TECH")))
                .andExpect(status().isForbidden());
    }

    // ---- helpers -------------------------------------------------------------

    /** The staff register for this test's patient, with any extra query string appended. */
    private JsonNode register(String extraQuery) throws Exception {
        return objectMapper.readTree(
                mockMvc.perform(get("/interop/disclosures?patientId=" + patientId + extraQuery)
                                .with(as("DOCTOR")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
    }

    /** Grants a consent and shares one record under it, returning the artefact id. */
    private String grantAndShare() throws Exception {
        String artefactId = grant(requestConsent(Map.of()).get("artefactId").asString())
                .get("artefactId").asString();
        share(artefactId, "OP_CONSULTATION", encounterId, 200);
        return artefactId;
    }

    /**
     * A portal session: the PATIENT role and, crucially, the signed {@code patient_id} claim.
     * Whose record it is comes from here and from nowhere in the request, which is why there is no
     * id in any {@code /portal} path to tamper with.
     */
    private static RequestPostProcessor asPatient(UUID patientId) {
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "mrn-test-patient")
                        .claim("patient_id", patientId.toString()))
                .authorities(List.of((GrantedAuthority) new SimpleGrantedAuthority("ROLE_PATIENT")));
    }

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user"))
                .authorities(authorities);
    }

    private Map<String, Object> consentBody(Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientId", patientId);
        body.put("patientMrn", mrn);
        body.put("requester", "A referring clinic");
        body.put("purposeCode", "CARE_MANAGEMENT");
        body.put("hiTypes", List.of("OP_CONSULTATION", "PRESCRIPTION"));
        body.put("coversFrom", LocalDate.now().minusYears(1).toString());
        body.put("coversTo", LocalDate.now().toString());
        body.put("expiresAt", Instant.now().plus(30, ChronoUnit.DAYS).toString());
        body.putAll(overrides);
        return body;
    }

    private JsonNode requestConsent(Map<String, Object> overrides) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/consents").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(consentBody(overrides))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode grant(String artefactId) throws Exception {
        return objectMapper.readTree(
                mockMvc.perform(post("/consents/" + artefactId + "/grant").with(as("RECEPTIONIST")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
    }

    private JsonNode share(String artefactId, String hiType, UUID recordId, int expected)
            throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/interop/share").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "artefactId", artefactId, "hiType", hiType,
                                "recordId", recordId))))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString());
    }
}
