package com.hms.apitests.journey;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.Fixtures;
import com.hms.apitests.support.RequiresRunningStack;
import io.restassured.path.json.JsonPath;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The revenue cycle through the gateway, under the identities that really hold it.
 *
 * <p>What this buys over billing-service's own suite is the part that cannot be tested inside one
 * service: that the gateway routes seven top-level nouns to it, that a token issued by
 * identity-service carries CASHIER through the resource server, and that a doctor's token reaches
 * the same endpoints and is refused by them. The separation of duties is the whole point of the
 * module — the person who decides what is owed is not the person who records that it was paid —
 * and a suite that drove it all as an administrator would be testing a system nobody runs.
 */
@DisplayName("the revenue cycle")
class BillingJourneyIT extends RequiresRunningStack {

    private static Fixtures.Patient patient;

    @BeforeAll
    static void seed() {
        patient = Fixtures.registerPatient(Api.RECEPTIONIST, "Bill");
    }

    private static JsonPath draft(String payerCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientId", patient.id());
        body.put("patientMrn", patient.mrn());
        if (payerCode != null) {
            body.put("payerCode", payerCode);
        }
        return given().spec(Api.as(Api.CASHIER))
                .body(body)
                .when().post("/invoices")
                .then().statusCode(201)
                .extract().jsonPath();
    }

