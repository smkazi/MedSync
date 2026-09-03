package com.hms.billing.repo;

import com.hms.billing.domain.BillingEnums.InvoiceStatus;
import com.hms.billing.domain.Invoice;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByNumber(String number);

    List<Invoice> findByPatientIdOrderByCreatedAtDesc(UUID patientId);

    List<Invoice> findByStatusInOrderByInvoiceDateAsc(Collection<InvoiceStatus> statuses);

    /**
     * The open draft for an encounter, if there is one.
     *
     * <p>Charge capture needs exactly this: an in-patient's charges accumulate onto one draft for
     * the length of the stay, and a second draft for the same encounter would split one stay across
     * two bills.
     */
    @Query("""
            select i from Invoice i
             where i.encounterId = :encounterId
               and i.status = 'DRAFT'
             order by i.createdAt
            """)
    List<Invoice> openDraftsFor(@Param("encounterId") UUID encounterId);

    /**
     * Takes a payment, or reports that it cannot.
     *
     * <p><strong>One conditional statement, and it is the control.</strong> Two cashiers taking the
     * same balance both read the same {@code amount_paid}; both add their amount; the one who
     * commits second silently restores the balance the first collected. The {@code WHERE} clause
     * decides between them, and a zero return is the refusal — which the service turns into a 409
     * naming what is actually outstanding.
     *
     * <p>The status moves in the same statement. Deriving PAID from the numbers in a second
     * statement would leave a window in which an invoice was fully paid and still said ISSUED, and
     * a receipt printed in that window would be wrong.
     *
     * <p>Both the cap and the PAID test are against {@code total - credited} rather than
     * {@code total}. Once part of a bill has been credited that part is not owed, so collecting it
     * would be taking money for a charge the hospital has withdrawn in writing — and an invoice
     * whose credited remainder has been settled would otherwise sit at ISSUED for ever, owing
     * nothing that anybody could pay.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Invoice i
               set i.amountPaid = i.amountPaid + :amount,
                   i.status = case when i.amountPaid + :amount >= i.total - i.credited
                                   then 'PAID' else i.status end
             where i.id = :id
               and i.status in ('DRAFT', 'ISSUED')
               and i.amountPaid + :amount <= i.total - i.credited
            """)
    int applyPayment(@Param("id") UUID id, @Param("amount") BigDecimal amount);

    /**
     * Credits part or all of an invoice, or reports that it cannot.
     *
     * <p>The same shape as {@link #applyPayment} and for the same reason: two administrators
     * crediting the same bill at once both read the same {@code credited}, and without the
     * {@code WHERE} clause the second silently overwrites the first — forgiving a bill twice, which
     * on a paid invoice becomes a licence to refund money the hospital never took.
     *
     * <p>A credit is allowed on a cancelled invoice's siblings but not on a cancelled invoice
     * itself: there is nothing to forgive on a bill that was withdrawn whole.
     *
     * <p>The status moves here too. When a credit leaves nothing owed on an invoice that has been
     * paid down, the invoice is settled and must say so — otherwise "ISSUED" would mean both
     * "somebody owes this" and "nobody owes anything", and the first thing a receivables report
     * does is trust that word.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Invoice i
               set i.credited = i.credited + :amount,
                   i.status = case when i.amountPaid >= i.total - (i.credited + :amount)
                                   then 'PAID' else i.status end
             where i.id = :id
               and i.status in ('DRAFT', 'ISSUED', 'PAID')
               and i.credited + :amount <= i.total
            """)
    int applyCredit(@Param("id") UUID id, @Param("amount") BigDecimal amount);

    /**
     * Pays money back, or reports that it cannot.
     *
     * <p>Two conditions, and the second is the control this whole slice turns on. Money cannot be
     * given back that was never received — the mirror of the overpayment guard — and it cannot be
     * given back beyond what a credit note has said is not owed. Without the second, a cashier
     * could hand cash out against a bill still recorded as owed in full, and the patient would owe
     * it again the next time anybody read the invoice.
     *
     * <p>Issuing a credit note is an administrator's act and paying a refund a cashier's, so a
     * cashier cannot both forgive a debt and pay out against it. An administrator can — {@code
     * BILLING_WRITE} is {@code hasAnyRole('ADMIN','CASHIER')} — and the README says so rather than
     * claiming a separation the roles do not enforce. What holds whoever acts is that money never
     * leaves without a credit note behind it: checked here, in the database's own CHECK, and in the
     * service, three times deliberately, because this is the statement that moves money out of the
     * building.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Invoice i
               set i.refunded = i.refunded + :amount
             where i.id = :id
               and i.refunded + :amount <= i.amountPaid
               and i.refunded + :amount <= i.credited
            """)
    int applyRefund(@Param("id") UUID id, @Param("amount") BigDecimal amount);

    /**
     * What was billed on a day, and how many invoices carried it.
     *
     * <p>Cancelled invoices are excluded: a bill that was withdrawn was never money the hospital
     * was owed, and leaving it in the day's billed figure makes every later reconciliation argue
     * with the ledger.
     */
    @Query("""
            select coalesce(sum(i.total), 0) as billed, count(i) as invoices from Invoice i
             where i.invoiceDate = :on
               and i.status <> 'CANCELLED'
            """)
    DayTotals billedOn(@Param("on") LocalDate on);

    /**
     * The two numbers {@link #billedOn} answers.
     *
     * <p>An interface rather than an {@code Object[]}: the array version compiled, ran, and threw
     * an index-out-of-bounds at runtime because the shape it comes back in is not the shape it
     * looks like. A projection makes the columns names the compiler can check.
     */
    interface DayTotals {

        BigDecimal getBilled();

        long getInvoices();
    }

    /**
     * What is still owed across every open invoice, for the day's cash-up.
     *
     * <p>Credited amounts come off, and refunded amounts go back on: what is owed is what was
     * charged less what was withdrawn in writing, against what the hospital has actually kept. A
     * receivables figure that ignored credit notes would chase money the hospital has already said
     * is not owed — which is worse than a wrong number, because somebody acts on it.
     *
     * <p>Clamped at zero per invoice with {@code greatest}, not in aggregate. An invoice in credit
     * owes nothing; letting it contribute a negative would quietly net off a debt on a different
     * patient's bill, and the total would balance while both rows were wrong.
     */
    @Query("""
            select coalesce(sum(greatest((i.total - i.credited) - (i.amountPaid - i.refunded), 0)), 0)
              from Invoice i
             where i.status in ('DRAFT', 'ISSUED')
               and i.invoiceDate <= :on
            """)
    BigDecimal outstandingAsOf(@Param("on") LocalDate on);
}
