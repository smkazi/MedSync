package com.hms.identity.repo;

import com.hms.identity.domain.SigningKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SigningKeyRepository extends JpaRepository<SigningKey, UUID> {

    Optional<SigningKey> findFirstByActiveTrueOrderByCreatedAtDesc();

    List<SigningKey> findAllByOrderByCreatedAtDesc();
}
