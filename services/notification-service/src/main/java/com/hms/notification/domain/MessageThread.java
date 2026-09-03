package com.hms.notification.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One conversation between a patient and the hospital, about one thing.
 *
 * <p>The status is derived from who wrote last rather than set by a caller, and that is the only
 * interesting behaviour here: a patient writing makes the thread OPEN because somebody now owes
 * them an answer, and a member of staff writing makes it ANSWERED because they no longer do. A
 * status field that a caller could set would eventually be set to ANSWERED by whoever wanted the
 * queue to look shorter.
 */
@Entity
@Table(name = "message_threads")
public class MessageThread extends BaseEntity {

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, length = 24, updatable = false)
    private String patientMrn;

    @Column(name = "subject", nullable = false, length = 160, updatable = false)
    private String subject;

    @Column(name = "department_code", length = 16)
    private String departmentCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationEnums.ThreadStatus status = NotificationEnums.ThreadStatus.OPEN;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @OneToMany(mappedBy = "thread", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("sentAt asc")
    private List<ThreadMessage> messages = new ArrayList<>();

    protected MessageThread() {
    }

    public MessageThread(UUID patientId, String patientMrn, String subject, String departmentCode) {
        this.patientId = patientId;
        this.patientMrn = patientMrn;
        this.subject = subject;
        this.departmentCode = departmentCode;
        this.lastMessageAt = Instant.now();
    }

    /**
     * Appends a message and moves the thread accordingly.
     *
     * @throws IllegalStateException if the thread is closed. Callers check first and refuse with a
     *                               message; this is the backstop that keeps the invariant true
     *                               for any path that forgets.
     */
    public ThreadMessage append(NotificationEnums.AuthorKind authorKind, String authorName, String body) {
        if (status == NotificationEnums.ThreadStatus.CLOSED) {
            throw new IllegalStateException("A closed thread cannot take another message");
        }
        ThreadMessage message = new ThreadMessage(this, authorKind, authorName, body);
        messages.add(message);
        lastMessageAt = message.getSentAt();
        status = authorKind == NotificationEnums.AuthorKind.PATIENT
                ? NotificationEnums.ThreadStatus.OPEN
                : NotificationEnums.ThreadStatus.ANSWERED;
        return message;
    }

    public void close() {
        this.status = NotificationEnums.ThreadStatus.CLOSED;
        this.closedAt = Instant.now();
    }

    public boolean isClosed() {
        return status == NotificationEnums.ThreadStatus.CLOSED;
    }

    /** Stamps every staff message the patient has not seen. Their own messages are never unread. */
    public int markReadByPatient() {
        Instant now = Instant.now();
        int marked = 0;
        for (ThreadMessage message : messages) {
            if (message.getAuthorKind() == NotificationEnums.AuthorKind.STAFF
                    && message.getReadByPatientAt() == null) {
                message.markRead(now);
                marked++;
            }
        }
        return marked;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientMrn() {
        return patientMrn;
    }

    public String getSubject() {
        return subject;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public NotificationEnums.ThreadStatus getStatus() {
        return status;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public List<ThreadMessage> getMessages() {
        return messages;
    }
}
