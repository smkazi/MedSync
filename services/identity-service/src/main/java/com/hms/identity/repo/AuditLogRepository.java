package com.hms.identity.repo;

import com.hms.identity.domain.AuditLogEntry;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    boolean existsByEventId(UUID eventId);

    /**
     * Filtered audit search. As in {@link UserRepository#search}, every parameter is a
     * non-null LIKE pattern so an unfiltered query cannot send an untyped null.
     */
    @Query("""
            select a from AuditLogEntry a
            where a.entity like :entity
              and a.action like :action
              and (a.actorId like :actorId or a.actorId is null)
            order by a.occurredAt desc
            """)
    Page<AuditLogEntry> search(@Param("entity") String entity, @Param("action") String action,
                              @Param("actorId") String actorId, Pageable pageable);
}
