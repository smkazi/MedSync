package com.hms.billing.service;

import com.hms.billing.domain.Money;
import com.hms.billing.repo.CreditNoteRepository;
import com.hms.billing.repo.InvoiceRepository;
import com.hms.billing.repo.PayerRepository;
import com.hms.billing.repo.PaymentRepository;
import com.hms.billing.repo.RefundRepository;
import com.hms.billing.web.dto.BillingDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
 * <p>Receivables ageing lives here too, and not because it is a day's figure — it is the opposite,
 * a position accumulated over months. It is here because it is the same question the day book's
 * outstanding total answers in one number, and the two must agree to the rupee: a report that
 * chases a debt the cash-up says is settled sends somebody to argue with a patient holding a
 * receipt. Sharing a class keeps the arithmetic in one file where a change has to be made twice on
 * purpose rather than forgotten once.
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
    private final PayerRepository payers;
    private final BillingClock clock;

    public DayBookService(InvoiceRepository invoices, PaymentRepository payments,
                          CreditNoteRepository creditNotes, RefundRepository refunds,
                          PayerRepository payers, BillingClock clock) {
        this.invoices = invoices;
        this.payments = payments;
        this.creditNotes = creditNotes;
        this.refunds = refunds;
        this.payers = payers;
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

    /**
     * What is owed, bucketed by age and by payer.
     *
     * <p>Ordered worst first — oldest money at the top, and the largest of it first within that —
     * because a collections list is read from the top and worked downwards, and a list sorted by
     * payer name puts the hopeless debt wherever the alphabet happens to put it.
     *
     * <p>Every total here is summed in {@code BigDecimal} rather than on a screen, and the row
     * total is the sum of that row's own four buckets rather than a fifth query. The buckets are
     * disjoint by construction, so the two can never disagree — which they could if the total came
     * back separately and an invoice sat exactly on a boundary.
     */
    @Transactional(readOnly = true)
    public BillingDtos.ReceivablesResponse receivables(LocalDate day) {
        LocalDate on = day == null ? clock.today() : day;
        List<InvoiceRepository.AgeingRow> rows = invoices.ageingAsOf(on, on.minusDays(30),
                on.minusDays(60), on.minusDays(90));

        Map<String, String> names = payers.findAllByOrderByNameAsc().stream()
                .collect(java.util.stream.Collectors.toMap(payer -> payer.getCode(),
                        payer -> payer.getName(), (first, second) -> first));

        List<BillingDtos.AgeingBucket> buckets = rows.stream()
                .map(row -> bucket(row, names))
                // Oldest first, then largest: the order a clerk works the list in.
                .sorted(Comparator.comparing(BillingDtos.AgeingBucket::days90)
                        .thenComparing(BillingDtos.AgeingBucket::days60)
                        .thenComparing(BillingDtos.AgeingBucket::days30)
                        .thenComparing(BillingDtos.AgeingBucket::total)
                        .reversed())
                .toList();

        BillingDtos.AgeingBucket total = new BillingDtos.AgeingBucket(null, "All payers",
                sum(buckets, BillingDtos.AgeingBucket::current),
                sum(buckets, BillingDtos.AgeingBucket::days30),
                sum(buckets, BillingDtos.AgeingBucket::days60),
                sum(buckets, BillingDtos.AgeingBucket::days90),
                sum(buckets, BillingDtos.AgeingBucket::total),
                buckets.stream().mapToLong(BillingDtos.AgeingBucket::invoices).sum());

        return new BillingDtos.ReceivablesResponse(on, buckets, total);
    }

    /**
     * One payer's row.
     *
     * <p>A null payer code is a self-paying patient and is named rather than dropped: they are the
     * collection everybody forgets, and omitting them would understate the receivable by exactly
     * the amount nobody is chasing. A code with no payer row behind it keeps its code, which is
     * visible enough to be asked about.
     */
    private BillingDtos.AgeingBucket bucket(InvoiceRepository.AgeingRow row,
                                            Map<String, String> names) {
        BigDecimal current = Money.scale(row.getCurrent());
        BigDecimal days30 = Money.scale(row.getDays30());
        BigDecimal days60 = Money.scale(row.getDays60());
        BigDecimal days90 = Money.scale(row.getDays90());
        String code = row.getPayerCode();
        String name = code == null ? "Self-paying" : names.getOrDefault(code, code);
        return new BillingDtos.AgeingBucket(code, name, current, days30, days60, days90,
                Money.scale(current.add(days30).add(days60).add(days90)), row.getInvoices());
    }

    private BigDecimal sum(List<BillingDtos.AgeingBucket> rows,
                           Function<BillingDtos.AgeingBucket, BigDecimal> field) {
        return Money.scale(rows.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
