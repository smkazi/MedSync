package com.hms.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.domain.AuditLogEntry;
import com.hms.identity.repo.AuditLogRepository;
import com.hms.identity.service.AuditReportService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * The audit report: its filters, and the file it downloads as.
 *
 * <p>Every test writes its rows under an {@code entity} nobody else uses, because this table is
 * append-only, shared with every other test in the module, and grows across runs against the same
 * database. Filtering on that entity is what makes the assertions about counts mean anything.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditReportIntegrationTest {

    private static final String SEED_PASSWORD = "TestPassword!2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditLogRepository auditLog;

    @Autowired
    private org.springframework.core.env.Environment environment;

    private String adminToken;
    private String entity;
    private String actorId;

    @BeforeEach
    void signInAndIsolate() throws Exception {
        this.adminToken = "Bearer " + token("admin");
        this.entity = "Probe-" + UUID.randomUUID().toString().substring(0, 8);
        this.actorId = UUID.randomUUID().toString();
    }

    private String token(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", SEED_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asString();
    }

    private void row(String action, String rowActorId, String username, String detail, Instant when) {
        auditLog.save(new AuditLogEntry(UUID.randomUUID(), "identity-service", action, entity,
                "probe", detail, rowActorId, username, UUID.randomUUID().toString(), when));
    }

    /** The zone the report bounds a day in, so a date assertion is not a bet on the container's. */
    private LocalDate today() {
        return LocalDate.now(ZoneId.of(environment.getProperty("hms.audit.zone", "Asia/Kolkata")));
    }

    @Test
    @DisplayName("filtering by actor excludes the rows nobody did")
    void actorFilterDoesNotMatchSystemRows() throws Exception {
        row("PROBE_BY_PERSON", actorId, "probe.person", "did a thing", Instant.now());
        // A system-initiated row: a scheduler, a device ingest, a refresh carrying no session.
        row("PROBE_BY_SYSTEM", null, "system", "happened by itself", Instant.now());

        // The defect this guards, and it was in the filter itself. The predicate read
        // "a.actorId like :actorId or a.actorId is null", so every null-actor row matched EVERY
        // actor filter: asking what one person did returned their actions plus everything nobody
        // did. On an audit report that is the report answering a different question.
        mockMvc.perform(get("/admin/audit")
                        .param("entity", entity).param("actorId", actorId).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("PROBE_BY_PERSON"))
                // ...and the id is on the row, because a filter you cannot see the value of cannot
                // be checked: an empty result and a mistyped id look identical.
                .andExpect(jsonPath("$.content[0].actorId").value(actorId));

        // Unfiltered, the system row is still there. That is what the broken predicate was for.
        mockMvc.perform(get("/admin/audit")
                        .param("entity", entity).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("username matches a fragment, in any case")
    void usernameFilterIsAContainsMatch() throws Exception {
        row("PROBE_NAMED", actorId, "Dr.Probe.Iqbal", "named row", Instant.now());
        row("PROBE_OTHER", actorId, "somebody.else", "other row", Instant.now());

        mockMvc.perform(get("/admin/audit")
                        .param("entity", entity).param("username", "PROBE.iq").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].username").value("Dr.Probe.Iqbal"));
    }

    @Test
    @DisplayName("the report defaults to a recent window and reaches further back when asked")
    void dateRangeBoundsTheReport() throws Exception {
        Instant longAgo = Instant.now().minus(60, ChronoUnit.DAYS);
        row("PROBE_OLD", actorId, "probe.person", "sixty days ago", longAgo);
        row("PROBE_NEW", actorId, "probe.person", "just now", Instant.now());

        // Unbounded means "the last thirty days", not "everything since the platform was installed".
        mockMvc.perform(get("/admin/audit")
                        .param("entity", entity).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("PROBE_NEW"));

        mockMvc.perform(get("/admin/audit")
                        .param("entity", entity)
                        .param("from", today().minusDays(90).toString())
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // Half-open and inclusive of the whole of `to`: a row stamped this afternoon must appear in
        // a report asked for up to today, not fall off the end of it.
        mockMvc.perform(get("/admin/audit")
                        .param("entity", entity)
                        .param("from", today().toString())
                        .param("to", today().toString())
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("a sign-in is attributed to the person who signed in, not to the system")
    void signInsAreAttributed() throws Exception {
        token("dr.pathan");

        // The defect a browser test found while exercising the new "who" filter: every audit row
        // written before there is a session -- every sign-in, every failed sign-in, every lockout,
        // every burned token family -- read the actor off an empty security context and recorded
        // `system` with the all-zero actor id. 5,262 of 5,746 rows in the development database.
        // The report's headline filter was useless for exactly the actions it exists to answer
        // questions about.
        mockMvc.perform(get("/admin/audit")
                        .param("action", "LOGIN_SUCCEEDED").param("username", "dr.pathan")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.content[0].username").value("dr.pathan"))
                .andExpect(jsonPath("$.content[0].actorId").isNotEmpty());
    }

    @Test
    @DisplayName("a failed sign-in against a name nobody holds is recorded under that name")
    void unknownUsernamesAreRecordedAsTyped() throws Exception {
        String typed = "ghost-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", typed, "password", "definitely-wrong"))))
                .andExpect(status().isUnauthorized());

        // A hundred of these in a row under a hundred different names is what credential stuffing
        // looks like, and it is invisible if they all say `system`. There is no actor id, because
        // there is no account to point at, and that is the honest answer rather than a placeholder.
        mockMvc.perform(get("/admin/audit")
                        .param("action", "LOGIN_FAILED").param("username", typed)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].username").value(typed))
                .andExpect(jsonPath("$.content[0].actorId").doesNotExist());
    }

    @Test
    @DisplayName("the CSV export escapes per RFC 4180 and neutralises what a spreadsheet would run")
    void csvExportIsSafeToOpen() throws Exception {
        row("PROBE_HOSTILE", actorId, "probe.person",
                "=HYPERLINK(\"http://evil\",\"click\")", Instant.now());
        row("PROBE_QUOTED", actorId, "probe.person", "has, a comma and \"quotes\"", Instant.now());

        MvcResult result = mockMvc.perform(get("/admin/audit.csv")
                        .param("entity", entity).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"audit-")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        String csv = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv).startsWith("occurredAt,service,action,entity,entityId,actorId,username,"
                + "correlationId,detail\r\n");
        // Neutralised with a leading apostrophe rather than stripped: the payload is still legible
        // to whoever is investigating, and inert in a spreadsheet.
        assertThat(csv).contains("\"'=HYPERLINK(\"\"http://evil\"\",\"\"click\"\")\"");
        assertThat(csv).contains("\"has, a comma and \"\"quotes\"\"\"");
        assertThat(csv).doesNotContain("TRUNCATED");
    }

    @Test
    @DisplayName("the export is administrators' only, like the report it renders")
    void csvIsAdminOnly() throws Exception {
        mockMvc.perform(get("/admin/audit.csv").header("Authorization", "Bearer " + token("dr.rao")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/audit.csv")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a truncated export says so in the file rather than looking complete")
    void truncationIsAnnounced() {
        for (int i = 0; i < 3; i++) {
            row("PROBE_BULK", actorId, "probe.person", "row " + i, Instant.now());
        }

        // The cap is constructed rather than bound through a property override, and deliberately:
        // a second Spring context costs another connection pool, and this module's own README rule
        // is that the local stack and the test suite together exhaust PostgreSQL's clients. There
        // is nothing about the wiring under test here -- only the arithmetic of the cap.
        AuditReportService capped = new AuditReportService(auditLog, ZoneId.of("Asia/Kolkata"), 1);
        String csv = capped.toCsv(new AuditReportService.Filters(entity, null, null, null, null, null));

        // A file that stopped early and did not say so is how somebody concludes an action never
        // happened.
        assertThat(csv).contains("TRUNCATED");
        assertThat(csv).contains("1 of 3 rows exported");
    }
}
