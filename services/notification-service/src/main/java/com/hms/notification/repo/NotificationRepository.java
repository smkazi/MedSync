package com.hms.notification.repo;

import com.hms.notification.domain.Notification;
import com.hms.notification.domain.NotificationEnums;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    List<Notification> findByPatientIdOrderByCreatedAtDesc(UUID patientId);

    /**
     * The delivery log, newest first, optionally narrowed to one status.
     *
     * <p>{@code exactOrAny} rather than {@code (:status is null or ...)}: an untyped null makes
     * PostgreSQL infer {@code bytea} for the parameter and the query fails at runtime. The pattern
     * is documented in {@code QueryPatterns} and this is its enum-valued form.
     */
    @Query("""
            select n from Notification n
            where (:status is null or n.status = :status)
            order by n.createdAt desc
            """)
    Page<Notification> log(NotificationEnums.Status status, Pageable pageable);
}
