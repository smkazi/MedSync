package com.hms.notification.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One message in a thread.
 *
 * <p>No setter for the body and no delete anywhere in this service. A thread is the record of what
 * the hospital told a patient and what they said back, and both sides sometimes need it a year
 * later — an editable one would be worth nothing to either of them.
 */
@Entity
@Table(name = "thread_messages")
public class ThreadMessage extends BaseEntity {

    /**
     * The thread this belongs to, mapped on this side.
     *
     * <p>A unidirectional {@code @OneToMany @JoinColumn} on the parent looks tidier and does not
     * work here: Hibernate inserts the child with a null foreign key and updates it afterwards, so
     * a {@code NOT NULL} {@code thread_id} rejects every message ever written. Found by the first
     * test that tried to start a conversation. Owning the association on the child means one
     * INSERT carrying the key, which is also one statement rather than two.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "thread_id", nullable = false, updatable = false)
    private MessageThread thread;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_kind", nullable = false, length = 16, updatable = false)
    private NotificationEnums.AuthorKind authorKind;

    @Column(name = "author_name", nullable = false, length = 160, updatable = false)
    private String authorName;

    @Column(name = "body", nullable = false, columnDefinition = "text", updatable = false)
    private String body;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "read_by_patient_at")
    private Instant readByPatientAt;

    protected ThreadMessage() {
    }

    ThreadMessage(MessageThread thread, NotificationEnums.AuthorKind authorKind, String authorName,
                  String body) {
        this.thread = thread;
        this.authorKind = authorKind;
        this.authorName = authorName;
        this.body = body;
        this.sentAt = Instant.now();
    }

    void markRead(Instant at) {
        this.readByPatientAt = at;
    }

    public MessageThread getThread() {
        return thread;
    }

    public NotificationEnums.AuthorKind getAuthorKind() {
        return authorKind;
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getBody() {
        return body;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getReadByPatientAt() {
        return readByPatientAt;
    }
}
