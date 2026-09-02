package com.hms.billing.repo;

import com.hms.billing.domain.BillingEnums.ClaimStatus;
import com.hms.billing.domain.Claim;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {

    Optional<Claim> findByInvoiceId(UUID invoiceId);

    List<Claim> findByPayerCodeAndStatusInOrderByCreatedAtDesc(String payerCode,
                                                               Collection<ClaimStatus> statuses);

    List<Claim> findByStatusInOrderByCreatedAtDesc(Collection<ClaimStatus> statuses);
}
