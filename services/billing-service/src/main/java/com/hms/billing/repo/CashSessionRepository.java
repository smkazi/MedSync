package com.hms.billing.repo;

import com.hms.billing.domain.BillingEnums.CashSessionStatus;
import com.hms.billing.domain.CashSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashSessionRepository extends JpaRepository<CashSession, UUID> {

    /**
     * The caller's open drawer, if they have one.
     *
     * <p>At most one can exist: a partial unique index on the table enforces it, so this returning
     * an Optional rather than a list is a fact about the schema and not an assumption.
     */
    Optional<CashSession> findByCashierAndStatus(String cashier, CashSessionStatus status);

    Page<CashSession> findByCashierOrderByOpenedAtDesc(String cashier, Pageable pageable);

    Page<CashSession> findAllByOrderByOpenedAtDesc(Pageable pageable);

    /** Every drawer still open, which is what an administrator wants at the end of a day. */
    List<CashSession> findByStatusOrderByOpenedAtAsc(CashSessionStatus status);

    /**
     * What is in the drawer according to the platform: the float, plus cash taken, less cash paid
     * back out.
     *
     * <p>Cash only, and by session id rather than by a time window. Card and UPI settle into the
     * acquirer's batch and cannot be short by a miscount, so counting them would invite somebody to
     * type the expected figure back in and call it reconciled.
     */
    @Query("""
            select coalesce((select sum(p.amount) from Payment p
                              where p.cashSessionId = :session and p.method = 'CASH'), 0)
                 - coalesce((select sum(r.amount) from Refund r
                              where r.cashSessionId = :session and r.method = 'CASH'), 0)
            """)
    java.math.BigDecimal netCashIn(@Param("session") UUID session);

    /**
     * Everything that moved through one drawer, split by method.
     *
     * <p>The non-cash rows are not counted against anything — they are what the cashier ticks off
     * against the terminal's own batch, which is a different act from counting notes and is worth
     * being on the same piece of paper.
     */
    @Query("""
            select p.method, coalesce(sum(p.amount), 0), count(p)
              from Payment p
             where p.cashSessionId = :session
             group by p.method
             order by p.method
            """)
    List<Object[]> paymentTotalsByMethod(@Param("session") UUID session);

    @Query("""
            select r.method, coalesce(sum(r.amount), 0), count(r)
              from Refund r
             where r.cashSessionId = :session
             group by r.method
             order by r.method
            """)
    List<Object[]> refundTotalsByMethod(@Param("session") UUID session);

    /**
     * Money taken in a window that belongs to no drawer at all.
     *
     * <p>A payment is never refused for want of an open shift — the money is real whether or not
     * somebody remembered to open one — so this is the total that would otherwise vanish between
     * the day book and every cash-up of that day. Reported rather than hidden: an unattributed
     * figure is the one a reconciliation has to start from.
     */
    @Query("""
            select coalesce(sum(p.amount), 0) from Payment p
             where p.cashSessionId is null
               and p.method = 'CASH'
               and p.receivedAt >= :from and p.receivedAt < :to
            """)
    java.math.BigDecimal unattributedCashBetween(@Param("from") Instant from,
                                                 @Param("to") Instant to);
}
