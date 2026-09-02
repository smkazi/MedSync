package com.hms.billing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The revenue cycle against a real database.
 *
 * <p>Everything asserted here is enforced by the schema rather than by care taken in a service, and
 * that is the reason these tests need PostgreSQL: money that cannot be overpaid, a charge that
 * cannot post twice, one claim per invoice, and {@code numeric(14,2)} instead of a floating point.
 * The arithmetic itself is tested without any of this in {@code MoneyAndPricerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillingApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID patientId;
    private String mrn;

    @BeforeEach
    void aPatientNobodyElseIsBilling() {
        patientId = UUID.randomUUID();
        mrn = "MRN-BIL-" + Math.abs(System.nanoTime() % 1_000_000);
    }

    // ---- the money -----------------------------------------------------------

    @Test
    @DisplayName("an invoice prices from the charge list, numbers itself, and issues")
    void pricesAndIssues() throws Exception {
        JsonNode invoice = draft(null, null);
        assertThat(invoice.get("number").asString())
                .as("the financial year's series, derived from the invoice's own date")
                .matches("INV/\\d{4}-\\d{2}/\\d{5}");
        assertThat(invoice.get("status").asString()).isEqualTo("DRAFT");

        JsonNode withLine = addLine(invoice, "CONSULT_OP", "1", null);
        JsonNode line = withLine.get("lines").get(0);
        assertThat(money(line, "unitPrice"))
                .isEqualByComparingTo("500.00");
        assertThat(line.get("taxPercent").asDouble())
                .as("a clinical service is GST-exempt in India, and that is the platform's default")
                .isZero();
        assertThat(money(withLine, "total"))
                .isEqualByComparingTo("500.00");
        assertThat(money(withLine, "outstanding"))
                .isEqualByComparingTo("500.00");

        JsonNode issued = issue(withLine);
        assertThat(issued.get("status").asString()).isEqualTo("ISSUED");
        assertThat(issued.get("issuedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("an issued invoice takes no further lines: a bill somebody was given cannot grow")
    void issuedInvoiceIsClosedToLines() throws Exception {
        JsonNode issued = issue(addLine(draft(null, null), "CONSULT_OP", "1", null));

        String refusal = mockMvc.perform(post("/invoices/" + id(issued) + "/lines")
                        .with(as("CASHIER")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("chargeItemCode", "CONSULT_FU", "qty", 1))))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(refusal).contains("Raise a new invoice for further charges");
    }

    @Test
    @DisplayName("a TPA's invoice prices from the tariff, not the charge list")
    void tariffBeatsTheListPrice() throws Exception {
        JsonNode tpa = addLine(draft("TPA_A", null), "CONSULT_OP", "1", null);
        assertThat(money(tpa.get("lines").get(0), "unitPrice"))
                .as("TPA_A has agreed 400 for a consultation the list prices at 500")
                .isEqualByComparingTo("400.00");

        JsonNode self = addLine(draft("SELF", null), "CONSULT_OP", "1", null);
        assertThat(money(self.get("lines").get(0), "unitPrice")).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("a tax-exempt payer exempts a taxable line, whatever the charge item says")
    void exemptPayerExemptsTheLine() throws Exception {
        JsonNode exempt = addLine(draft("SCHEME_A", null), "CONSUMABLE", "1", null);
        assertThat(exempt.get("lines").get(0).get("taxPercent").asDouble()).isZero();
        assertThat(money(exempt, "taxTotal"))
                .isEqualByComparingTo("0.00");

        JsonNode taxed = addLine(draft("SELF", null), "CONSUMABLE", "1", null);
        assertThat(taxed.get("lines").get(0).get("taxPercent").asDouble()).isEqualTo(12.0);
        assertThat(money(taxed, "taxTotal"))
                .isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("GST is the rate in force on the invoice's own date, not today's")
    void taxIsAsOfTheInvoiceDate() throws Exception {
        // A rate code and a charge item of this test's own, so no other test's arithmetic depends
        // on them. The rate starts today — the service refuses a rate starting in the past, for
        // the reason its own message gives — so an invoice dated before it was created is taxed at
        // the rate in force then, which is none.
        String rateCode = "T" + UUID.randomUUID().toString().substring(0, 6)
                .toUpperCase(Locale.ROOT);
        addRate(rateCode, "20.00", LocalDate.now());

        String itemCode = "TI" + UUID.randomUUID().toString().substring(0, 6)
                .toUpperCase(Locale.ROOT);
        mockMvc.perform(post("/charge-items").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", itemCode, "name", "A taxable thing", "unitPrice", "100.00",
                                "taxable", true, "taxRateCode", rateCode))))
                .andExpect(status().isCreated());

        JsonNode backDated = addLine(draft(null, LocalDate.now().minusDays(10)), itemCode, "1",
                null);
        assertThat(backDated.get("lines").get(0).get("taxPercent").asDouble())
                .as("no rate was in force on that date, and today's is not applied backwards")
                .isZero();
        assertThat(money(backDated, "total"))
                .isEqualByComparingTo("100.00");

        JsonNode today = addLine(draft(null, LocalDate.now()), itemCode, "1", null);
        assertThat(today.get("lines").get(0).get("taxPercent").asDouble()).isEqualTo(20.0);
        assertThat(money(today, "total"))
                .isEqualByComparingTo("120.00");

        // And a rate cannot be back-dated into a period that has already been invoiced, which is
        // the other half of the same rule.
        String refusal = mockMvc.perform(post("/tax-rates").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", rateCode, "name", "Retroactive", "percent", "30.00",
                                "effectiveFrom", LocalDate.now().minusDays(5).toString()))))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(refusal).contains("cannot start in the past");
    }

    @Test
    @DisplayName("the wire carries two decimal places, because a receipt is rendered from it")
    void theWireCarriesTwoDecimals() throws Exception {
        // A brand-new draft first: its totals are zero, and zero has to be rendered "0.00" like
        // every other amount. This is what caught the entity initialising its money fields to an
        // unscaled BigDecimal.ZERO, which reached a screen as "0" beside "500.00".
        String empty = mockMvc.perform(get("/invoices/" + id(draft(null, null)))
                        .with(as("CASHIER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(empty).contains("\"total\":0.00").contains("\"subtotal\":0.00");

        JsonNode invoice = addLine(draft(null, null), "CONSULT_OP", "1", null);

        String body = mockMvc.perform(get("/invoices/" + id(invoice)).with(as("CASHIER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body)
                .as("scale preserved end to end: numeric(14,2) in the database, BigDecimal in "
                        + "Java, and two decimals in the JSON a screen prints")
                .contains("\"total\":500.00")
                .contains("\"amountPaid\":0.00");
    }

    @Test
    @DisplayName("a discount comes off before tax, so no tax is collected on money nobody paid")
    void discountReducesTheTaxableAmount() throws Exception {
        JsonNode discounted = addLine(draft("SELF", null), "CONSUMABLE", "2", "50.00");
        JsonNode line = discounted.get("lines").get(0);
        assertThat(money(line, "taxAmount"))
                .as("12% of (200 - 50), not of 200")
                .isEqualByComparingTo("18.00");
        assertThat(money(line, "lineTotal"))
                .isEqualByComparingTo("168.00");
    }

    @Test
    @DisplayName("a discount larger than the line is refused by the database, not absorbed")
    void discountCannotExceedTheLine() throws Exception {
        JsonNode invoice = draft(null, null);
        mockMvc.perform(post("/invoices/" + id(invoice) + "/lines").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "chargeItemCode", "CONSULT_OP", "qty", 1, "discount", "600.00"))))
                .andExpect(status().is4xxClientError());
    }

    // ---- payments ------------------------------------------------------------

    @Test
    @DisplayName("paying the balance in full moves the invoice to PAID in the same statement")
    void fullPaymentSettles() throws Exception {
        JsonNode issued = issue(addLine(draft(null, null), "CONSULT_OP", "1", null));

        JsonNode paid = pay(issued, "500.00", "CASH", 200);
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(money(paid, "amountPaid"))
                .isEqualByComparingTo("500.00");
        assertThat(money(paid, "outstanding"))
                .isEqualByComparingTo("0.00");
        assertThat(paid.get("payments").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("an overpayment is refused, naming what is actually outstanding")
    void overpaymentIsRefused() throws Exception {
        JsonNode issued = issue(addLine(draft(null, null), "CONSULT_OP", "1", null));
        pay(issued, "200.00", "CASH", 200);

        String refusal = mockMvc.perform(post("/invoices/" + id(issued) + "/payments")
                        .with(as("CASHIER")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("amount", "400.00", "method", "CASH"))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        assertThat(refusal).contains("300.00");
    }

    @Test
    @DisplayName("two cashiers taking the same balance: one succeeds, and the invoice is paid once")
    void concurrentPaymentsCannotBothLand() throws Exception {
        JsonNode issued = issue(addLine(draft(null, null), "CONSULT_OP", "1", null));
        int contenders = 6;

        List<Integer> statuses = inParallel(contenders, () ->
                mockMvc.perform(post("/invoices/" + id(issued) + "/payments").with(as("CASHIER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("amount", "500.00", "method", "CASH"))))
                        .andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(status -> status == 200).count())
                .as("exactly one of %d cashiers collects the balance", contenders)
                .isEqualTo(1);
        assertThat(statuses.stream().filter(status -> status == 409).count())
                .isEqualTo(contenders - 1L);

        JsonNode after = read(id(issued));
        assertThat(money(after, "amountPaid"))
                .as("no lost update: the balance was collected exactly once")
                .isEqualByComparingTo("500.00");
        assertThat(after.get("status").asString()).isEqualTo("PAID");
        assertThat(after.get("payments").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("an invoice with money against it cannot be cancelled")
    void paidInvoiceCannotBeCancelled() throws Exception {
        JsonNode issued = issue(addLine(draft(null, null), "CONSULT_OP", "1", null));
        pay(issued, "100.00", "CARD", 200);

        String refusal = mockMvc.perform(post("/invoices/" + id(issued) + "/cancel")
                        .with(as("CASHIER")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "raised twice"))))
                .andExpect(status().is4xxClientError())
                .andReturn().getResponse().getContentAsString();
        assertThat(refusal).contains("100.00");
    }

    // ---- charge capture ------------------------------------------------------

    @Test
    @DisplayName("a replayed charge writes nothing and says so, rather than billing twice")
    void chargeCaptureIsIdempotent() throws Exception {
        UUID appointment = UUID.randomUUID();

        JsonNode first = postCharge("APPOINTMENT", appointment, "CONSULT_OP", 1);
        assertThat(first.get("alreadyPosted").asBoolean()).isFalse();

        JsonNode replay = postCharge("APPOINTMENT", appointment, "CONSULT_OP", 1);
        assertThat(replay.get("alreadyPosted").asBoolean())
                .as("the second delivery of one event is a duplicate, not a second consultation")
                .isTrue();

        JsonNode invoice = read(first.get("invoiceId").asString());
        assertThat(invoice.get("lines").size()).isEqualTo(1);
        assertThat(money(invoice, "total"))
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("charges for one encounter accumulate onto one draft, because a stay is one bill")
    void chargesForAnEncounterShareADraft() throws Exception {
        UUID encounter = UUID.randomUUID();

        JsonNode bed = postCharge("ADMISSION", UUID.randomUUID(), "BED_GEN", 3, encounter);
        JsonNode lab = postCharge("LAB_ORDER", UUID.randomUUID(), "LAB_CBC", 1, encounter);
        assertThat(lab.get("invoiceId").asString())
                .as("the second charge landed on the draft the first one opened")
                .isEqualTo(bed.get("invoiceId").asString());

        JsonNode invoice = read(bed.get("invoiceId").asString());
        assertThat(invoice.get("lines").size()).isEqualTo(2);
        assertThat(money(invoice, "total"))
                .isEqualByComparingTo("4850.00");
    }

    @Test
    @DisplayName("posting a charge nobody has priced is a 404, not a guessed number")
    void unpricedChargeIsRefused() throws Exception {
        mockMvc.perform(post("/charges").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(charge("MANUAL",
                                UUID.randomUUID(), "NOT_A_CHARGE_ITEM", 1, null))))
                .andExpect(status().isNotFound());
    }

    // ---- claims --------------------------------------------------------------

    @Test
    @DisplayName("a claim is raised, submitted, settled short, and the shortfall is reported")
    void claimLifecycle() throws Exception {
        JsonNode issued = issue(addLine(draft("TPA_A", null), "CONSULT_OP", "1", null));

        JsonNode claim = raiseClaim(id(issued), "PA-12345", 201);
        assertThat(money(claim, "claimedAmount"))
                .as("the outstanding balance, so a co-pay already collected is not claimed again")
                .isEqualByComparingTo("400.00");
        assertThat(claim.get("status").asString()).isEqualTo("DRAFT");

        JsonNode submitted = objectMapper.readTree(
                mockMvc.perform(post("/claims/" + claim.get("id").asString() + "/submit")
                                .with(as("CASHIER")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertThat(submitted.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(submitted.get("submittedAt").isNull()).isFalse();

        JsonNode settled = objectMapper.readTree(
                mockMvc.perform(post("/claims/" + claim.get("id").asString() + "/settle")
                                .with(as("CASHIER")).contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("settledAmount", "300.00"))))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertThat(settled.get("status").asString())
                .as("settled short is its own status: somebody has to decide about the balance")
                .isEqualTo("PARTIALLY_SETTLED");
        assertThat(money(settled, "shortfall"))
                .isEqualByComparingTo("100.00");

        JsonNode invoice = read(id(issued));
        assertThat(money(invoice, "amountPaid"))
                .as("the settlement is money, and it went onto the invoice as a payment")
                .isEqualByComparingTo("300.00");
        assertThat(invoice.get("payments").get(0).get("method").asString()).isEqualTo("INSURANCE");
        assertThat(money(invoice, "outstanding"))
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("a payer that requires pre-authorisation is not claimed without a number")
    void preauthIsRequiredWhenThePayerSaysSo() throws Exception {
        JsonNode issued = issue(addLine(draft("TPA_A", null), "CONSULT_OP", "1", null));

        String refusal = mockMvc.perform(post("/claims").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("invoiceId", id(issued)))))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(refusal).contains("pre-authorisation");
    }

    @Test
    @DisplayName("a self-paying patient's invoice has nobody to claim from")
    void selfPayHasNoClaim() throws Exception {
        JsonNode issued = issue(addLine(draft("SELF", null), "CONSULT_OP", "1", null));

        String refusal = mockMvc.perform(post("/claims").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("invoiceId", id(issued), "preauthNo", "PA-1"))))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(refusal).contains("does not settle directly");
    }

    @Test
    @DisplayName("a draft cannot be claimed for, because it is still collecting charges")
    void draftCannotBeClaimed() throws Exception {
        JsonNode draft = addLine(draft("TPA_A", null), "CONSULT_OP", "1", null);

        mockMvc.perform(post("/claims").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("invoiceId", id(draft), "preauthNo", "PA-2"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("one invoice, one claim: the second is refused naming the first")
    void oneClaimPerInvoice() throws Exception {
        JsonNode issued = issue(addLine(draft("TPA_A", null), "CONSULT_OP", "1", null));
        raiseClaim(id(issued), "PA-3", 201);

        String refusal = mockMvc.perform(post("/claims").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("invoiceId", id(issued), "preauthNo", "PA-4"))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        assertThat(refusal).contains("already carries a claim");
    }

    @Test
    @DisplayName("a payer cannot settle more than was claimed")
    void settlementCannotExceedTheClaim() throws Exception {
        JsonNode issued = issue(addLine(draft("TPA_A", null), "CONSULT_OP", "1", null));
        JsonNode claim = raiseClaim(id(issued), "PA-5", 201);
        mockMvc.perform(post("/claims/" + claim.get("id").asString() + "/submit").with(as("CASHIER")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/claims/" + claim.get("id").asString() + "/settle").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("settledAmount", "999.00"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a denial has to say why, and takes no money")
    void denialCarriesAReason() throws Exception {
        JsonNode issued = issue(addLine(draft("TPA_A", null), "CONSULT_OP", "1", null));
        JsonNode claim = raiseClaim(id(issued), "PA-6", 201);
        mockMvc.perform(post("/claims/" + claim.get("id").asString() + "/submit").with(as("CASHIER")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/claims/" + claim.get("id").asString() + "/deny").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", ""))))
                .andExpect(status().isBadRequest());

        JsonNode denied = objectMapper.readTree(
                mockMvc.perform(post("/claims/" + claim.get("id").asString() + "/deny")
                                .with(as("CASHIER")).contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("reason", "Policy lapsed before admission"))))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertThat(denied.get("status").asString()).isEqualTo("DENIED");
        assertThat(denied.get("denialReason").asString()).contains("lapsed");
        assertThat(money(read(id(issued)), "amountPaid")).isEqualByComparingTo("0.00");
    }

    // ---- who may do what -----------------------------------------------------

    @Test
    @DisplayName("a doctor may read what a patient was billed and may not take their money")
    void cliniciansReadAndDoNotWrite() throws Exception {
        JsonNode issued = issue(addLine(draft(null, null), "CONSULT_OP", "1", null));

        mockMvc.perform(get("/invoices/" + id(issued)).with(as("DOCTOR")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/invoices/" + id(issued) + "/payments").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("amount", "500.00", "method", "CASH"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/invoices").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("patientId", patientId, "patientMrn", mrn))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a cashier takes money and does not set prices")
    void cashiersCollectAndDoNotPrice() throws Exception {
        mockMvc.perform(get("/charge-items").with(as("CASHIER"))).andExpect(status().isOk());

        mockMvc.perform(post("/charge-items").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "CHEAP", "name", "Discounted everything",
                                "unitPrice", "1.00"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/charge-items/CONSULT_OP").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("unitPrice", "1.00"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/tax-rates").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "GST_0", "name", "None at all", "percent", "0.00",
                                "effectiveFrom", LocalDate.now().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the laboratory and the pharmacy are not offered the money at all")
    void benchAndPharmacyAreOut() throws Exception {
        mockMvc.perform(get("/invoices").with(as("LAB_TECH"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/day-book").with(as("PHARMACIST"))).andExpect(status().isForbidden());
    }

    // ---- the day's position --------------------------------------------------

    @Test
    @DisplayName("the day book totals what was collected, split by how it arrived")
    void dayBookSplitsByMethod() throws Exception {
        JsonNode first = issue(addLine(draft(null, null), "CONSULT_OP", "1", null));
        pay(first, "500.00", "CASH", 200);
        JsonNode second = issue(addLine(draft(null, null), "CONSULT_FU", "1", null));
        pay(second, "300.00", "UPI", 200);

        JsonNode book = objectMapper.readTree(
                mockMvc.perform(get("/day-book").with(as("CASHIER")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        // Other tests in this class bill and collect against the same day, so these are floors
        // rather than equalities — a shared ledger asserted absolutely is a test that fails when
        // somebody adds another one.
        assertThat(new BigDecimal(book.get("collected").asString()))
                .isGreaterThanOrEqualTo(new BigDecimal("800.00"));
        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        for (JsonNode row : book.get("byMethod")) {
            byMethod.put(row.get("method").asString(), new BigDecimal(row.get("amount").asString()));
        }
        assertThat(byMethod).containsKeys("CASH", "UPI");
        assertThat(byMethod.get("UPI")).isGreaterThanOrEqualTo(new BigDecimal("300.00"));
    }

    // ---- helpers -------------------------------------------------------------

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-cashier"))
                .authorities(authorities);
    }

    /**
     * An amount off a response.
     *
     * <p>Compared as a {@link BigDecimal} rather than as text, because the JSON tree parses a
     * decimal into a double and {@code 500.00} reads back as {@code "500.0"} — a fact about
     * Jackson's tree model and not about the wire, which carries the scale (asserted once in
     * {@link #theWireCarriesTwoDecimals()}). Comparing text here would have been a test that
     * failed for a reason unconnected to any money.
     */
    private static BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asString());
    }

    private static String id(JsonNode invoice) {
        return invoice.get("id").asString();
    }

    private JsonNode draft(String payerCode, LocalDate invoiceDate) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientId", patientId);
        body.put("patientMrn", mrn);
        if (payerCode != null) {
            body.put("payerCode", payerCode);
        }
        if (invoiceDate != null) {
            body.put("invoiceDate", invoiceDate.toString());
        }
        return objectMapper.readTree(mockMvc.perform(post("/invoices").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode addLine(JsonNode invoice, String chargeItemCode, String qty, String discount)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chargeItemCode", chargeItemCode);
        body.put("qty", qty);
        if (discount != null) {
            body.put("discount", discount);
        }
        return objectMapper.readTree(
                mockMvc.perform(post("/invoices/" + id(invoice) + "/lines").with(as("CASHIER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
    }

    private JsonNode issue(JsonNode invoice) throws Exception {
        return objectMapper.readTree(
                mockMvc.perform(post("/invoices/" + id(invoice) + "/issue").with(as("CASHIER")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
    }

    private JsonNode pay(JsonNode invoice, String amount, String method, int expected)
            throws Exception {
        return objectMapper.readTree(
                mockMvc.perform(post("/invoices/" + id(invoice) + "/payments").with(as("CASHIER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("amount", amount, "method", method))))
                        .andExpect(status().is(expected))
                        .andReturn().getResponse().getContentAsString());
    }

    private JsonNode read(String invoiceId) throws Exception {
        return objectMapper.readTree(
                mockMvc.perform(get("/invoices/" + invoiceId).with(as("CASHIER")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
    }

    private Map<String, Object> charge(String sourceType, UUID sourceId, String code, int qty,
                                       UUID encounterId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceType", sourceType);
        body.put("sourceId", sourceId);
        body.put("patientId", patientId);
        body.put("patientMrn", mrn);
        body.put("chargeItemCode", code);
        body.put("qty", qty);
        if (encounterId != null) {
            body.put("encounterId", encounterId);
        }
        return body;
    }

    private JsonNode postCharge(String sourceType, UUID sourceId, String code, int qty)
            throws Exception {
        return postCharge(sourceType, sourceId, code, qty, null);
    }

    private JsonNode postCharge(String sourceType, UUID sourceId, String code, int qty,
                                UUID encounterId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/charges").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                charge(sourceType, sourceId, code, qty, encounterId))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode raiseClaim(String invoiceId, String preauthNo, int expected) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/claims").with(as("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("invoiceId", invoiceId, "preauthNo", preauthNo))))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString());
    }

    private void addRate(String code, String percent, LocalDate from) throws Exception {
        mockMvc.perform(post("/tax-rates").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code, "name", "Test rate " + percent,
                                "percent", percent, "effectiveFrom", from.toString()))))
                .andExpect(status().isCreated());
    }

    private <T> List<T> inParallel(int n, Callable<T> task) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> futures = pool.invokeAll(java.util.Collections.nCopies(n, task));
            List<T> results = new ArrayList<>(n);
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
