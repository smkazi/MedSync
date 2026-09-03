package com.hms.billing.service;

import com.hms.billing.domain.Money;
import com.hms.billing.repo.CreditNoteRepository;
import com.hms.billing.repo.InvoiceRepository;
import com.hms.billing.repo.PaymentRepository;
import com.hms.billing.repo.RefundRepository;
import com.hms.billing.web.dto.BillingDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The day's position: billed, collected, outstanding, and the collections split by method.
 *
 * <p>The split is the point. A cash-up reconciles the cash figure against what is in the drawer and
 * the card figure against the terminal's own batch; a single grand total reconciles against
 * nothing, and a discrepancy nobody can localise is a discrepancy nobody finds.
 *
 * <p>Money out is reported beside money in rather than netted into it. A day that collected eight
 * hundred and refunded two hundred took six hundred, and all three numbers matter: the first
 * reconciles against receipts, the second against the refund vouchers, and only the third against
 * the drawer. A single net figure would balance and explain nothing.
 *
 * <p>The day is bounded by {@link BillingClock} rather than by the JVM's default zone. A payment's
 * {@code received_at} is an instant, and "the 14th" is a different window in Kolkata than in UTC —
 * a hospital cashing up at 8pm local would otherwise see the evening's takings land in tomorrow.
 * That was not hypothetical: this class read the configured zone while invoices were dated in the
 * JVM's, and a day's billing read zero while its collections read eight hundred.
 */
@Service
public class DayBookService {

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final CreditNoteRepository creditNotes;
    private final RefundRepository refunds;
    private final BillingClock clock;

    public DayBookService(InvoiceRepository invoices, PaymentRepository payments,
                          CreditNoteRepository creditNotes, RefundRepository refunds,
                          BillingClock clock) {
        this.invoices = invoices;
        this.payments = payments;
        this.creditNotes = creditNotes;
        this.refunds = refunds;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BillingDtos.DayBookResponse on(LocalDate day) {
        LocalDate on = day == null ? clock.today() : day;
        Instant from = clock.startOf(on);
        Instant to = clock.startOf(on.plusDays(1));

        InvoiceRepository.DayTotals billed = invoices.billedOn(on);
        List<BillingDtos.MethodTotal> byMethod = payments.totalsByMethod(from, to).stream()
                .map(PaymentRepository.MethodTotalRow::of)
                .map(row -> new BillingDtos.MethodTotal(row.method(), Money.scale(row.amount()),
                        row.count()))
                .toList();
        int paymentCount = byMethod.stream().mapToInt(BillingDtos.MethodTotal::count).sum();

        List<BillingDtos.MethodTotal> refundsByMethod = refunds.totalsByMethod(from, to).stream()
                .map(RefundRepository.MethodTotalRow::of)
                .map(row -> new BillingDtos.MethodTotal(row.method(), Money.scale(row.amount()),
                        row.count()))
                .toList();
        int refundCount = refundsByMethod.stream().mapToInt(BillingDtos.MethodTotal::count).sum();

        BigDecimal collected = Money.scale(payments.collectedBetween(from, to));
        BigDecimal refunded = Money.scale(refunds.refundedBetween(from, to));

        return new BillingDtos.DayBookResponse(on,
                Money.scale(billed == null ? BigDecimal.ZERO : billed.getBilled()),
                Money.scale(creditNotes.creditedBetween(from, to)),
                collected, refunded, Money.scale(collected.subtract(refunded)),
                Money.scale(invoices.outstandingAsOf(on)),
                billed == null ? 0 : (int) billed.getInvoices(), paymentCount, refundCount,
                byMethod, refundsByMethod);
    }
}