    private static JsonPath addLine(String invoiceId, String code, int qty) {
        return given().spec(Api.as(Api.CASHIER))
                .body(Map.of("chargeItemCode", code, "qty", qty))
                .when().post("/invoices/" + invoiceId + "/lines")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    private static JsonPath issue(String invoiceId) {
        return given().spec(Api.as(Api.CASHIER))
                .when().post("/invoices/" + invoiceId + "/issue")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    private static BigDecimal amount(JsonPath json, String path) {
        return new BigDecimal(json.getString(path));
    }

    @Test
    @DisplayName("raised, priced, issued, paid — through the gateway under a cashier's token")
    void theWholeInvoice() {
        JsonPath raised = draft(null);
        assertThat(raised.getString("number")).matches("INV/\\d{4}-\\d{2}/\\d{5}");
        assertThat(raised.getString("status")).isEqualTo("DRAFT");

        String id = raised.getString("id");
        JsonPath priced = addLine(id, "CONSULT_OP", 1);
        assertThat(amount(priced, "total")).isEqualByComparingTo("500.00");
        assertThat(priced.getString("lines[0].taxPercent"))
                .as("a clinical service is GST-exempt in India")
                .isEqualTo("0");

        JsonPath issued = issue(id);
        assertThat(issued.getString("status")).isEqualTo("ISSUED");

        // Overpaying is refused before anything is written, and the refusal names the balance.
        String refusal = given().spec(Api.as(Api.CASHIER))
                .body(Map.of("amount", "900.00", "method", "CASH"))
                .when().post("/invoices/" + id + "/payments")
                .then().statusCode(409)
                .extract().jsonPath().getString("detail");
        assertThat(refusal).contains("500.00");

        JsonPath paid = given().spec(Api.as(Api.CASHIER))
                .body(Map.of("amount", "500.00", "method", "UPI", "reference", "UPI-" + Fixtures.RUN))
                .when().post("/invoices/" + id + "/payments")
                .then().statusCode(200)
                .extract().jsonPath();
        assertThat(paid.getString("status")).isEqualTo("PAID");
        assertThat(amount(paid, "outstanding")).isEqualByComparingTo("0.00");
        assertThat(paid.getString("payments[0].receivedBy"))
                .as("the token's own identity, not whoever the client claimed to be")
                .isEqualTo(Api.CASHIER);
    }

    @Test
    @DisplayName("a payer's tariff prices the invoice, and the claim settles onto it")
    void tariffAndClaim() {
        String id = draft("TPA_A").getString("id");
        JsonPath priced = addLine(id, "CONSULT_OP", 1);
        assertThat(amount(priced, "total"))
                .as("TPA_A has agreed 400 for a consultation the list prices at 500")
                .isEqualByComparingTo("400.00");
        issue(id);

        // Pre-authorisation is this payer's rule, and the claim is refused without one.
        given().spec(Api.as(Api.CASHIER))
                .body(Map.of("invoiceId", id))
                .when().post("/claims")
                .then().statusCode(400);

        String claimId = given().spec(Api.as(Api.CASHIER))
                .body(Map.of("invoiceId", id, "preauthNo", "PA-" + Fixtures.RUN))
                .when().post("/claims")
                .then().statusCode(201)
                .extract().jsonPath().getString("id");

        given().spec(Api.as(Api.CASHIER))
                .when().post("/claims/" + claimId + "/submit")
                .then().statusCode(200);

        JsonPath settled = given().spec(Api.as(Api.CASHIER))
                .body(Map.of("settledAmount", "300.00"))
                .when().post("/claims/" + claimId + "/settle")
                .then().statusCode(200)
                .extract().jsonPath();
        assertThat(settled.getString("status")).isEqualTo("PARTIALLY_SETTLED");
        assertThat(amount(settled, "shortfall")).isEqualByComparingTo("100.00");

        JsonPath invoice = given().spec(Api.as(Api.CASHIER))
                .when().get("/invoices/" + id)
                .then().statusCode(200)
                .extract().jsonPath();
        assertThat(amount(invoice, "amountPaid"))
                .as("a settlement is money, and it lands on the invoice as an insurance payment")
                .isEqualByComparingTo("300.00");
        assertThat(invoice.getString("payments[0].method")).isEqualTo("INSURANCE");
    }

    @Test
    @DisplayName("the same charge, delivered twice, is billed once")
    void chargeCaptureIsIdempotent() {
        String source = java.util.UUID.randomUUID().toString();
        Map<String, Object> charge = Map.of(
                "sourceType", "APPOINTMENT", "sourceId", source,
                "patientId", patient.id(), "patientMrn", patient.mrn(),
                "chargeItemCode", "CONSULT_OP", "qty", 1);

        JsonPath first = given().spec(Api.as(Api.CASHIER)).body(charge)
                .when().post("/charges").then().statusCode(200).extract().jsonPath();
        assertThat(first.getBoolean("alreadyPosted")).isFalse();

        JsonPath replay = given().spec(Api.as(Api.CASHIER)).body(charge)
                .when().post("/charges").then().statusCode(200).extract().jsonPath();
        assertThat(replay.getBoolean("alreadyPosted"))
                .as("a redelivered event is a duplicate, not a second consultation")
                .isTrue();

        JsonPath invoice = given().spec(Api.as(Api.CASHIER))
                .when().get("/invoices/" + first.getString("invoiceId"))
                .then().statusCode(200)
                .extract().jsonPath();
        assertThat(invoice.getList("lines")).hasSize(1);
    }

    @Test
    @DisplayName("a clinician reads the money and cannot touch it; the bench cannot read it at all")
    void separationOfDuties() {
        String id = issue(addLine(draft(null).getString("id"), "CONSULT_OP", 1).getString("id"))
                .getString("id");

        given().spec(Api.as(Api.DOCTOR)).when().get("/invoices/" + id).then().statusCode(200);
        given().spec(Api.as(Api.DOCTOR))
                .body(Map.of("amount", "500.00", "method", "CASH"))
                .when().post("/invoices/" + id + "/payments")
                .then().statusCode(403);

        // Setting prices is an administrator's, not a cashier's: somebody who could discount a
        // procedure to zero and then record it as paid in full would need no accomplice.
        given().spec(Api.as(Api.CASHIER))
                .body(Map.of("unitPrice", "1.00"))
                .when().patch("/charge-items/CONSULT_OP")
                .then().statusCode(403);

        given().spec(Api.as(Api.LAB_TECH)).when().get("/invoices").then().statusCode(403);
        given().spec(Api.as(Api.PHARMACIST)).when().get("/day-book").then().statusCode(403);
    }

    @Test
    @DisplayName("the day book totals the day, split by how the money arrived")
    void theDayBook() {
        String id = issue(addLine(draft(null).getString("id"), "CONSULT_FU", 1).getString("id"))
                .getString("id");
        given().spec(Api.as(Api.CASHIER))
                .body(Map.of("amount", "300.00", "method", "CASH"))
                .when().post("/invoices/" + id + "/payments")
                .then().statusCode(200);

        JsonPath book = given().spec(Api.as(Api.CASHIER))
                .when().get("/day-book")
                .then().statusCode(200)
                .extract().jsonPath();
        assertThat(amount(book, "collected"))
                .as("this run's 300 at least, on a ledger other runs also write to")
                .isGreaterThanOrEqualTo(new BigDecimal("300.00"));
        assertThat(book.getList("byMethod.method")).contains("CASH");
    }
}
