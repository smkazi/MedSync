package com.hms.interop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.interop.hl7.Er7Parser;
import com.hms.interop.hl7.Hl7Message;
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
 * The HL7 interface through its real HTTP surface, against a real database.
 *
 * <p>What is asserted here is the acknowledgement, because the acknowledgement is the contract. A
 * sender does not read this platform's HTTP status or its logs; it reads MSA-1, and the difference
 * between AA, AE and AR decides whether it retries for ever, gives up, or files the message as
 * delivered when nothing was done with it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Hl7ApiIntegrationTest {

    private static final String CR = "\r";

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

    private String adt(String controlId) {
        return String.join(CR,
                "MSH|^~\\&|LIS|CENTRAL LAB|HMS|CITY HOSPITAL|20260903120000||ADT^A04|"
                        + controlId + "|P|2.5",
                "EVN|A04|20260903115900",
                "PID|1||MRN-2026-000010^^^HMS^MR||Noorani^Farida||19780412|F");
    }

    /** Posts a message and returns the parsed acknowledgement. */
    private Hl7Message send(String message) throws Exception {
        String ack = mockMvc.perform(post("/hl7").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("message", message))))
                // Always 200: the verdict is in the acknowledgement, which is the protocol's own
                // error channel. Two contradictory error mechanisms would leave a sender with no
                // rule for which one wins.
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Er7Parser.parse(ack);
    }

    @Test
    @DisplayName("a message it handles is accepted, and the acknowledgement comes back addressed to the sender")
    void acceptsAHandledMessage() throws Exception {
        String controlId = "CID" + UUID.randomUUID().toString().substring(0, 8);
        Hl7Message ack = send(adt(controlId));

        assertThat(ack.segment("MSA").orElseThrow().field(1)).isEqualTo("AA");
        // The sender matches its message by the control id and by nothing else.
        assertThat(ack.segment("MSA").orElseThrow().field(2)).isEqualTo(controlId);
        assertThat(ack.receivingApplication()).isEqualTo("LIS");
        assertThat(ack.sendingApplication()).isEqualTo("HMS");
    }

    @Test
    @DisplayName("a type it does not handle is AE, not AA — the sender must not file it as delivered")
    void refusesAnUnhandledType() throws Exception {
        String message = String.join(CR,
                "MSH|^~\\&|LIS|LAB|HMS|CITY|20260903120000||MDM^T02|MDM1|P|2.5",
                "PID|1||MRN-1||Doe^Jane");

        Hl7Message ack = send(message);

        // AA here would be a lie the sender has no way to detect: their record would say the
        // document was delivered, and nothing on this platform would ever have looked at it.
        assertThat(ack.segment("MSA").orElseThrow().field(1)).isEqualTo("AE");
        // Unescaped before comparing, because MSA-3 is free text: a caret in it is content and is
        // escaped on the wire, unlike the one in MSH-9 which separates a type from its trigger.
        assertThat(Er7Parser.unescape(ack.segment("MSA").orElseThrow().field(3), ack.encoding()))
                .contains("MDM^T02");
    }

    @Test
    @DisplayName("a message that does not parse is AR, because re-sending it might work")
    void rejectsWhatItCannotParse() throws Exception {
        Hl7Message ack = send("PID|1||MRN-1||Doe^Jane");

        // AR and not AE: the distinction is whether the sender should try again. A misconfigured
        // sender that fixes its header and re-sends should be able to.
        assertThat(ack.segment("MSA").orElseThrow().field(1)).isEqualTo("AR");
        // And no control id, because the header that carries it is what was missing.
        assertThat(ack.segment("MSA").orElseThrow().field(2)).isEmpty();
    }

    @Test
    @DisplayName("every message is stored verbatim, including the ones that did not parse")
    void storesEveryMessageVerbatim() throws Exception {
        String controlId = "CID" + UUID.randomUUID().toString().substring(0, 8);
        send(adt(controlId));

        String body = mockMvc.perform(get("/hl7/messages/by-control-id/" + controlId)
                        .with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = objectMapper.readTree(body);

        assertThat(rows.size()).isEqualTo(1);
        JsonNode row = rows.get(0);
        assertThat(row.get("direction").asString()).isEqualTo("IN");
        assertThat(row.get("messageType").asString()).isEqualTo("ADT^A04");
        assertThat(row.get("ackCode").asString()).isEqualTo("AA");
        // The raw text is the point: the messages worth asking about are the ones that did not
        // parse, and a parsed-only record cannot answer "what did you actually receive".
        assertThat(row.get("raw").asString()).contains("Noorani");
        assertThat(row.get("ackRaw").asString()).contains("MSA|AA");
    }

    @Test
    @DisplayName("the failures filter finds the dozen that matter among the thousands that do not")
    void filtersToFailures() throws Exception {
        send(adt("CID" + UUID.randomUUID().toString().substring(0, 8)));
        send("not a message at all");

        String body = mockMvc.perform(get("/hl7/messages").param("failuresOnly", "true")
                        .param("size", "50").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode page = objectMapper.readTree(body);

        assertThat(page.get("content").size()).isGreaterThan(0);
        for (JsonNode row : page.get("content")) {
            boolean failed = !row.get("error").isNull()
                    || List.of("AE", "AR").contains(row.get("ackCode").asString());
            assertThat(failed).isTrue();
        }
    }

    @Test
    @DisplayName("the interface is not open to anyone with a token")
    void theInterfaceIsNotOpenToEverybody() throws Exception {
        // An endpoint that writes clinical messages into a hospital is the thing the rest of this
        // platform's security spends its time avoiding. That HL7 traditionally has no
        // authentication is a reason to add some, not to match it.
        mockMvc.perform(post("/hl7").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("message", adt("X1")))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/hl7/messages").with(as("LAB_TECH")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/hl7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("message", adt("X2")))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("building a message it cannot build is refused before anything is sent")
    void refusesAMessageTypeItCannotBuild() throws Exception {
        Map<String, Object> request = Map.of(
                "host", "127.0.0.1", "port", 1,
                "receivingApplication", "GP", "receivingFacility", "PRACTICE",
                "messageType", "MDM^T02",
                "patient", Map.of("mrn", "MRN-1", "familyName", "Doe", "givenName", "Jane",
                        "dateOfBirth", "19800101", "sex", "F", "phone", ""));

        mockMvc.perform(post("/hl7/send").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ADT^A04")));
    }

    @Test
    @DisplayName("a send nobody is listening for is recorded as failed rather than lost")
    void recordsAFailedSend() throws Exception {
        // Port 1 is not listening. What matters is that the exchange is on the log afterwards with
        // its reason: a send that vanishes because nothing was listening is the failure an
        // interface engineer is called about, and it has to leave a row.
        Map<String, Object> request = Map.of(
                "host", "127.0.0.1", "port", 1,
                "receivingApplication", "GP", "receivingFacility", "PRACTICE",
                "messageType", "ADT^A04",
                "patient", Map.of("mrn", "MRN-SEND-1", "familyName", "O^Brien",
                        "givenName", "Se&an", "dateOfBirth", "19800101", "sex", "M",
                        "phone", "9876543210"));

        String body = mockMvc.perform(post("/hl7/send").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode sent = objectMapper.readTree(body);

        assertThat(sent.get("direction").asString()).isEqualTo("OUT");
        assertThat(sent.get("messageType").asString()).isEqualTo("ADT^A04");
        assertThat(sent.get("error").asString()).isNotBlank();

        // And the name with a caret in it was escaped on the way out. Written raw it would not
        // corrupt the name -- it would shift every later field one component left, and the
        // receiver would file a valid-looking message with the wrong data in all of them.
        String raw = sent.get("raw").asString();
        assertThat(raw).contains("O\\S\\Brien").contains("Se\\T\\an");
        Hl7Message rebuilt = Er7Parser.parse(raw);
        assertThat(Er7Parser.unescape(rebuilt.segment("PID").orElseThrow().component(5, 1),
                rebuilt.encoding())).isEqualTo("O^Brien");
        // The field after the name is still the date of birth, which is the thing escaping protects.
        assertThat(rebuilt.segment("PID").orElseThrow().field(7)).isEqualTo("19800101");
    }
}
