package com.hms.laboratory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * Drives the laboratory API against a real database, from ordering through analyzer ingest to
 * verification — the path a sample actually takes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LabApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt()
                .jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user")
                        .claim("roles", List.of(roles)))
                .authorities(authorities);
    }

    /** Creates an order for a fresh synthetic patient and returns it. */
    private JsonNode createOrder(String sex, String... testCodes) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", UUID.randomUUID().toString());
        body.put("patientMrn", "MRN-TEST-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("patientSex", sex);
        body.put("testCodes", List.of(testCodes));
        body.put("priority", "ROUTINE");

        String response = mockMvc.perform(post("/lab/orders").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String collectSpecimen(String orderId) throws Exception {
        String response = mockMvc.perform(post("/lab/orders/" + orderId + "/specimens").with(as("LAB_TECH"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessionNo").asString();
    }

    /** A Sysmex XP-300 transmission addressed to an accession number. */
    private static String astmTransmission(String accession) {
        return "H|\\^&|||XP300|||||||P|1\r"
                + "P|1||" + accession + "||TEST^PATIENT||19880412|F|||||||Dr. TEST\r"
                + "O|1|" + accession + "||^^^CBC|R||20260901090000\r"
                + "R|1|^^^^WBC^26|12.8|10*3/uL|4.0-11.0|N\r"
                + "R|2|^^^^HGB^26|9.4|g/dL|11.5-14.5|N\r"
                + "R|3|^^^^PLT^26|140|10*3/uL|150.0-450.0|N\r"
                + "R|4|^^^^W-SCR^26|26.9|%||N\r"
                + "R|5|^^^^MCH^26|***.*|pg||A\r"
                + "L|1|N\r";
    }

    private JsonNode ingestAstm(String accession) throws Exception {
        String response = mockMvc.perform(post("/lab/device-messages").with(as("LAB_TECH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "protocol", "ASTM",
                                "analyzerName", "Haematology-1",
                                "payload", astmTransmission(accession)))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode results(String orderId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/lab/orders/" + orderId + "/results").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("an order is created with its panel and starts as ORDERED")
    void orderIsCreated() throws Exception {
        JsonNode order = createOrder("F", "CBC");

        assertThat(order.get("status").asString()).isEqualTo("ORDERED");
        assertThat(order.get("items")).hasSize(1);
        assertThat(order.get("items").get(0).get("testCode").asString()).isEqualTo("CBC");
    }

    @Test
    @DisplayName("an unknown test code is rejected")
    void unknownTestCodeIsRejected() throws Exception {
        Map<String, Object> body = Map.of("patientId", UUID.randomUUID().toString(),
                "patientMrn", "MRN-TEST-X", "testCodes", List.of("NOT-A-TEST"));

        mockMvc.perform(post("/lab/orders").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("collecting a specimen issues a unique accession number and advances the order")
    void collectionIssuesAccession() throws Exception {
        String orderId = createOrder("F", "CBC").get("id").asString();

        String first = collectSpecimen(orderId);
        String second = collectSpecimen(createOrder("M", "CBC").get("id").asString());

        assertThat(first).matches("L\\d{4}-\\d{6}");
        assertThat(first).isNotEqualTo(second);
        mockMvc.perform(get("/lab/orders/" + orderId).with(as("DOCTOR")))
                .andExpect(jsonPath("$.status").value("COLLECTED"));
    }

    @Test
    @DisplayName("scanning a tube finds the order it belongs to")
    void scanFindsTheOrder() throws Exception {
        String orderId = createOrder("F", "CBC").get("id").asString();
        String accession = collectSpecimen(orderId);

        // The whole point of the barcode: the technician scans and lands on the right order rather
        // than typing an accession number next to a rack of identical tubes.
        mockMvc.perform(get("/lab/specimens/by-accession/{a}", accession).with(as("LAB_TECH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.specimens[0].accessionNo").value(accession));
    }

    @Test
    @DisplayName("an unknown accession is a 404, not an empty result")
    void unknownAccessionIsNotFound() throws Exception {
        // A tube whose label does not resolve is an incident, and 404 says so. An empty list would
        // read as "nothing ordered", which is a different and much more dangerous statement.
        mockMvc.perform(get("/lab/specimens/by-accession/{a}", "L2026-999999").with(as("LAB_TECH")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("L2026-999999")));
    }

    @Test
    @DisplayName("a nurse chasing a sample can scan it without bench write access")
    void scanIsAReadNotAWrite() throws Exception {
        String accession = collectSpecimen(createOrder("F", "CBC").get("id").asString());

        mockMvc.perform(get("/lab/specimens/by-accession/{a}", accession).with(as("NURSE")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the specimen label is an SVG barcode carrying the accession number")
    void labelIsRendered() throws Exception {
        String accession = collectSpecimen(createOrder("F", "CBC").get("id").asString());

        String svg = mockMvc.perform(get("/lab/specimens/{a}/label", accession).with(as("LAB_TECH")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("image/svg+xml")))
                // Never cached: a label belongs to one tube, and a stale one is the wrong barcode
                // on the right sample.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();

        assertThat(svg).startsWith("<svg").endsWith("</svg>");
        // The human-readable line, for when the scanner fails and somebody has to read it out.
        assertThat(svg).contains(accession);
        // Bars actually drawn, not an empty frame.
        assertThat(svg).contains("fill=\"#000000\"");
        assertThat(countOccurrences(svg, "<rect")).isGreaterThan(20);
    }

    @Test
    @DisplayName("the label carries no patient identity")
    void labelCarriesNoPatientIdentity() throws Exception {
        JsonNode order = createOrder("F", "CBC");
        String mrn = order.get("patientMrn").asString();
        String accession = collectSpecimen(order.get("id").asString());

        String svg = mockMvc.perform(get("/lab/specimens/{a}/label", accession).with(as("LAB_TECH")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // A tube label is handled by couriers and visible in a shared collection room. The lab works
        // by accession number, so the name and MRN would leak identity and buy nothing.
        assertThat(svg).doesNotContain(mrn);
        assertThat(svg).doesNotContain("TEST^PATIENT");
    }

    @Test
    @DisplayName("printing a label needs bench access")
    void labelRequiresLabAccess() throws Exception {
        String accession = collectSpecimen(createOrder("F", "CBC").get("id").asString());

        mockMvc.perform(get("/lab/specimens/{a}/label", accession).with(as("RECEPTIONIST")))
                .andExpect(status().isForbidden());
    }

    // ---- the host-query direction ----------------------------------------------

    @Test
    @DisplayName("an analyzer asks what is ordered for a scanned sample")
    void worklistForSample() throws Exception {
        JsonNode order = createOrder("F", "CBC", "ESR");
        String accession = collectSpecimen(order.get("id").asString());

        mockMvc.perform(get("/lab/worklist/query").param("sampleId", accession).with(as("LAB_TECH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessionNo").value(accession))
                .andExpect(jsonPath("$.orderId").value(order.get("id").asString()))
                .andExpect(jsonPath("$.patientSex").value("F"))
                .andExpect(jsonPath("$.specimenType").value("WHOLE_BLOOD"))
                .andExpect(jsonPath("$.testCodes").value(
                        org.hamcrest.Matchers.containsInAnyOrder("CBC", "ESR")))
                .andExpect(jsonPath("$.runnable").value(true));
    }

    @Test
    @DisplayName("a cancelled order is not offered to an analyzer")
    void cancelledOrderIsNotRunnable() throws Exception {
        String orderId = createOrder("F", "CBC").get("id").asString();
        String accession = collectSpecimen(orderId);
        mockMvc.perform(delete("/lab/orders/" + orderId).with(as("DOCTOR")))
                .andExpect(status().isOk());

        // Running a cancelled test burns reagent and produces a result nobody asked for, which then
        // has to be explained away on a chart.
        mockMvc.perform(get("/lab/worklist/query").param("sampleId", accession).with(as("LAB_TECH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.runnable").value(false));
    }

    @Test
    @DisplayName("the JSON worklist 404s for an unknown sample")
    void unknownSampleIsNotFoundForAHuman() throws Exception {
        mockMvc.perform(get("/lab/worklist/query").param("sampleId", "L2026-999999")
                        .with(as("LAB_TECH")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an ASTM query is answered with the order on the wire")
    void astmQueryIsAnswered() throws Exception {
        JsonNode order = createOrder("F", "CBC");
        String accession = collectSpecimen(order.get("id").asString());

        String reply = mockMvc.perform(post("/lab/device-messages/query").with(as("LAB_TECH"))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("H|\\^&|||XP300|||||||Q|1\r"
                                + "Q|1|^" + accession + "||ALL||||||||O\r"
                                + "L|1|N\r"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(reply).startsWith("H|");
        assertThat(reply).contains(accession);
        assertThat(reply).contains("^^^CBC");
        assertThat(reply).contains("L|1|N");
    }

    @Test
    @DisplayName("an ASTM query for an unknown sample gets an empty worklist, never an error")
    void astmQueryForUnknownSampleIsStillAnswered() throws Exception {
        // The asymmetry that matters. An analyzer is a state machine waiting on a reply: an error
        // status leaves it blocked, and the operator sees a hung instrument rather than a tube with
        // nothing ordered. "No orders" is an answer; a 404 is not.
        String reply = mockMvc.perform(post("/lab/device-messages/query").with(as("LAB_TECH"))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("Q|1|^L2026-999999||ALL||||||||O\r"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(reply).startsWith("H|").contains("L|1|N");
        assertThat(reply).doesNotContain("O|");
    }

    @Test
    @DisplayName("answering an analyzer needs bench access")
    void analyzerQueryRequiresLabAccess() throws Exception {
        mockMvc.perform(post("/lab/device-messages/query").with(as("RECEPTIONIST"))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("Q|1|^L2026-000001||ALL||||||||O\r"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("query then result: the full bidirectional conversation reaches VERIFIED")
    void fullBidirectionalConversation() throws Exception {
        String orderId = createOrder("F", "CBC").get("id").asString();
        String accession = collectSpecimen(orderId);

        // 1. The instrument reads the barcode and asks what to run.
        String worklist = mockMvc.perform(post("/lab/device-messages/query").with(as("LAB_TECH"))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("Q|1|^" + accession + "||ALL||||||||O\r"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(worklist).contains("^^^CBC");

        // 2. It runs the sample and transmits results back through the existing inbound seam,
        //    addressed to the accession number it was told about - nobody keyed anything.
        ingestAstm(accession);

        // 3. The pathologist releases them.
        mockMvc.perform(post("/lab/orders/" + orderId + "/verify").with(as("PATHOLOGIST")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/lab/orders/" + orderId).with(as("DOCTOR")))
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.results", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.empty())));

        // And the closed order is no longer offered for a re-run.
        mockMvc.perform(get("/lab/worklist/query").param("sampleId", accession).with(as("LAB_TECH")))
                .andExpect(jsonPath("$.runnable").value(false));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    @Test
    @DisplayName("an ASTM transmission is matched to its order by accession number")
    void astmIngestMatchesByAccession() throws Exception {
        String orderId = createOrder("F", "CBC").get("id").asString();
        String accession = collectSpecimen(orderId);

        JsonNode ingest = ingestAstm(accession);

        assertThat(ingest.get("parsedOk").asBoolean()).isTrue();
        assertThat(ingest.get("matchedOrderId").asString()).isEqualTo(orderId);
        assertThat(ingest.get("sampleId").asString()).isEqualTo(accession);
    }

    @Test
    @DisplayName("results are flagged against the lab's ranges, overriding the analyzer's 'normal'")
    void resultsAreFlaggedAgainstLabRanges() throws Exception {
        String orderId = createOrder("F", "CBC").get("id").asString();
        ingestAstm(collectSpecimen(orderId));

        Map<String, String> flags = new HashMap<>();
        results(orderId).forEach(result ->
                flags.put(result.get("parameter").asString(), result.get("flag").asString()));

        // Every one of these arrived with the analyzer's flag set to "N".
        assertThat(flags).containsEntry("WBC", "H").containsEntry("HGB", "L").containsEntry("PLT", "L");
    }

    @Test
    @DisplayName("XP W- codes are translated to the reported parameter names")
    void xpCodesAreTranslated() throws Exception {
        String orderId = createOrder("F", "CBC").get("id").asString();
        ingestAstm(collectSpecimen(orderId));

        List<String> parameters = new java.util.ArrayList<>();
        results(orderId).forEach(result -> parameters.add(result.get("parameter").asString()));

        assertThat(parameters).contains("LYM%").doesNotContain("W-SCR");
    }

    @Test
    @DisplayName("a masked reading is not stored as a result at all")
    void maskedReadingIsNotStored() throws Exception {
        String orderId = createOrder("F", "CBC").get("id").asString();
        JsonNode ingest = ingestAstm(collectSpecimen(orderId));

        List<String> parameters = new java.util.ArrayList<>();
        results(orderId).forEach(result -> parameters.add(result.get("parameter").asString()));

        assertThat(parameters)
                .as("MCH arrived as ***.* and carries no measurement")
                .doesNotContain("MCH");
        assertThat(ingest.get("warnings").toString()).contains("MCH");
    }

    @Test
    @DisplayName("an unmatched transmission is retained and reported, never filed on a guess")
    void unmatchedTransmissionIsNotGuessed() throws Exception {
        JsonNode ingest = objectMapper.readTree(mockMvc.perform(post("/lab/device-messages").with(as("LAB_TECH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "protocol", "ASTM", "payload", astmTransmission("L9999-999999")))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());

        assertThat(ingest.get("matchedOrderId").isNull()).isTrue();
        assertThat(ingest.get("resultsStored").asInt()).isZero();
        assertThat(ingest.get("warnings").toString()).contains("No open order matched");

        // The message itself is kept, so a technician can see what arrived and why it did not land.
        mockMvc.perform(get("/lab/device-messages").with(as("LAB_TECH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("an unparseable transmission is recorded as a failure rather than lost")
    void unparseableTransmissionIsRecorded() throws Exception {
        JsonNode ingest = objectMapper.readTree(mockMvc.perform(post("/lab/device-messages").with(as("LAB_TECH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "protocol", "ASTM", "payload", "this is not an ASTM transmission"))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());

        assertThat(ingest.get("parsedOk").asBoolean()).isFalse();
        assertThat(ingest.get("error").asString()).isNotBlank();
        assertThat(ingest.get("messageId").asString()).isNotBlank();
    }

    @Test
    @DisplayName("manual entry records results a technician typed")
    void manualEntryRecordsResults() throws Exception {
        String orderId = createOrder("M", "ESR").get("id").asString();
        collectSpecimen(orderId);

        mockMvc.perform(post("/lab/orders/" + orderId + "/results").with(as("LAB_TECH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("results",
                                List.of(Map.of("parameter", "ESR", "value", "35", "unit", "mm/hr"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].parameter").value("ESR"))
                .andExpect(jsonPath("$[0].flag").value("H"))
                .andExpect(jsonPath("$[0].source").value("MANUAL"));
    }

    @Test
    @DisplayName("re-entering a parameter amends it rather than adding a second row")
    void reEntryAmendsRatherThanDuplicates() throws Exception {
        String orderId = createOrder("M", "ESR").get("id").asString();
        collectSpecimen(orderId);
        String body = objectMapper.writeValueAsString(Map.of("results",
                List.of(Map.of("parameter", "ESR", "value", "35", "unit", "mm/hr"))));
        mockMvc.perform(post("/lab/orders/" + orderId + "/results").with(as("LAB_TECH"))
                .contentType(MediaType.APPLICATION_JSON).content(body));

        mockMvc.perform(post("/lab/orders/" + orderId + "/results").with(as("LAB_TECH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("results",
                                List.of(Map.of("parameter", "ESR", "value", "12", "unit", "mm/hr"))))))
                .andExpect(status().isOk());

        JsonNode found = results(orderId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("value").asString()).isEqualTo("12");
        assertThat(found.get(0).get("flag").asString()).isEmpty();
    }

    @Test
    @DisplayName("only a pathologist may verify, and verification closes the order")
    void verificationIsPathologistOnly() throws Exception {
        String orderId = createOrder("F", "CBC").get("id").asString();
        ingestAstm(collectSpecimen(orderId));

        mockMvc.perform(post("/lab/orders/" + orderId + "/verify").with(as("LAB_TECH")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/lab/orders/" + orderId + "/verify").with(as("PATHOLOGIST")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/lab/orders/" + orderId).with(as("DOCTOR")))
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.hasAbnormalResults").value(true));
        results(orderId).forEach(result ->
                assertThat(result.get("status").asString()).isEqualTo("VERIFIED"));
    }

    @Test
    @DisplayName("a verified order takes no further results")
    void verifiedOrderIsClosedToNewResults() throws Exception {
        String orderId = createOrder("F", "CBC").get("id").asString();
        ingestAstm(collectSpecimen(orderId));
        mockMvc.perform(post("/lab/orders/" + orderId + "/verify").with(as("PATHOLOGIST")));

        mockMvc.perform(post("/lab/orders/" + orderId + "/results").with(as("LAB_TECH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("results",
                                List.of(Map.of("parameter", "ESR", "value", "30"))))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an order with results cannot be cancelled")
    void orderWithResultsCannotBeCancelled() throws Exception {
        String orderId = createOrder("F", "CBC").get("id").asString();
        ingestAstm(collectSpecimen(orderId));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/lab/orders/" + orderId).with(as("DOCTOR")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an order with no results can be cancelled")
    void emptyOrderCanBeCancelled() throws Exception {
        String orderId = createOrder("F", "CBC").get("id").asString();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/lab/orders/" + orderId).with(as("DOCTOR")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/lab/orders/" + orderId).with(as("DOCTOR")))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("the worklist shows orders awaiting attention")
    void worklistShowsOpenOrders() throws Exception {
        JsonNode order = createOrder("F", "CBC");
        String mrn = order.get("patientMrn").asString();

        mockMvc.perform(get("/lab/orders").param("mrn", mrn).with(as("LAB_TECH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("ORDERED"));
    }

    @Test
    @DisplayName("reference ranges and the catalog are readable, and ranges are editable by a pathologist")
    void referenceDataIsAvailable() throws Exception {
        mockMvc.perform(get("/lab/catalog").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(4))));

        String ranges = mockMvc.perform(get("/lab/reference-ranges").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode first = objectMapper.readTree(ranges).get(0);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/lab/reference-ranges/" + first.get("id").asString()).with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"normalHigh\": 99}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/lab/reference-ranges/" + first.get("id").asString()).with(as("PATHOLOGIST"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"normalHigh\": 99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.normalHigh").value(99));
    }

    @Test
    @DisplayName("an anonymous request reaches nothing")
    void anonymousAccessIsRejected() throws Exception {
        mockMvc.perform(get("/lab/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/lab/device-messages").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
