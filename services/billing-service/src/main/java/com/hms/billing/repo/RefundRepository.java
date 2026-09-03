package com.hms.billing.repo;

import com.hms.billing.domain.BillingEnums.PaymentMethod;
import com.hms.billing.domain.Refund;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByInvoiceIdOrderByPaidAt(UUID invoiceId);

    /**
     * What was paid back in a window, split by how it left.
     *
     * <p>Split by method for exactly the reason collections are: cash handed back comes out of the
     * same drawer the cash total is counted against, and a card refund appears in the terminal's
     * own batch. A cash-up that knows what came in and not what went out balances against a figure
     * that was never true.
     */
    @Query("""
            select r.method, coalesce(sum(r.amount), 0), count(r)
              from Refund r
             where r.paidAt >= :from and r.paidAt < :to
             group by r.method
             order by r.method
            """)
    List<Object[]> totalsByMethod(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select coalesce(sum(r.amount), 0) from Refund r
             where r.paidAt >= :from and r.paidAt < :to
            """)
    BigDecimal refundedBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** Typed reading of a {@link #totalsByMethod} row, so the cast lives in one place. */
    record MethodTotalRow(PaymentMethod method, BigDecimal amount, int count) {

        public static MethodTotalRow of(Object[] row) {
            return new MethodTotalRow((PaymentMethod) row[0], (BigDecimal) row[1],
                    ((Number) row[2]).intValue());
        }
    }
}
