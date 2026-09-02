package com.hms.notification.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hms.common.events.DomainEvent;
import com.hms.notification.domain.NotificationEnums;
import com.hms.notification.service.NotificationService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * What the consumer does with a real released-report event.
 *
 * <p>Constructed by hand rather than through Spring: the listener is
 * {@code @ConditionalOnProperty} on the Kafka transport, which the test profile does not use, and
 * standing up a broker to assert a mapping would be a lot of machinery around one method.
 *
 * <p>The payload here is the one {@code LabResultService.publish} really sends — patient id, MRN,
 * status, result count and abnormal count. That matters: a test that fed a hand-simplified payload
 * would not prove anything about what actually arrives.
 */
class LabEventListenerTest {

    private static final UUID PATIENT = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NotificationService notifications = mock(NotificationService.class);
    private final LabEventListener listener =
            new LabEventListener(notifications, objectMapper, NotificationEnums.Channel.SMS);

    private String event(String type, UUID eventId) {
        DomainEvent domainEvent = new DomainEvent(eventId, type, "LabOrder",
                UUID.randomUUID().toString(), java.time.Instant.now(),
                UUID.randomUUID().toString(), "corr-1",
                Map.of("patientId", PATIENT.toString(),
                        "mrn", "MRN-000123",
                        "status", "VERIFIED",
                        "count", 3,
                        "abnormal", 1));
        return objectMapper.writeValueAsString(domainEvent);
    }

    @Test
    @DisplayName("a released report asks for one LAB_REPORT_READY, keyed on the event id")
    void aReleasedReportIsNotified() {
        UUID eventId = UUID.randomUUID();
        listener.onLabEvent(event("lab.results.verified", eventId));

        ArgumentCaptor<NotificationService.Request> captured =
                ArgumentCaptor.forClass(NotificationService.Request.class);
        verify(notifications).send(captured.capture());

        NotificationService.Request request = captured.getValue();
        assertThat(request.category()).isEqualTo(NotificationEnums.Category.LAB_REPORT_READY);
        assertThat(request.patientId()).isEqualTo(PATIENT);
        // The event id, so a redelivery is the same message rather than a second SMS. Prefixed
        // because the key space is shared with the API path.
        assertThat(request.idempotencyKey()).isEqualTo("lab-release:" + eventId);
    }

    @Test
    @DisplayName("nothing from the event's payload is offered to the message")
    void thePayloadIsNotForwardedIntoTheMessage() {
        listener.onLabEvent(event("lab.results.verified", UUID.randomUUID()));

        ArgumentCaptor<NotificationService.Request> captured =
                ArgumentCaptor.forClass(NotificationService.Request.class);
        verify(notifications).send(captured.capture());

        // The MRN, the result count and the abnormal count are all on the event and none of them
        // is passed on. Even the abnormal count: a message whose existence implied bad news would
        // be as much of a disclosure as one that said so, so the notification is identical whether
        // the report is entirely normal or entirely not.
        assertThat(captured.getValue().values()).isEmpty();
        assertThat(captured.getValue().values()).doesNotContainKey("mrn");
    }

    @Test
    @DisplayName("every other laboratory event passes through untouched")
    void otherLabEventsAreIgnored() {
        // The topic carries ordering, collection and manual result entry too. A patient does not
        // want a text message each time a tube moves, and the release is the only point at which
        // there is something for them to read.
        listener.onLabEvent(event("lab.results.recorded", UUID.randomUUID()));
        listener.onLabEvent(event("lab.order.created", UUID.randomUUID()));

        verify(notifications, never()).send(any());
    }

    @Test
    @DisplayName("a malformed message is dropped rather than stalling the consumer group")
    void aMalformedMessageIsDropped() {
        // The AuditEventListener rule: a message this consumer cannot read will not become
        // readable, and a stalled group means every later released report goes untold.
        listener.onLabEvent("{not json");
        listener.onLabEvent("");

        verify(notifications, never()).send(any());
    }

    @Test
    @DisplayName("an event with no patient id notifies nobody rather than failing")
    void aMissingPatientIdIsNotAnError() {
        DomainEvent malformed = new DomainEvent(UUID.randomUUID(), "lab.results.verified", "LabOrder",
                UUID.randomUUID().toString(), java.time.Instant.now(), "system", "corr-1",
                Map.of("mrn", "MRN-000123"));

        listener.onLabEvent(objectMapper.writeValueAsString(malformed));

        verify(notifications, never()).send(any());
    }
}
