package com.hms.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.billing.service.Pricer;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic, with no database and no clock.
 *
 * <p>Worth testing on its own because every failure here is silent. A total that differs from the
 * sum of its lines by one paisa does not crash anything; it produces a reconciliation that never
 * closes, and a patient who is right when they say the bill does not add up.
 */
class MoneyAndPricerTest {

    @Test
    @DisplayName("rounding is HALF_UP, which is what the person checking the bill does by hand")
    void roundsHalfUp() {
        assertThat(Money.scale(new BigDecimal("10.005"))).isEqualByComparingTo("10.01");
        assertThat(Money.scale(new BigDecimal("10.004"))).isEqualByComparingTo("10.00");
        // The case banker's rounding gets "right" and a receipt gets wrong: 2.5 paisa is a paisa.
        assertThat(Money.scale(new BigDecimal("0.025"))).isEqualByComparingTo("0.03");
        assertThat(Money.scale(null)).isEqualByComparingTo("0.00");
        assertThat(Money.scale(new BigDecimal("7"))).hasToString("7.00");
    }

    @Test
    @DisplayName("tax is rounded once on the line, not per unit and not on the invoice")
    void roundsTaxOncePerLine() {
        // 7 units at 33.33, GST 5%: the taxable amount is 233.31 and 5% of it is 11.6655.
        BigDecimal taxable = new BigDecimal("33.33").multiply(new BigDecimal("7"));
        assertThat(Money.taxOn(taxable, new BigDecimal("5"))).isEqualByComparingTo("11.67");

        // Rounding per unit first would have given 7 x 1.67 = 11.69 — two paisa of error the
        // quantity magnified, on one line of one invoice.
        BigDecimal perUnitThenMultiplied =
                Money.taxOn(new BigDecimal("33.33"), new BigDecimal("5"))
                        .multiply(new BigDecimal("7"));
        assertThat(perUnitThenMultiplied).isEqualByComparingTo("11.69");
    }

    @Test
    @DisplayName("a rate of zero is no tax, and an absent amount is not an exception")
    void handlesZeroAndNull() {
        assertThat(Money.taxOn(new BigDecimal("500.00"), BigDecimal.ZERO))
                .isEqualByComparingTo("0.00");
        assertThat(Money.taxOn(null, new BigDecimal("18"))).isEqualByComparingTo("0.00");
        assertThat(Money.taxOn(new BigDecimal("500.00"), null)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a payer's agreed tariff beats the list price, and says which it used")
    void tariffBeatsList() {
        ChargeItem consult = item("CONSULT_OP", "500.00", false, null);
        Payer tpa = new Payer("TPA_A", "A third-party administrator", true, true, true, false);

        Pricer.Priced list = Pricer.price(consult, tpa, Optional.empty(), Optional.empty());
        assertThat(list.unitPrice()).isEqualByComparingTo("500.00");
        assertThat(list.source()).isEqualTo(Pricer.LIST);

        Pricer.Priced agreed = Pricer.price(consult, tpa, Optional.of(new BigDecimal("400.00")),
                Optional.empty());
        assertThat(agreed.unitPrice()).isEqualByComparingTo("400.00");
        assertThat(agreed.source()).isEqualTo(Pricer.TARIFF);
    }

    @Test
    @DisplayName("a taxable item carries the rate it is given, and an exempt one carries none")
    void taxFollowsTheItem() {
        ChargeItem medicine = item("PHARM_X", "120.00", true, "GST_5");
        Pricer.Priced taxed = Pricer.price(medicine, null, Optional.empty(),
                Optional.of(new BigDecimal("5")));
        assertThat(taxed.taxPercent()).isEqualByComparingTo("5");

        // A clinical service: exempt in India, and the platform's default.
        ChargeItem consult = item("CONSULT_OP", "500.00", false, null);
        Pricer.Priced exempt = Pricer.price(consult, null, Optional.empty(),
                Optional.of(new BigDecimal("18")));
        assertThat(exempt.taxPercent()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a tax-exempt payer exempts the line whatever the item says")
    void payerExemptionWins() {
        ChargeItem medicine = item("PHARM_X", "120.00", true, "GST_5");
        Payer scheme = new Payer("SCHEME_A", "A government scheme", true, false, true, true);

        Pricer.Priced priced = Pricer.price(medicine, scheme, Optional.empty(),
                Optional.of(new BigDecimal("5")));
        assertThat(priced.taxPercent()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a line's own arithmetic: tax on the discounted amount, never on the gross")
    void taxesTheDiscountedAmount() {
        // 2 x 1000, 200 off, GST 12%. Taxable is 1800 and the tax is 216 — taxing the gross would
        // have collected 240, which is tax on 200 rupees nobody paid.
        InvoiceLine line = new InvoiceLine("CONSUMABLE", "Dressing pack", new BigDecimal("2"),
                new BigDecimal("1000.00"), new BigDecimal("200.00"), new BigDecimal("12"));

        assertThat(line.getTaxAmount()).isEqualByComparingTo("216.00");
        assertThat(line.getLineTotal()).isEqualByComparingTo("2016.00");
    }

    private static ChargeItem item(String code, String price, boolean taxable, String rateCode) {
        return new ChargeItem(code, code, "GEN", new BigDecimal(price), taxable, rateCode);
    }
}
