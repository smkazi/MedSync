package com.hms.notification.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * The wording of one kind of message on one channel.
 *
 * <p>Rows rather than compiled strings because a hospital rewrites these, translates them, and has
 * a legal opinion about them — all configuration. What is emphatically not configurable is the set
 * of placeholders a template may use: see {@code MessageComposer}, which refuses one it does not
 * recognise. That refusal is where the PHI rule actually lives. If a template could interpolate a
 * result value, then rewording a message would be enough to put a laboratory number into an SMS,
 * and the rule would be a comment instead of a property of the system.
 */
@Entity
@Table(name = "message_templates")
public class MessageTemplate extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32, updatable = false)
    private NotificationEnums.Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private NotificationEnums.Channel channel;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "body", nullable = false, length = 1000)
    private String body;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected MessageTemplate() {
    }

    public NotificationEnums.Category getCategory() {
        return category;
    }

    public NotificationEnums.Channel getChannel() {
        return channel;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public boolean isActive() {
        return active;
    }

    /** Retuning the wording. The category and the channel are the key and stay fixed. */
    public void reword(String newSubject, String newBody) {
        if (newSubject != null) {
            this.subject = newSubject;
        }
        if (newBody != null && !newBody.isBlank()) {
            this.body = newBody;
        }
    }

    public void setActive(boolean value) {
        this.active = value;
    }
}
