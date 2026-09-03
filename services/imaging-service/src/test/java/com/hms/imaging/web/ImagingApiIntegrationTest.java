package com.hms.imaging.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.imaging.dicom.DicomTag;
import com.hms.imaging.dicom.DicomWriter;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Radiology end to end against a real database: order, acquire, report, sign.
 *
 * <p>Three identities, because the point of the module is that no account holds two parts of it. A
 * doctor orders and cannot acquire; a radiographer acquires and cannot report; a radiologist reports
 * and cannot order. Every refusal here is a row somebody would otherwise have to take on trust.
 *
 * <p>The care-relationship narrowing is off in this profile — it calls scheduling-service, which is
 * not running — so what these tests assert is radiology's own rules. The narrowing has its own
 * suite, as it does in the laboratory.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImagingApiIntegrationTest {

    /** A UID root of this test's own, so nothing it writes can collide with another run's. */
    private static final String UID_ROOT = "1.2.826.0.1.3680043.8.498.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt()
                .jwt(builder -> builder
                        .subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user")
                        .claim("roles", List.of(roles)))
                .authorities(authorities);
    }

    /** A unique UID per call, since a study UID is unique by construction and so is this. */
    private static String uid() {
        return UID_ROOT + Math.abs(UUID.randomUUID().getMostSignificantBits());
    }

    private JsonNode order(String procedureCode) throws Exception {
        String body = mockMvc.perform(post("/imaging/orders").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-2026-000123",
                                "procedureCode", procedureCode,
                                "clinicalQuestion",
                                "Persistent cough for three weeks, query consolidation"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** A DICOM file carrying the accession number a modality would have copied off the worklist. */
    private static MockMultipartFile dicom(String accession, String studyUid, String seriesUid,
                                           String sopUid) {
        byte[] file = new DicomWriter(DicomWriter.EXPLICIT_VR_LE, sopUid)
                .str(DicomTag.ACCESSION_NUMBER, "SH", accession)
                .str(DicomTag.PATIENT_ID, "LO", "typed-at-the-console")
                .str(DicomTag.MODALITY, "CS", "CR")
                .str(DicomTag.STUDY_INSTANCE_UID, "UI", studyUid)
                .str(DicomTag.SERIES_INSTANCE_UID, "UI", seriesUid)
                .str(DicomTag.SOP_INSTANCE_UID, "UI", sopUid)
                .str(DicomTag.STUDY_DESCRIPTION, "LO", "Chest PA")
                .str(DicomTag.STUDY_DATE, "DA", "20260903")
                .str(DicomTag.BODY_PART_EXAMINED, "CS", "CHEST")
                .uint16(DicomTag.ROWS, 2048)
                .uint16(DicomTag.COLUMNS, 2500)
                .pixels(64)
                .build();
        return new MockMultipartFile("file", "image.dcm", "application/dicom", file);
    }

    // ---- the chain -----------------------------------------------------------

    @Test
    @DisplayName("a request is ordered, scanned, reported and signed, by three different people")
    void theWholeChain() throws Exception {
        JsonNode created = order("XR_CHEST_PA");
        String orderId = created.get("id").asText();
        String accession = created.get("accessionNo").asText();

        // The catalogue decides the modality, not the requester: they chose an examination.
        assertThat(created.get("modality").asText()).isEqualTo("CR");
        assertThat(created.get("status").asText()).isEqualTo("ORDERED");
        assertThat(accession).matches("IMG\\d{4}-\\d{6}");

        // The radiographer sees it on the worklist.
        String worklist = mockMvc.perform(get("/imaging/worklist").with(as("RADIOGRAPHER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(worklist).contains(accession);
        // And the worklist carries no clinical question: it is read on a screen beside a scanner,
        // in a room patients walk through.
        assertThat(worklist).doesNotContain("Persistent cough");

        // The modality sends an image, carrying the accession number off the worklist.
        String studyUid = uid();
        String ingest = mockMvc.perform(multipart("/imaging/studies")
                        .file(dicom(accession, studyUid, uid(), uid()))
                        .with(as("RADIOGRAPHER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.orderId").value(orderId))
                // No archive is configured in this profile, which is the shipped default, and the
                // response says so rather than implying a file was kept.
                .andExpect(jsonPath("$.stored").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(ingest).contains("No archive is configured");

        // The order moved on its own, on the first image rather than on a signal no modality sends.
        mockMvc.perform(get("/imaging/orders/" + orderId).with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACQUIRED"))
                .andExpect(jsonPath("$.studies[0].studyInstanceUid").value(studyUid));

        // It is now on the radiologist's queue.
        assertThat(mockMvc.perform(get("/imaging/reporting-queue").with(as("RADIOLOGIST")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .contains(accession);

        String studyId = studyId(orderId);

        // A draft is written, and is a draft.
        mockMvc.perform(put("/imaging/studies/" + studyId + "/report").with(as("RADIOLOGIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "findings", "Right lower zone consolidation.",
                                "impression", "Community-acquired pneumonia."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.signedBy").doesNotExist());

        // Signing is release, and the order finishes with it.
        mockMvc.perform(post("/imaging/studies/" + studyId + "/report/sign").with(as("RADIOLOGIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"))
                .andExpect(jsonPath("$.signedBy").value("test-user"));
        mockMvc.perform(get("/imaging/orders/" + orderId).with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPORTED"));
    }

    @Test
    @DisplayName("a signed report is amended, not overwritten, and the old text is kept")
    void amendmentKeepsWhatWasSigned() throws Exception {
        String orderId = order("CT_HEAD").get("id").asText();
        String accession = accessionOf(orderId);
        mockMvc.perform(multipart("/imaging/studies").file(dicom(accession, uid(), uid(), uid()))
                .with(as("RADIOGRAPHER"))).andExpect(status().isCreated());
        String studyId = studyId(orderId);

        draft(studyId, "No acute intracranial abnormality.", "Normal study.");
        mockMvc.perform(post("/imaging/studies/" + studyId + "/report/sign").with(as("RADIOLOGIST")))
                .andExpect(status().isOk());

        // Editing a signed report is refused, and the refusal says what to do instead.
        mockMvc.perform(put("/imaging/studies/" + studyId + "/report").with(as("RADIOLOGIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "findings", "Small subdural collection.",
                                "impression", "Subdural haematoma."))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("amendment")));

        // An amendment carries the text that was signed, which is the point: somebody may have
        // treated from it.
        mockMvc.perform(post("/imaging/studies/" + studyId + "/report/amend").with(as("RADIOLOGIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "findings", "Small left frontal subdural collection.",
                                "impression", "Subdural haematoma, neurosurgery informed.",
                                "reason", "Missed on the first read, found on comparison"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AMENDED"))
                .andExpect(jsonPath("$.amendedFrom").value(
                        org.hamcrest.Matchers.containsString("No acute intracranial abnormality")))
                .andExpect(jsonPath("$.amendedReason").value(
                        org.hamcrest.Matchers.containsString("comparison")));
    }

    @Test
    @DisplayName("signing before anything is written is refused rather than signing nothing")
    void signingNothingIsRefused() throws Exception {
        String orderId = order("US_ABDO").get("id").asText();
        mockMvc.perform(multipart("/imaging/studies")
                .file(dicom(accessionOf(orderId), uid(), uid(), uid()))
                .with(as("RADIOGRAPHER"))).andExpect(status().isCreated());

        mockMvc.perform(post("/imaging/studies/" + studyId(orderId) + "/report/sign")
                        .with(as("RADIOLOGIST")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("no report yet")));
    }

    // ---- what arrives for nobody --------------------------------------------

    @Test
    @DisplayName("a study whose accession matches no order is kept, flagged, and listed")
    void anUnmatchedStudyIsKeptRatherThanGuessedAt() throws Exception {
        String studyUid = uid();

        mockMvc.perform(multipart("/imaging/studies")
                        .file(dicom("IMG1999-000000", studyUid, uid(), uid()))
                        .with(as("RADIOGRAPHER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.matched").value(false))
                .andExpect(jsonPath("$.orderId").doesNotExist())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("matches no order")));

        // On the list somebody works from, and with no patient attached: the header's patient id
        // was "typed-at-the-console" and the platform declines to believe it.
        String unmatched = mockMvc.perform(get("/imaging/studies/unmatched")
                        .with(as("RADIOGRAPHER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(unmatched).contains(studyUid);
        assertThat(unmatched).doesNotContain("typed-at-the-console");
    }

    @Test
    @DisplayName("re-sending the same instance does not produce a second copy")
    void ingestIsIdempotentPerInstance() throws Exception {
        String orderId = order("XR_KNEE").get("id").asText();
        String accession = accessionOf(orderId);
        String studyUid = uid();
        String seriesUid = uid();
        String sopUid = uid();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(multipart("/imaging/studies")
                            .file(dicom(accession, studyUid, seriesUid, sopUid))
                            .with(as("RADIOGRAPHER")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.studyInstanceUid").value(studyUid));
        }

        // One series, one instance. A scanner that resends because it was unsure the first attempt
        // landed is doing the right thing, and must not double the study.
        mockMvc.perform(get("/imaging/orders/" + orderId).with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studies.length()").value(1))
                .andExpect(jsonPath("$.studies[0].series.length()").value(1))
                .andExpect(jsonPath("$.studies[0].series[0].instanceCount").value(1));
    }

    @Test
    @DisplayName("bytes that are not DICOM are refused with a reason, not a 500")
    void nonDicomIsRefused() throws Exception {
        MockMultipartFile jpeg = new MockMultipartFile("file", "photo.jpg", "image/jpeg",
                "not a dicom file at all".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/imaging/studies").file(jpeg).with(as("RADIOGRAPHER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    // ---- who may do what ----------------------------------------------------

    @Test
    @DisplayName("no account holds two parts of the chain")
    void theSeparationOfDutiesHolds() throws Exception {
        String orderId = order("MR_BRAIN").get("id").asText();
        String accession = accessionOf(orderId);
        mockMvc.perform(multipart("/imaging/studies").file(dicom(accession, uid(), uid(), uid()))
                .with(as("RADIOGRAPHER"))).andExpect(status().isCreated());
        String studyId = studyId(orderId);

        // A radiographer acquires and does not report.
        mockMvc.perform(put("/imaging/studies/" + studyId + "/report").with(as("RADIOGRAPHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "findings", "x", "impression", "y"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/imaging/studies/" + studyId + "/report/sign")
                .with(as("RADIOGRAPHER"))).andExpect(status().isForbidden());

        // A radiologist reports and does not acquire, order or schedule.
        mockMvc.perform(multipart("/imaging/studies").file(dicom(accession, uid(), uid(), uid()))
                .with(as("RADIOLOGIST"))).andExpect(status().isForbidden());
        // A *valid* body, deliberately. @Valid resolves during argument binding, before
        // @PreAuthorize runs, so an empty one answers 400 and would pass this row whatever the role
        // rules said. The only thing left to refuse a well-formed request is the role.
        mockMvc.perform(post("/imaging/orders").with(as("RADIOLOGIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-2026-000123",
                                "procedureCode", "XR_CHEST_PA",
                                "clinicalQuestion",
                                "A question long enough to pass validation cleanly"))))
                .andExpect(status().isForbidden());

        // A doctor orders and does not run the department.
        mockMvc.perform(get("/imaging/worklist").with(as("DOCTOR")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/imaging/studies/unmatched").with(as("DOCTOR")))
                .andExpect(status().isForbidden());

        // And the service lines with no part in radiology at all reach none of it.
        for (String role : List.of("RECEPTIONIST", "LAB_TECH", "PHARMACIST", "CASHIER")) {
            mockMvc.perform(get("/imaging/orders/" + orderId).with(as(role)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("an unknown examination is refused before an accession number is burnt on it")
    void anUnknownProcedureIsRefused() throws Exception {
        mockMvc.perform(post("/imaging/orders").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-2026-000123",
                                "procedureCode", "XR_NOSUCH",
                                "clinicalQuestion", "Something that reads like a real question"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("Unknown examination")));
    }

    @Test
    @DisplayName("an order with no clinical question is refused, because a radiologist would guess")
    void aRequestWithoutAQuestionIsRefused() throws Exception {
        mockMvc.perform(post("/imaging/orders").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-2026-000123",
                                "procedureCode", "XR_CHEST_PA",
                                "clinicalQuestion", "?"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.clinicalQuestion").exists());
    }

    @Test
    @DisplayName("the catalogue is readable by anybody signed in, and lists what can be ordered")
    void theCatalogueIsReadable() throws Exception {
        String catalogue = mockMvc.perform(get("/imaging/procedures").with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(catalogue).contains("XR_CHEST_PA").contains("CT_HEAD").contains("MR_BRAIN");
    }

    // ---- helpers -------------------------------------------------------------

    private void draft(String studyId, String findings, String impression) throws Exception {
        mockMvc.perform(put("/imaging/studies/" + studyId + "/report").with(as("RADIOLOGIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("findings", findings, "impression", impression))))
                .andExpect(status().isOk());
    }

    private String accessionOf(String orderId) throws Exception {
        return read(orderId).get("accessionNo").asText();
    }

    private String studyId(String orderId) throws Exception {
        return read(orderId).get("studies").get(0).get("id").asText();
    }

    private JsonNode read(String orderId) throws Exception {
        return objectMapper.readTree(
                mockMvc.perform(get("/imaging/orders/" + orderId).with(as("DOCTOR")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
    }
}
