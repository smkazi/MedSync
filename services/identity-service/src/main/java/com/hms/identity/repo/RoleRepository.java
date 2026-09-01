package com.hms.identity.repo;

import com.hms.identity.domain.Role;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByCode(String code);

    Set<Role> findByCodeIn(Collection<String> codes);
}
