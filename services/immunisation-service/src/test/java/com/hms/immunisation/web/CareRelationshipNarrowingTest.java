package com.hms.immunisation.web;

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
import java.time.LocalDate;
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
import tools.jackson.databind.ObjectMapper;

/**
 * The care-team narrowing, from the register's side of it.
 *
 * <p>The rest of this module's suite runs with the narrowing off, because scheduling-service is not
 * running in an immunisation unit test and a fail-closed client would turn every clinician read into
 * a 403 about the wrong thing. So it is proven here, against a stub standing in for
 * scheduling-service — which is what lets the interesting cases be tested at all: the answer being
 * no, and the answer being unavailable.
 *
 * <p>Deliberately the same shape as the laboratory's and radiology's tests of the same name. This is
 * the fourth service to ask the question, and a reader who knows one should recognise the others.
 *
 * <p><strong>The case that is this module's alone:</strong> the narrowing applies to
 * <em>recording</em> a dose as well as reading one. Every other service narrows reads and leaves
 * writes to the role gate, because writing to a chart is itself evidence of providing care. A
 * vaccine is different in one specific way — a register is a lifetime record and a dose recorded
 * against the wrong patient is a dose that child will not be called for again — so the same guard
 * runs on the way in.
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

    private static String lotNo() {
        return "NARROW-" + Math.abs(UUID.randomUUID().getMostSignificantBits());
    }

    /** A dose recorded as ADMIN, which is not narrowed, so the fixture never fights the guard. */
    private UUID aDoseFor(UUID patientId) throws Exception {
        related.set(true);
        reachable.set(true);
        String lot = lotNo();
        mockMvc.perform(post("/vaccines/lots").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productCode", "BCG",
                                "lotNo", lot,
                                "expiresOn", LocalDate.now().plusYears(1).toString(),
                                "quantity", 5))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/immunisations").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", patientId.toString(),
                                "patientMrn", "MRN-IMM-"
                                        + UUID.randomUUID().toString().substring(0, 8),
                                "productCode", "BCG",
                                "lotNo", lot,
                                "givenOn", LocalDate.now().toString(),
                                "site", "Left arm"))))
                .andExpect(status().isCreated());
        return patientId;
    }

    @Test
    @DisplayName("a clinician looking after the patient reads the register; one who is not cannot")
    void narrowsToTheCareTeam() throws Exception {
        UUID patientId = aDoseFor(UUID.randomUUID());

        related.set(true);
        mockMvc.perform(get("/immunisations/patients/" + patientId).with(as("DOCTOR")))
                .andExpect(status().isOk());

        related.set(false);
        mockMvc.perform(get("/immunisations/patients/" + patientId).with(as("DOCTOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("recording a dose is narrowed too, which no other module does")
    void theWritePathIsNarrowedAsWell() throws Exception {
        // Every other service narrows reads and leaves writes to the role gate, because writing to
        // a chart is itself evidence of providing care. A register is different: it is a lifetime
        // record, and a dose recorded against the wrong patient is a dose that child will not be
        // called for again -- so the guard runs on the way in.
        related.set(false);
        reachable.set(true);
        mockMvc.perform(post("/immunisations").with(as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", UUID.randomUUID().toString(),
                                "patientMrn", "MRN-IMM-NARROW",
                                "productCode", "BCG",
                                "lotNo", "anything",
                                "givenOn", LocalDate.now().toString(),
                                "site", "Left arm"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the caller's own token is forwarded, not a service credential")
    void forwardsTheCallersToken() throws Exception {
        UUID patientId = aDoseFor(UUID.randomUUID());
        seenAuthorization.set(null);

        related.set(true);
        mockMvc.perform(get("/immunisations/patients/" + patientId).with(as("DOCTOR")))
                .andExpect(status().isOk());

        // The endpoint takes no user id: it answers about whoever the token names. So a forwarded
        // token is what stops this service being able to ask about anybody but its caller.
        assertThat(seenAuthorization.get()).startsWith("Bearer ");
    }

    @Test
    @DisplayName("an unreachable scheduling-service refuses the read rather than opening it")
    void failsClosed() throws Exception {
        UUID patientId = aDoseFor(UUID.randomUUID());

        reachable.set(false);
        // The cost is real and is the right way round: while scheduling-service is down a doctor
        // cannot read an immunisation history. Failing open would make an outage a platform-wide
        // privacy hole, at the worst possible moment for one.
        mockMvc.perform(get("/immunisations/patients/" + patientId).with(as("DOCTOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the catalogue and the fridge are not narrowed: they have no patient in them")
    void thedepartmentIsNotNarrowed() throws Exception {
        related.set(false);

        // Nothing to narrow to. A vaccine catalogue is a list of names and a lot is a vial in a
        // fridge; neither has a patient to have a relationship with, and a cold chain that a nurse
        // could only read for their own patients would not be a cold chain.
        mockMvc.perform(get("/vaccines/products").with(as("NURSE")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/vaccines/lots").param("productCode", "BCG").with(as("NURSE")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an administrator is not narrowed either, and the README says so rather than not")
    void administratorsAreNotNarrowed() throws Exception {
        UUID patientId = aDoseFor(UUID.randomUUID());

        related.set(false);
        // Stated as a gap rather than pretended away: narrowing the account that repairs the
        // platform is a different decision, and this is the row that would notice it changing.
        mockMvc.perform(get("/immunisations/patients/" + patientId).with(as("ADMIN")))
                .andExpect(status().isOk());
    }
}
