package com.hms.interop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.interop.client.PortalClinicalClient;
import com.hms.interop.client.dto.ClinicalViews.DiagnosisView;
import com.hms.interop.client.dto.ClinicalViews.EncounterView;
import com.hms.interop.client.dto.ClinicalViews.LabOrderItemView;
import com.hms.interop.client.dto.ClinicalViews.LabOrderView;
import com.hms.interop.client.dto.ClinicalViews.LabResultView;
import com.hms.interop.client.dto.ClinicalViews.NoteView;
import com.hms.interop.client.dto.ClinicalViews.PatientView;
import com.hms.interop.client.dto.ClinicalViews.PrescriptionItemView;
import com.hms.interop.client.dto.ClinicalViews.PrescriptionView;
import com.hms.interop.client.dto.ClinicalViews.VitalsView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Download and transmit: a patient exporting their own record.
 *
 * <p>The bundle is the same {@code searchset} the administrator-run export produces, built by the
 * same {@link com.hms.interop.service.FhirBundleBuilder}. What differs is where the contents come
 * from — the portal's own endpoints, called with the patient's own token — and this suite checks
 * the consequence: everything the portal itself would refuse to show is absent from the export
 * without a second rule being written to make it so.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalExportIntegrationTest {

    /** The portal fan-out, stubbed: what is under test is the assembly, not four HTTP hops. */
    @MockitoBean
    private PortalClinicalClient portal;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID patientId;
    private String mrn;

    @BeforeEach
    void aPatientWithARecord() {
        patientId = UUID.randomUUID();
        mrn = "MRN-EXP-" + Math.abs(System.nanoTime() % 1_000_000);
        Instant when = Instant.now().minus(3, ChronoUnit.DAYS);

        when(portal.me(anyString())).thenReturn(new PatientView(patientId, mrn, "Asha", "Menon",
                LocalDate.of(1984, 3, 2), "FEMALE", null, null, "A city", "A state", "India",
                true, false));
        when(portal.myEncounters(anyString())).thenReturn(List.of(new EncounterView(
                UUID.randomUUID(), patientId, mrn, UUID.randomUUID(), "GEN", "OUTPATIENT",
                when, when.plus(1, ChronoUnit.HOURS), "CLOSED",
                List.of(new NoteView(UUID.randomUUID(), 1, "Sore throat", "Pharynx inflamed",
                        "Viral pharyngitis", "Fluids", "dr.rao", true, when, "dr.rao")),
                List.of(new VitalsView(UUID.randomUUID(), when, "nurse.iqbal", 88, 128, 82, 18,
                        new BigDecimal("37.2"), 97, null, null)),
                List.of(new DiagnosisView(UUID.randomUUID(), "J06.9", "URTI", "PRIMARY")))));
        when(portal.myReleasedLabOrders(anyString())).thenReturn(List.of(new LabOrderView(
                UUID.randomUUID(), patientId, mrn, "dr.rao", null, "ROUTINE", "VERIFIED", when,
                List.of(new LabOrderItemView(UUID.randomUUID(), "CBC", "Complete Blood Count")),
                List.of(new LabResultView(UUID.randomUUID(), "HGB", "Haemoglobin", "9.4", "g/dL",
                        new BigDecimal("11.5"), new BigDecimal("14.5"), "11.5-14.5", "L", true,
                        "VERIFIED", "dr.pathan", when)))));
        when(portal.myPrescriptions(anyString())).thenReturn(List.of(new PrescriptionView(
                UUID.randomUUID(), null, patientId, mrn, "Dr Rao", "ACTIVE", when,
                List.of(new PrescriptionItemView(UUID.randomUUID(), "AMOX500", "Amoxicillin 500mg",
                        "500 mg", "TDS", 5, 15, "After food")))));
    }

    private static RequestPostProcessor asPatient(UUID patientId) {
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "MRN-EXP")
                        .claim("roles", List.of("PATIENT"))
                        .claim("patient_id", patientId.toString()))
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_PATIENT")));
    }

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user"))
                .authorities(authorities);
    }

    @Test
    @DisplayName("the export is a searchset of the patient's visits, results and prescriptions")
    void theExportIsASearchset() throws Exception {
        String body = mockMvc.perform(get("/portal/records/export").with(asPatient(patientId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("Bundle"))
                .andExpect(jsonPath("$.type").value("searchset"))
                .andExpect(jsonPath("$.total").value(3))
                .andReturn().getResponse().getContentAsString();

        JsonNode bundle = objectMapper.readTree(body);
        // A document bundle led by a Composition is what a receiving system opens first, and the
        // one the patient's own consultation has to be.
        assertThat(bundle.get("entry").get(0).get("resource").get("type").asString())
                .isEqualTo("document");
        assertThat(bundle.get("entry").get(0).get("resource")
                .get("entry").get(0).get("resource").get("resourceType").asString())
                .isEqualTo("Composition");
        assertThat(body).contains("J06.9").contains("Haemoglobin").contains("Amoxicillin");
    }

    @Test
    @DisplayName("the download is offered as a file, and never cached")
    void theExportIsADownload() throws Exception {
        mockMvc.perform(get("/portal/records/export").with(asPatient(patientId)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition",
                                org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    @DisplayName("a self-export is registered in the disclosure log like anything else that leaves")
    void aSelfExportIsRegistered() throws Exception {
        mockMvc.perform(get("/portal/records/export").with(asPatient(patientId)))
                .andExpect(status().isOk());

        // A patient downloading their own record is not a disclosure to anybody new, and that is
        // precisely why it is logged: the register answers "what has left this platform", and one
        // category of departure missing makes it unreliable for the others.
        mockMvc.perform(get("/interop/disclosures").with(as("ADMIN"))
                        .param("patientId", patientId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("PATIENT_EXPORT"))
                .andExpect(jsonPath("$[0].recipient").value(mrn));
    }

    @Test
    @DisplayName("an export with nothing released in it is an empty bundle, not an error")
    void anEmptyRecordExportsEmpty() throws Exception {
        when(portal.myEncounters(anyString())).thenReturn(List.of());
        when(portal.myReleasedLabOrders(anyString())).thenReturn(List.of());
        when(portal.myPrescriptions(anyString())).thenReturn(List.of());

        // A new patient with nothing on file is a real state. Refusing would tell them the platform
        // is broken when the truth is that there is nothing there yet.
        mockMvc.perform(get("/portal/records/export").with(asPatient(patientId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.entry").isEmpty());
    }

    @Test
    @DisplayName("staff are refused the portal export; theirs is the administrator's one")
    void staffAreRefused() throws Exception {
        mockMvc.perform(get("/portal/records/export").with(as("ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/portal/records/export").with(as("DOCTOR")))
                .andExpect(status().isForbidden());
    }
}
