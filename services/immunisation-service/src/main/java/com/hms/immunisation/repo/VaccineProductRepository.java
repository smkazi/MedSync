package com.hms.immunisation.repo;

import com.hms.immunisation.domain.VaccineProduct;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VaccineProductRepository extends JpaRepository<VaccineProduct, UUID> {

    Optional<VaccineProduct> findByCode(String code);

    List<VaccineProduct> findByActiveTrueOrderByNameAsc();
}
