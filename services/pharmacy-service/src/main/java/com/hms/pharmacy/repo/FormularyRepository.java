package com.hms.pharmacy.repo;

import com.hms.pharmacy.domain.Formulary;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FormularyRepository extends JpaRepository<Formulary, UUID> {

    Optional<Formulary> findByCode(String code);

    List<Formulary> findByCodeIn(Collection<String> codes);

    /**
     * The catalogue, searchable.
     *
     * <p>A contains-pattern rather than a nullable parameter, for the reason
     * {@code QueryPatterns} records: an untyped null makes PostgreSQL infer {@code bytea} and the
     * query fails rather than matching everything.
     */
    @Query("""
            select f from Formulary f
             where lower(f.name) like :pattern
               and (:includeInactive = true or f.active = true)
             order by f.name
            """)
    List<Formulary> search(@Param("pattern") String pattern,
                           @Param("includeInactive") boolean includeInactive);
}
