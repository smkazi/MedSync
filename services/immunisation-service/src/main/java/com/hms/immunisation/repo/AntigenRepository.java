package com.hms.immunisation.repo;

import com.hms.immunisation.domain.Antigen;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AntigenRepository extends JpaRepository<Antigen, UUID> {

    Optional<Antigen> findByCode(String code);

    List<Antigen> findByActiveTrueOrderByCodeAsc();
}
