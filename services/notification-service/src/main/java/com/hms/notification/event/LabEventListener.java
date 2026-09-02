package com.hms.notification.event;

import com.hms.common.events.DomainEvent;
import com.hms.common.events.Topics;
import com.hms.notification.domain.NotificationEnums;
import com.hms.notification.service.NotificationService;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Tells a patient that a report has been released.
 *
 * <p>Consumes {@code hms.lab.events} and reacts to one type: {@code lab.results.verified}, which
 * laboratory-service publishes when a pathologist verifies — and verifying is the release, so that
 * is the moment there is something for a patient to read.
 *
 * <p><strong>What the message does not contain.</strong> The event carries the patient's MRN, how
 * many results there were, and how many were abnormal. None of it reaches the message. The
 * notification says a report is ready and where to sign in, and it says that whether the report is
 * entirely normal or entirely not — because the difference is exactly what must not travel over
 * SMS, and because a message whose *existence* implied bad news would be as much of a disclosure as
 * one that said so.
 *
 * <p>Built to the shape {@code AuditEventListener} established: only active with Kafka, a raw
 * {@code String} payload deserialised by hand, and a malformed message logged and dropped so a bad
 * message cannot stall the consumer group forever. Idempotency is on the event id, so a redelivery
 * cannot produce a second SMS.
 */
@Component
@ConditionalOnProperty(name = "hms.events.transport", havingValue = "kafka")
public class LabEventListener {

    private static final Logger log = LoggerFactory.getLogger(LabEventListener.class);

    /** The one type this listener acts on. Every other lab event passes through untouched. */
    private static final String RELEASED = "lab.results.verified";

    private final NotificationService notifications;
    private final ObjectMapper objectMapper;
    private final NotificationEnums.Channel channel;

    public LabEventListener(NotificationService notifications, ObjectMapper objectMapper,
                            @Value("${hms.notification.default-channel:LOG}") NotificationEnums.Channel channel) {
        this.notifications = notifications;
        this.objectMapper = objectMapper;
        this.channel = channel;
    }

    @KafkaListener(topics = Topics.LAB, groupId = "notification-lab-release")
    public void onLabEvent(String message) {
        try {
            DomainEvent event = objectMapper.readValue(message, DomainEvent.class);
            if (!RELEASED.equals(event.type())) {
                return;
            }
            UUID patientId = patientId(event.payload());
            if (patientId == null) {
                log.warn("Released report event {} carries no patient id; nothing to notify",
                        event.eventId());
                return;
            }
            notifications.send(new NotificationService.Request(
                    NotificationEnums.Category.LAB_REPORT_READY, channel, patientId,
                    event.aggregateId(),
                    // The event id, so a redelivered message is the same message. Prefixed because
                    // the key space is shared with the API path and a collision between the two
                    // would silently suppress a real send.
                    "lab-release:" + event.eventId(),
                    Map.of()));
        } catch (RuntimeException ex) {
            // Dropped rather than retried forever. A message this consumer cannot read will not
            // become readable, and a stalled group means every later released report goes untold.
            log.error("Discarding unreadable lab event: {}", ex.getMessage());
        }
    }

    private static UUID patientId(Map<String, Object> payload) {
        Object value = payload.get("patientId");
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
