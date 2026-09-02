package com.hms.billing.repo;

import com.hms.billing.domain.Payer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayerRepository extends JpaRepository<Payer, UUID> {

    Optional<Payer> findByCode(String code);

    List<Payer> findAllByOrderByNameAsc();
}
