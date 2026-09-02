package com.hms.notification.service;

import com.hms.common.audit.AuditService;
import com.hms.notification.channel.ChannelRegistry;
import com.hms.notification.channel.Delivery;
import com.hms.notification.channel.Message;
import com.hms.notification.channel.Recipient;
import com.hms.notification.domain.Notification;
import com.hms.notification.domain.NotificationEnums;
import com.hms.notification.repo.NotificationRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sending, and recording that it was sent.
 *
 * <p>Every path in and out of this module goes through {@link #send}: the API, the event consumer,
 * and anything added later. That is what keeps the two rules the module exists for in one place —
 * the words come from a template, and the same message is never sent twice.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final MessageComposer composer;
    private final ContactDirectory contacts;
    private final ChannelRegistry channels;
    private final AuditService audit;

    public NotificationService(NotificationRepository notifications, MessageComposer composer,
                               ContactDirectory contacts, ChannelRegistry channels, AuditService audit) {
        this.notifications = notifications;
        this.composer = composer;
        this.contacts = contacts;
        this.channels = channels;
        this.audit = audit;
    }

    /**
     * What to send, and what it is about.
     *
     * <p>Note what is absent: any text. A caller chooses a category and supplies the substitutions
     * the template is allowed to use, and that is the whole of its influence over the words. It is
     * also the reason this record exists rather than four parameters — adding a {@code body} field
     * would be a visible act rather than an easy one.
     *
     * @param idempotencyKey what makes two attempts the same attempt. The event id on the consumer
     *                       path; a caller-supplied key, or a derived one, on the API path.
     * @param reference      what the message is about, for tracing. Never rendered.
     */
    public record Request(NotificationEnums.Category category, NotificationEnums.Channel channel,
                          UUID patientId, String reference, String idempotencyKey,
                          Map<String, String> values) {

        public Request {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    /**
     * Sends one message, once.
     *
     * <p>{@code REQUIRES_NEW} so that the delivery record survives whatever the caller's
     * transaction does next. On the consumer path there is no outer transaction to speak of; on the
     * API path it means a later failure cannot erase evidence that a patient was contacted, which
     * would be worse than the duplicate the idempotency key already prevents.
     *
     * @return the notification as it was recorded — including a {@code SUPPRESSED} one, which is a
     *         real outcome rather than a failure to report
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification send(Request request) {
        Optional<Notification> already = notifications.findByIdempotencyKey(request.idempotencyKey());
        if (already.isPresent()) {
            log.debug("Notification {} already handled", request.idempotencyKey());
            return already.get();
        }

        ChannelRegistry.Resolved resolved = channels.resolve(request.channel());
        Optional<Message> composed = composer.compose(request.category(), resolved.kind(), request.values());
        if (composed.isEmpty()) {
            // A category with no active template for the channel it resolved to. Not silently
            // dropped: somebody switched a template off or a migration is missing one, and the way
            // that becomes visible is a row saying so.
            Notification record = new Notification(resolved.kind(), request.category(),
                    request.idempotencyKey(),
                    "(no active template for %s on %s)".formatted(request.category(), resolved.kind()));
            record.setPatientId(request.patientId());
            record.setReference(request.reference());
            record.suppressed("No active message template for this category and channel");
            return persist(record, request);
        }

        Message message = composed.get();
        Notification record = new Notification(resolved.kind(), request.category(),
                request.idempotencyKey(), message.body());
        record.setSubject(message.subject());
        record.setPatientId(request.patientId());
        record.setReference(request.reference());

        Optional<Recipient> recipient = request.patientId() == null
                ? Optional.empty()
                : contacts.find(request.patientId());
        if (recipient.isEmpty()) {
            record.suppressed(resolved.substitution() == null
                    ? contacts.unavailableReason()
                    : resolved.substitution() + ". " + contacts.unavailableReason());
            return persist(record, request);
        }

        Delivery delivery = resolved.channel().send(recipient.get(), message);
        if (delivery.sent()) {
            record.sent(delivery.address(), 1);
        } else {
            record.failed(delivery.address(), 1, delivery.detail());
        }
        return persist(record, request);
    }

    @Transactional(readOnly = true)
    public Page<Notification> log(NotificationEnums.Status status, Pageable pageable) {
        return notifications.log(status, pageable);
    }

    @Transactional(readOnly = true)
    public java.util.List<Notification> forPatient(UUID patientId) {
        return notifications.findByPatientIdOrderByCreatedAtDesc(patientId);
    }

    /**
     * Writes the row, and treats a duplicate key as "somebody else already did this".
     *
     * <p>The check at the top of {@link #send} is an optimisation, not the control. Two consumer
     * instances handling the same redelivered event pass it at the same moment and both compose a
     * message; the unique index is what stops the second one from being a second SMS. Losing that
     * race is the system working, so it is not an error.
     *
     * <p>The audit detail deliberately carries the category and the status and not the body or the
     * address: the audit trail is read more widely than the delivery log, and a message saying a
     * report is ready is still a statement that this patient had a test.
     */
    private Notification persist(Notification record, Request request) {
        try {
            Notification saved = notifications.saveAndFlush(record);
            audit.record("NOTIFICATION_" + saved.getStatus(), "Notification", saved.getId(),
                    "%s on %s".formatted(saved.getCategory(), saved.getChannel()));
            return saved;
        } catch (DataIntegrityViolationException ex) {
            log.debug("Notification {} was written concurrently; keeping the first",
                    request.idempotencyKey());
            return notifications.findByIdempotencyKey(request.idempotencyKey()).orElseThrow(() -> ex);
        }
    }
}
