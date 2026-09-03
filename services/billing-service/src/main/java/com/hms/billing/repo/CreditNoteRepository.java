package com.hms.billing.repo;

import com.hms.billing.domain.CreditNote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditNoteRepository extends JpaRepository<CreditNote, UUID> {

    List<CreditNote> findByInvoiceIdOrderByIssuedAt(UUID invoiceId);

    /**
     * What was credited in a window.
     *
     * <p>Not split by method, unlike collections: a credit note moves no money and so reconciles
     * against no drawer and no terminal. What it belongs in is the day's *billed* figure, as the
     * amount withdrawn from it — which is the number that explains why what was billed and what is
     * owed disagree.
     *
     * <p>Half-open window, for the reason {@code PaymentRepository.totalsByMethod} records: a
     * document dated exactly midnight belongs to the day starting, and counting it twice is how a
     * reconciliation stops balancing.
     */
    @Query("""
            select coalesce(sum(c.amount), 0) from CreditNote c
             where c.issuedAt >= :from and c.issuedAt < :to
            """)
    BigDecimal creditedBetween(@Param("from") Instant from, @Param("to") Instant to);
}
