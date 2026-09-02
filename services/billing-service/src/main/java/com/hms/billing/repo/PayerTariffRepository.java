package com.hms.billing.repo;

import com.hms.billing.domain.PayerTariff;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayerTariffRepository extends JpaRepository<PayerTariff, PayerTariff.Key> {

    List<PayerTariff> findByIdPayerCodeOrderByIdChargeItemCodeAsc(String payerCode);
}
