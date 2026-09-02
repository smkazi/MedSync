package com.hms.billing.repo;

import com.hms.billing.domain.PostedCharge;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostedChargeRepository extends JpaRepository<PostedCharge, PostedCharge.Key> {
}
