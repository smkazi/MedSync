package com.hms.pharmacy.repo;

import com.hms.pharmacy.domain.StockBatch;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockBatchRepository extends JpaRepository<StockBatch, UUID> {

    /**
     * Usable stock for a drug, first expiry first.
     *
     * <p>FEFO rather than FIFO, and the difference matters: stock received later can expire sooner,
     * and picking by arrival order is how a pharmacy ends up destroying the box it should have used
     * and dispensing the one it should have destroyed.
     *
     * <p>The expiry filter is in the query, not in the caller: a batch that expires today is not
     * offered at all, so there is no path on which an expired batch reaches a picker and has to be
     * refused later.
     */
    @Query("""
            select b from StockBatch b
             where b.drugCode = :drugCode
               and b.quantityOnHand > 0
               and b.expiresOn > :today
             order by b.expiresOn, b.receivedOn
            """)
    List<StockBatch> usable(@Param("drugCode") String drugCode, @Param("today") LocalDate today);

    List<StockBatch> findByDrugCodeOrderByExpiresOn(String drugCode);

    @Query("select b from StockBatch b where b.quantityOnHand > 0 order by b.expiresOn")
    List<StockBatch> onHand();

    /**
     * Takes units out of a batch, or reports that it could not.
     *
     * <p>One conditional statement, the {@code recordFailedLogin} shape, and for the same reason:
     * two pharmacists dispensing the last box both read the same {@code quantity_on_hand}, both
     * subtract, and both save — and the one who commits second silently restores the stock the
     * first one took. The {@code WHERE} clause is what decides between them, and a zero return is
     * the refusal.
     *
     * <p>The expiry is re-checked here as well as in {@link #usable}, because the gap between
     * choosing a batch and writing the dispense can span midnight, and a batch that was usable when
     * the picker read it is not usable when the row is written.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StockBatch b
               set b.quantityOnHand = b.quantityOnHand - :units
             where b.id = :id
               and b.quantityOnHand >= :units
               and b.expiresOn > :today
            """)
    int take(@Param("id") UUID id, @Param("units") int units, @Param("today") LocalDate today);
}
