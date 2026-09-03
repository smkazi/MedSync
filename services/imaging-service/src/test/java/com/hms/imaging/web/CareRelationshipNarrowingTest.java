package com.hms.imaging.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The care-team narrowing, from radiology's side of it.
 *
 * <p>The rest of this module's suite runs with the narrowing off, because scheduling-service is not
 * running in a radiology unit test and a fail-closed client would turn every clinician read into a
 * 403 about the wrong thing. So it is proven here, against a stub standing in for
 * scheduling-service — which is what lets the interesting cases be tested at all: the answer being
 * no, and the answer being unavailable.
 *
 * <p>Deliberately the same shape as the laboratory's test of the same name. Radiology is the third
 * service to ask this question, and a reader who knows one should recognise the others.
 *
 * <p>The one case here that is radiology's alone: the department is <strong>not</strong> narrowed.
 * A radiographer's worklist and the unmatched list are read without a care relationship, because a
 * service line does inherently cross-patient work — and because an unmatched study has no patient
 * to have a relationship with, which is exactly the problem it represents.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CareRelationshipNarrowingTest {

    private static HttpServer scheduling;

    /** What the stub answers next, and whether it answers at all. */
    private static final AtomicBoolean related = new AtomicBoolean(true);
    private static final AtomicBoolean reachable = new AtomicBoolean(true);
    private static final AtomicReference<String> seenAuthorization = new AtomicReference<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startStub() throws IOException {
        scheduling = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        scheduling.createContext("/care-relationships", exchange -> {
            // Recorded so the test can assert the caller's own token was forwarded. A service that
            // asked with its own credentials could ask about anybody.
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (!reachable.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            String patientId = exchange.getRequestURI().getPath()
                    .substring("/care-relationships/".length());
            byte[] body = ("{\"patientId\":\"" + patientId + "\",\"related\":" + related.get() + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        scheduling.start();
    }

    @AfterAll
    static void stopStub() {
        scheduling.stop(0);
    }

    @DynamicPropertySource
    static void narrowing(DynamicPropertyRegistry registry) {
        registry.add("hms.care-team.narrow-patient-records", () -> true);
        registry.add("hms.scheduling.base-url",
                () -> "http://127.0.0.1:" + scheduling.getAddress().getPort());
    }

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

    /** An order raised as ADMIN, which is not narrowed, so the fixture never fights the guard. */
    private JsonNode anOrder() throws Exception {
        related.set(true);
        reachable.set(true);
        String response = mockMvc.perform(post("/imaging/orders").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-RAD-"
                                        + UUID.randomUUID().toString().substring(0, 8),
                                "procedureCode", "XR_CHEST_PA",
                                "clinicalQuestion",
                                "Shortness of breath since this morning, query effusion"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    @DisplayName("a clinician looking after the patient reads the study; one who is not cannot")
    void narrowsToTheCareTeam() throws Exception {
        String id = anOrder().get("id").asString();

        related.set(true);
        mockMvc.perform(get("/imaging/orders/" + id).with(as("DOCTOR")))
                .andExpect(status().isOk());

        related.set(false);
        mockMvc.perform(get("/imaging/orders/" + id).with(as("DOCTOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the caller's own token is forwarded, not a service credential")
    void forwardsTheCallersToken() throws Exception {
        String id = anOrder().get("id").asString();
        seenAuthorization.set(null);

        related.set(true);
        mockMvc.perform(get("/imaging/orders/" + id).with(as("DOCTOR")))
                .andExpect(status().isOk());

        // The endpoint takes no user id: it answers about whoever the token names. So a forwarded
        // token is what stops this service being able to ask about anybody but its caller.
        assertThat(seenAuthorization.get()).startsWith("Bearer ");
    }

    @Test
    @DisplayName("an unreachable scheduling-service refuses the read rather than opening it")
    void failsClosed() throws Exception {
        String id = anOrder().get("id").asString();

        reachable.set(false);
        // The cost of this policy is real and is the right way round: while scheduling-service is
        // down a doctor cannot read a radiology report. Failing open would make an outage a
        // platform-wide privacy hole, at the worst possible moment for one.
        mockMvc.perform(get("/imaging/orders/" + id).with(as("DOCTOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the department is not narrowed: the worklist and the unmatched list still read")
    void theDepartmentIsNotNarrowed() throws Exception {
        anOrder();

        // No care relationship anywhere, and the stub would say no if asked.
        related.set(false);

        mockMvc.perform(get("/imaging/worklist").with(as("RADIOGRAPHER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/imaging/studies/unmatched").with(as("RADIOGRAPHER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/imaging/reporting-queue").with(as("RADIOLOGIST")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an administrator is not narrowed either, and the README says so rather than not")
    void administratorsAreNotNarrowed() throws Exception {
        String id = anOrder().get("id").asString();

        related.set(false);
        // Stated as a gap rather than pretended away: narrowing the account that repairs the
        // platform is a different decision, and this is the row that would notice it changing.
        mockMvc.perform(get("/imaging/orders/" + id).with(as("ADMIN")))
                .andExpect(status().isOk());
    }
}
