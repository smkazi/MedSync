package com.hms.billing.repo;

import com.hms.billing.domain.TaxRate;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaxRateRepository extends JpaRepository<TaxRate, UUID> {

    /**
     * The rate for a code, on a date.
     *
     * <p>Resolved by date rather than by "the current one", which is the whole reason these are
     * rows: an invoice raised last year must carry the rate that applied then, and a rate changed
     * by statute in April must not rewrite March's invoices.
     */
    @Query("""
            select r from TaxRate r
             where r.code = :code
               and r.effectiveFrom <= :on
               and (r.effectiveTo is null or r.effectiveTo > :on)
             order by r.effectiveFrom desc
            """)
    List<TaxRate> effectiveOn(@Param("code") String code, @Param("on") LocalDate on);

    List<TaxRate> findAllByOrderByCodeAscEffectiveFromDesc();
}
