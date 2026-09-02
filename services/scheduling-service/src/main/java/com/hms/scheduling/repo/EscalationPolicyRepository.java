package com.hms.scheduling.repo;

import com.hms.scheduling.domain.EscalationPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscalationPolicyRepository extends JpaRepository<EscalationPolicy, UUID> {

    Optional<EscalationPolicy> findByBand(String band);

    List<EscalationPolicy> findAllByOrderByBandAsc();
}
