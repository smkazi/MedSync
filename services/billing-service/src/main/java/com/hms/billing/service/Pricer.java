package com.hms.billing.service;

import com.hms.billing.domain.ChargeItem;
import com.hms.billing.domain.Money;
import com.hms.billing.domain.Payer;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * What one line costs, and what tax it carries.
 *
 * <p>A pure function over the four inputs that decide it, deliberately: the charge item, the payer,
 * the payer's tariff if there is one, and the tax rate in force on the invoice's date. No
 * repository and no clock, so the arithmetic can be read and tested on its own — the same reasoning
 * that put {@code News2Calculator} and {@code AllergyChecker} beside their services rather than
 * inside them.
 *
 * <p>Three rules, in this order, and the order matters:
 *
 * <ol>
 *   <li><strong>A tariff beats the list price.</strong> That is what a tariff is for: a payer has
 *       agreed a number, and billing them the list price is a claim that will be short-paid.</li>
 *   <li><strong>Tax is charged on the discounted amount</strong>, not on the gross. Taxing the list
 *       price and then discounting collects tax on money nobody paid.</li>
 *   <li><strong>A tax-exempt payer exempts the line</strong> whatever the item says. A government
 *       scheme's exemption is a property of who is paying, not of what was done.</li>
 * </ol>
 */
public final class Pricer {

    private Pricer() {
    }

    /**
     * @param unitPrice     what one unit costs on this invoice
     * @param taxPercent    the rate that applied on the invoice's date, already resolved
     * @param source        where the price came from, so a screen can say "tariff" rather than
     *                      leaving somebody to wonder why it is not the list price
     */
    public record Priced(BigDecimal unitPrice, BigDecimal taxPercent, String source) {
    }

    public static final String LIST = "list price";
    public static final String TARIFF = "payer tariff";

    /**
     * @param tariffPrice the payer's agreed price, if they have one for this item
     * @param ratePercent the tax rate in force for the item's rate code on the invoice's date, if
     *                    the item is taxable at all. Absent means no tax, which is the ordinary
     *                    case for clinical services in India.
     */
    public static Priced price(ChargeItem item, Payer payer, Optional<BigDecimal> tariffPrice,
                               Optional<BigDecimal> ratePercent) {
        BigDecimal unitPrice = tariffPrice.orElse(item.getUnitPrice());
        String source = tariffPrice.isPresent() ? TARIFF : LIST;

        boolean payerExempt = payer != null && payer.isTaxExempt();
        BigDecimal percent = !item.isTaxable() || payerExempt
                ? BigDecimal.ZERO
                : ratePercent.orElse(BigDecimal.ZERO);

        return new Priced(Money.scale(unitPrice), percent, source);
    }
}
