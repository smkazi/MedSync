package com.hms.billing.repo;

import com.hms.billing.domain.BillingEnums.PaymentMethod;
import com.hms.billing.domain.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByInvoiceIdOrderByReceivedAt(UUID invoiceId);

    /**
     * What was collected in a window, split by how it arrived.
     *
     * <p>Split by method because that is how a day is cashed up: the cash total is counted against
     * a drawer, the card total against the terminal's own batch, and a single grand total
     * reconciles against nothing. The window is half-open on purpose — a payment taken at exactly
     * midnight belongs to the day starting, and counting it in both days is how a cash-up stops
     * balancing.
     */
    @Query("""
            select p.method, coalesce(sum(p.amount), 0), count(p)
              from Payment p
             where p.receivedAt >= :from and p.receivedAt < :to
             group by p.method
             order by p.method
            """)
    List<Object[]> totalsByMethod(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select coalesce(sum(p.amount), 0) from Payment p
             where p.receivedAt >= :from and p.receivedAt < :to
            """)
    BigDecimal collectedBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** Typed reading of a {@link #totalsByMethod} row, so the cast lives in one place. */
    record MethodTotalRow(PaymentMethod method, BigDecimal amount, int count) {

        public static MethodTotalRow of(Object[] row) {
            return new MethodTotalRow((PaymentMethod) row[0], (BigDecimal) row[1],
                    ((Number) row[2]).intValue());
        }
    }
}
