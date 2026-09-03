package com.hms.identity.repo;

import com.hms.identity.domain.AuditLogEntry;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    boolean existsByEventId(UUID eventId);

    /**
     * Filtered audit search. As in {@link UserRepository#search}, every string parameter is a
     * non-null LIKE pattern so an unfiltered query cannot send an untyped null.
     *
     * <p>The actor predicate is {@code :actorId = '%' or a.actorId like :actorId}, and the shape
     * matters for the same reason it does in {@code UserRepository}. It used to read
     * {@code a.actorId like :actorId or a.actorId is null} — so that an unfiltered search would
     * still return system-initiated rows, which carry no actor. But that made every null-actor row
     * match <em>every</em> actor filter: asking "what did this person do" returned their actions
     * plus every action nobody did. On an audit report that is not a cosmetic problem; it is the
     * report answering a different question from the one asked. Comparing the pattern to
     * {@code '%'} says "no filter was supplied" explicitly, which is what was meant.
     *
     * <p>The date range is half-open and compared directly rather than through a LIKE pattern —
     * {@code QueryPatterns} is for strings, and the precedent for dates on this platform is
     * {@code AppointmentService}'s {@code >= :from and < :to} over an instant. Username goes
     * through {@code QueryPatterns.contains} because a person types a fragment of a name.
     */
    @Query("""
            select a from AuditLogEntry a
            where a.entity like :entity
              and a.action like :action
              and (:actorId = '%' or a.actorId like :actorId)
              and (:username = '%' or lower(a.username) like :username)
              and a.occurredAt >= :from
              and a.occurredAt < :to
            order by a.occurredAt desc
            """)
    Page<AuditLogEntry> search(@Param("entity") String entity, @Param("action") String action,
                               @Param("actorId") String actorId, @Param("username") String username,
                               @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
