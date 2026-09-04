package com.hms.immunisation.repo;

import com.hms.immunisation.domain.VaccineLot;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VaccineLotRepository extends JpaRepository<VaccineLot, UUID> {

    Optional<VaccineLot> findByProductCodeAndLotNo(String productCode, String lotNo);

    /**
     * Usable lots for a product, earliest expiry first.
     *
     * <p>First expiry, first out, matching the partial index the migration builds — and the reason
     * is not tidiness: a fridge full of vaccine that expires next week and a picker who takes the
     * newest vial is a fridge that throws vaccine away.
     */
    @Query("""
            select l from VaccineLot l
            where l.productCode = :productCode
              and l.withdrawnReason is null
              and l.quantityOnHand > 0
              and l.expiresOn >= :today
            order by l.expiresOn asc
            """)
    List<VaccineLot> usableFor(@Param("productCode") String productCode,
                               @Param("today") LocalDate today);

    List<VaccineLot> findByProductCodeOrderByExpiresOnAsc(String productCode);

    /** The query a bad week runs: this lot was recalled, and these are the vials of it. */
    List<VaccineLot> findByLotNo(String lotNo);
}
