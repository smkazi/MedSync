package com.hms.billing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
 * A patient's own bills, through the portal.
 *
 * <p>Read-only, and that is the interesting part. Every other portal surface answers "what may this
 * person see"; this one also has to answer "what may this person do", and the answer is nothing.
 * Taking money needs a payment gateway with live merchant credentials, and a Pay-now button that
 * settled an invoice without receiving anything would balance the day book against money that does
 * not exist.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalBillingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder
                        .subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user")
                        .claim("roles", List.of(roles)))
                .authorities(authorities);
    }

    private static RequestPostProcessor asPatient(UUID patientId) {
        return jwt().jwt(builder -> builder
                        .subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "MRN-BILL-1")
                        .claim("roles", List.of("PATIENT"))
                        .claim("patient_id", patientId.toString()))
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_PATIENT")));
    }

    /** An issued invoice with one consultation line, for a fresh synthetic patient. */
    private JsonNode issuedInvoice(UUID patientId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientId", patientId.toString());
        body.put("patientMrn", "MRN-PORTAL-" + UUID.randomUUID().toString().substring(0, 8));
        JsonNode draft = objectMapper.readTree(mockMvc.perform(post("/invoices").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String id = draft.get("id").asString();
        mockMvc.perform(post("/invoices/" + id + "/lines").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("chargeItemCode", "CONSULT_OP", "qty", "1"))))
                .andExpect(status().isOk());
        return objectMapper.readTree(
                mockMvc.perform(post("/invoices/" + id + "/issue").with(as("CASHIER")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a patient sees their own bill, line by line, with the tax shown")
    void aPatientSeesTheirOwnBill() throws Exception {
        UUID patient = UUID.randomUUID();
        JsonNode invoice = issuedInvoice(patient);

        // The staff shape, reused deliberately: an invoice is a document the hospital has already
        // handed the patient on paper, and every field on it is one they are entitled to check.
        mockMvc.perform(get("/portal/invoices/" + invoice.get("id").asString()).with(asPatient(patient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(invoice.get("number").asString()))
                .andExpect(jsonPath("$.lines[0].chargeItemCode").value("CONSULT_OP"))
                .andExpect(jsonPath("$.lines[0].taxAmount").exists())
                .andExpect(jsonPath("$.outstanding").exists());
    }

    @Test
    @DisplayName("the balance is one number and it leaves cancelled invoices out")
    void theBalanceExcludesCancelledInvoices() throws Exception {
        UUID patient = UUID.randomUUID();
        JsonNode first = issuedInvoice(patient);
        JsonNode second = issuedInvoice(patient);

        String balance = mockMvc.perform(get("/portal/invoices/balance").with(asPatient(patient)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        BigDecimal both = new BigDecimal(objectMapper.readTree(balance).get("outstanding").asString());
        assertThat(both).isEqualByComparingTo(
                new BigDecimal(first.get("total").asString()).add(new BigDecimal(second.get("total").asString())));

        mockMvc.perform(post("/invoices/" + second.get("id").asString() + "/cancel").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "Raised in error"))))
                .andExpect(status().isOk());

        String after = mockMvc.perform(get("/portal/invoices/balance").with(asPatient(patient)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(after).get("outstanding").asString()))
                .isEqualByComparingTo(new BigDecimal(first.get("total").asString()));

        // Cancelled is still listed, though. A patient told they owed money and then told they did
        // not should be able to see both, and a bill that quietly vanishes is how a billing
        // department loses an argument it was going to win.
        mockMvc.perform(get("/portal/invoices").with(asPatient(patient)))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("another patient's invoice is not found, rather than forbidden")
    void anotherPatientsInvoiceIsNotFound() throws Exception {
        UUID patient = UUID.randomUUID();
        JsonNode invoice = issuedInvoice(patient);

        mockMvc.perform(get("/portal/invoices/" + invoice.get("id").asString())
                        .with(asPatient(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a patient cannot pay, cancel or raise anything through the portal")
    void thePortalIsReadOnly() throws Exception {
        UUID patient = UUID.randomUUID();
        JsonNode invoice = issuedInvoice(patient);
        String id = invoice.get("id").asString();

        // Not "no button on the screen": there is no endpoint. A payment recorded without money
        // arriving would settle the invoice, balance the day book, and be discovered at the month
        // end by somebody who could not tell which of the two records was wrong.
        mockMvc.perform(post("/invoices/" + id + "/payments").with(asPatient(patient))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", "1.00", "method", "CASH"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/invoices/" + id + "/cancel").with(asPatient(patient))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "I would rather not"))))
                .andExpect(status().isForbidden());
        // A well-formed body on purpose: an empty one is rejected by argument binding before
        // method security is reached, which would make this assertion prove validation rather than
        // authorisation.
        mockMvc.perform(post("/invoices").with(asPatient(patient))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patientId", patient.toString(), "patientMrn", "MRN-BILL-1"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("staff are refused the portal, and a patient is refused the day book")
    void neitherSideCanUseTheOther() throws Exception {
        UUID patient = UUID.randomUUID();
        mockMvc.perform(get("/portal/invoices").with(as("CASHIER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/portal/invoices").with(as("ADMIN"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/day-book").with(asPatient(patient))).andExpect(status().isForbidden());
    }
}
