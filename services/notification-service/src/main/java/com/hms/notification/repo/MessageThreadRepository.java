package com.hms.notification.repo;

import com.hms.notification.domain.MessageThread;
import com.hms.notification.domain.NotificationEnums;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageThreadRepository extends JpaRepository<MessageThread, UUID> {

    /** One patient's inbox, newest activity first. Every portal read goes through this. */
    List<MessageThread> findByPatientIdOrderByLastMessageAtDesc(UUID patientId);

    /**
     * The staff queue, oldest first.
     *
     * <p>Oldest first, deliberately, and it is the same argument the casualty board makes about
     * arrival order: a queue served newest-first starves the person who has been waiting longest,
     * and in an inbox nobody ever notices because the screen always looks busy.
     *
     * <p>The status filter is an always-present pattern rather than a nullable value, following
     * {@code QueryPatterns}: a bare {@code :param is null} sends an untyped null that PostgreSQL
     * infers as {@code bytea}. Here the enum comparison is expressed as a list membership with an
     * explicit "no filter" flag for the same reason.
     */
    @Query("""
            select t from MessageThread t
             where (:unfiltered = true or t.status = :status)
             order by t.lastMessageAt asc
            """)
    Page<MessageThread> queue(@Param("unfiltered") boolean unfiltered,
                              @Param("status") NotificationEnums.ThreadStatus status,
                              Pageable pageable);

    /**
     * How many staff messages this patient has not opened — the portal's unread badge.
     *
     * <p>The author kind arrives as a bound parameter rather than as a literal in the query.
     * Naming an enum constant inline in JPQL means writing a fully-qualified nested-class path
     * that Hibernate parses differently between versions, and a query that compiles and then
     * throws at runtime is exactly the failure the {@code Object[]} projection taught this
     * repository once already.
     */
    @Query("""
            select count(m) from MessageThread t join t.messages m
             where t.patientId = :patientId
               and m.authorKind = :staff
               and m.readByPatientAt is null
            """)
    long unreadFor(@Param("patientId") UUID patientId,
                   @Param("staff") NotificationEnums.AuthorKind staff);
}
