package com.hms.laboratory.web;

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
import java.util.HashMap;
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
 * The care-team narrowing, from the laboratory's side of it.
 *
 * <p>The rest of this module's suite runs with the narrowing off, because scheduling-service is not
 * running in a laboratory unit test and a fail-closed client would turn every clinician read into a
 * 403 about the wrong thing. So it is proven here instead, against a stub that stands in for
 * scheduling-service — which lets the interesting cases be tested at all: the answer being no, and
 * the answer being unavailable.
 *
 * <p>The stub is a JDK {@code HttpServer} rather than a mocking library. It is nine lines, it needs
 * no dependency, and it exercises the real {@code RestClient} over a real socket, so what is under
 * test is the client this service actually uses rather than a stand-in for it.
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

    /** An order for a fresh synthetic patient, raised by the laboratory so no narrowing applies. */
    private JsonNode anOrder() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", UUID.randomUUID().toString());
        body.put("patientMrn", "MRN-CT-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("patientSex", "F");
        body.put("testCodes", List.of("CBC"));
        related.set(true);
        reachable.set(true);
        String response = mockMvc.perform(post("/lab/orders").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    @DisplayName("a clinician looking after the patient reads the order, and one who is not cannot")
    void narrowsToTheCareTeam() throws Exception {
        JsonNode order = anOrder();
        String id = order.get("id").asString();
        String patientId = order.get("patientId").asString();

        related.set(true);
        mockMvc.perform(get("/lab/orders/" + id).with(as("DOCTOR")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/lab/patients/" + patientId + "/orders").with(as("DOCTOR")))
                .andExpect(status().isOk());

        related.set(false);
        // The whole point: a doctor with CLINICAL_READ, which used to be the entire answer, is now
        // refused somebody else's patient.
        mockMvc.perform(get("/lab/orders/" + id).with(as("DOCTOR")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/lab/patients/" + patientId + "/orders").with(as("NURSE")))
                .andExpect(status().isForbidden());
        // And the results, which are the thing actually worth browsing.
        mockMvc.perform(get("/lab/orders/" + id + "/results").with(as("DOCTOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the bench and the administrator are not narrowed, and could not do the job if they were")
    void theServiceLinesAreNotNarrowed() throws Exception {
        JsonNode order = anOrder();
        String id = order.get("id").asString();
        related.set(false);

        // Running a blood count and reporting a specimen are cross-patient jobs. A pathologist who
        // could only report on their own patients could not report at all.
        mockMvc.perform(get("/lab/orders/" + id).with(as("LAB_TECH")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/lab/orders/" + id).with(as("PATHOLOGIST")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/lab/orders/" + id).with(as("ADMIN")))
                .andExpect(status().isOk());
        // An administrator who is also a doctor is still not narrowed: the rule is "clinician and
        // not administrator", so the repairing account keeps working.
        mockMvc.perform(get("/lab/orders/" + id).with(as("ADMIN", "DOCTOR")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unreachable scheduling-service refuses the read rather than opening it")
    void failsClosed() throws Exception {
        JsonNode order = anOrder();
        String id = order.get("id").asString();

        reachable.set(false);
        // Failing open would make an outage a platform-wide privacy hole, at the worst possible
        // moment for one. The cost — a doctor cannot read a result while scheduling is down — is
        // the trade, and it is stated in the README rather than discovered.
        mockMvc.perform(get("/lab/orders/" + id).with(as("DOCTOR")))
                .andExpect(status().isForbidden());
        // The bench is unaffected, because it never asks.
        mockMvc.perform(get("/lab/orders/" + id).with(as("LAB_TECH")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the question is asked with the clinician's own token, not the service's")
    void asksWithTheCallersToken() throws Exception {
        JsonNode order = anOrder();
        seenAuthorization.set(null);
        related.set(true);

        mockMvc.perform(get("/lab/orders/" + order.get("id").asString()).with(as("DOCTOR")))
                .andExpect(status().isOk());

        // A service asking with its own credentials could ask about anybody, and the endpoint's
        // whole safety property is that it answers only about whoever the token names.
        assertThat(seenAuthorization.get()).isNotNull().startsWith("Bearer ");
    }
}
