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
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Invoice i
               set i.amountPaid = i.amountPaid + :amount,
                   i.status = case when i.amountPaid + :amount >= i.total then 'PAID' else i.status end
             where i.id = :id
               and i.status in ('DRAFT', 'ISSUED')
               and i.amountPaid + :amount <= i.total
            """)
    int applyPayment(@Param("id") UUID id, @Param("amount") BigDecimal amount);

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

    /** What is still owed across every open invoice, for the day's cash-up. */
    @Query("""
            select coalesce(sum(i.total - i.amountPaid), 0) from Invoice i
             where i.status in ('DRAFT', 'ISSUED')
               and i.invoiceDate <= :on
            """)
    BigDecimal outstandingAsOf(@Param("on") LocalDate on);
}
