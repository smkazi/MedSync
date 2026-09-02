package com.hms.billing.repo;

import com.hms.billing.domain.ChargeItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChargeItemRepository extends JpaRepository<ChargeItem, UUID> {

    Optional<ChargeItem> findByCode(String code);

    @Query("""
            select c from ChargeItem c
             where lower(c.name) like :pattern
               and (:includeInactive = true or c.active = true)
             order by c.departmentCode, c.name
            """)
    List<ChargeItem> search(@Param("pattern") String pattern,
                            @Param("includeInactive") boolean includeInactive);
}
