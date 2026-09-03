package com.hms.interop.service;

import com.hms.common.audit.AuditService;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import com.hms.interop.domain.Hl7Exchange;
import com.hms.interop.hl7.Er7Parser;
import com.hms.interop.hl7.Hl7Ack;
import com.hms.interop.hl7.Hl7Message;
import com.hms.interop.hl7.Hl7Segment;
import com.hms.interop.repo.Hl7ExchangeRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seam between another hospital's system and this one.
 *
 * <p>Every message is stored verbatim before it is parsed. That is the rule the laboratory service
 * learned from analyzers and it holds harder here: an interface engine is judged on its ability to
 * answer "what did you actually receive at nine o'clock", and the messages worth asking about are
 * the ones that did not parse.
 *
 * <p>What this deliberately does not do is act on a message inside the acknowledgement. A sender
 * holds its connection open waiting for the ACK, and doing a patient merge before replying makes
 * that sender's timeout this platform's problem — so an accepted message is turned into a domain
 * event and the reply goes back immediately. Nothing on the platform consumes those events yet,
 * which is stated in the README rather than implied by the word "accepted": AA here means the
 * message arrived, parsed, and was recorded, which is exactly what an interface engine promises and
 * no more.
 */
@Service
public class Hl7IngestService {

    private static final Logger log = LoggerFactory.getLogger(Hl7IngestService.class);

    /** The topic inbound traffic lands on, one event per accepted message. */
    public static final String TOPIC = "hms.interop.hl7";

    /**
     * The types this platform says it handles.
     *
     * <p>An unlisted type is answered AE rather than AA. That distinction is the whole reason the
     * codes exist: AA to a message nobody will ever act on is a lie the sender has no way to
     * detect, and their record will say the result was delivered.
     */
    private static final Set<String> HANDLED = Set.of(
            "ADT^A01", "ADT^A03", "ADT^A04", "ADT^A08", "ADT^A28", "ADT^A31",
            "ORU^R01", "ORM^O01", "OML^O21", "SIU^S12", "SIU^S14", "SIU^S15");

    private final Hl7ExchangeRepository exchanges;
    private final EventPublisher events;
    private final AuditService audit;
    private final String application;
    private final String facility;

    public Hl7IngestService(Hl7ExchangeRepository exchanges, EventPublisher events,
                            AuditService audit,
                            @Value("${hms.interop.hl7.application:HMS}") String application,
                            @Value("${hms.interop.hl7.facility:HMS}") String facility) {
        this.exchanges = exchanges;
        this.events = events;
        this.audit = audit;
        this.application = application;
        this.facility = facility;
    }

    /**
     * Receives one message and returns the acknowledgement to send back.
     *
     * <p>Runs in its own transaction. A sender on a socket delivers messages back to back down one
     * connection, and one poisoned message must not roll back the record of the nine that followed
     * it — the same reason charge capture posts each charge in its own.
     *
     * <p>Never throws. Whatever arrives, the caller gets a string to write back: a socket handler
     * that has to decide what to do with an exception is a socket handler that will one day hang up
     * on a sender mid-conversation, and a sender that receives nothing retries for ever.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String receive(String raw, Hl7Exchange.Transport transport, String peer) {
        Hl7Exchange exchange = new Hl7Exchange(Hl7Exchange.Direction.IN, raw, transport, peer);

        Hl7Message message;
        try {
            message = Er7Parser.parse(raw);
        } catch (RuntimeException ex) {
            // Not understood at all: AR, because re-sending it might work if the sender's
            // configuration is what was wrong.
            String reason = ex.getMessage() == null ? "The message could not be parsed"
                    : ex.getMessage();
            String ack = Hl7Ack.rejected(reason, application, facility);
            exchange.failed(reason);
            exchange.acknowledged(Hl7Ack.Code.AR.name(), reason, ack);
            save(exchange);
            log.warn("Rejected an unparseable HL7 message from {}: {}", peer, reason);
            return ack;
        }

        exchange.describe(message.messageType(), message.controlId(), message.sendingApplication(),
                message.sendingFacility(), message.receivingApplication(),
                message.receivingFacility(), message.messageDateTime().orElse(null));

        String type = message.messageType();
        if (!HANDLED.contains(type)) {
            // Understood and refused. The sender should stop sending it rather than retry, which is
            // precisely what AE means and AR does not.
            String reason = "This platform does not handle %s".formatted(
                    type.isEmpty() ? "a message with no type in MSH-9" : type);
            String ack = Hl7Ack.of(message, Hl7Ack.Code.AE, reason, application, facility);
            exchange.failed(reason);
            exchange.acknowledged(Hl7Ack.Code.AE.name(), reason, ack);
            save(exchange);
            return ack;
        }

        String ack = Hl7Ack.of(message, Hl7Ack.Code.AA, null, application, facility);
        exchange.acknowledged(Hl7Ack.Code.AA.name(), null, ack);
        save(exchange);

        publish(message, exchange);
        audit.record("HL7_RECEIVED", "Hl7Exchange", exchange.getId(),
                "%s from %s".formatted(type, message.sendingApplication()));
        return ack;
    }

    /**
     * Records a message this platform sent.
     *
     * <p>The same table, because "what did we send them" and "what did they send us" are the same
     * question asked from two ends, and an engineer chasing one exchange should not have to look in
     * two places to see both halves of it.
     */
    @Transactional
    public Hl7Exchange recordOutbound(String raw, Hl7Exchange.Transport transport, String peer,
                                      String ackRaw, String error) {
        Hl7Exchange exchange = new Hl7Exchange(Hl7Exchange.Direction.OUT, raw, transport, peer);
        try {
            Hl7Message message = Er7Parser.parse(raw);
            exchange.describe(message.messageType(), message.controlId(),
                    message.sendingApplication(), message.sendingFacility(),
                    message.receivingApplication(), message.receivingFacility(),
                    message.messageDateTime().orElse(null));
        } catch (RuntimeException ex) {
            // A message this platform built and cannot parse is a defect in the builder, and the
            // row is the evidence. Recorded rather than thrown: it has already been sent.
            exchange.failed("Built a message this platform cannot parse: " + ex.getMessage());
        }
        if (ackRaw != null) {
            readAck(exchange, ackRaw);
        }
        if (error != null) {
            exchange.failed(error);
        }
        return save(exchange);
    }

    /** Reads the MSA out of a reply, so an outbound row says whether the far end took it. */
    private void readAck(Hl7Exchange exchange, String ackRaw) {
        try {
            Hl7Message reply = Er7Parser.parse(ackRaw);
            Hl7Segment msa = reply.segment("MSA").orElse(null);
            if (msa == null) {
                exchange.acknowledged(null, null, ackRaw);
                exchange.failed("The reply carried no MSA segment");
                return;
            }
            String code = msa.field(1);
            exchange.acknowledged(code.isEmpty() ? null : code, msa.field(3), ackRaw);
            if (!"AA".equals(code)) {
                // A rejection at the far end is a failure of this exchange even though the send
                // succeeded, and a log that recorded only "sent" would show green while nothing
                // arrived.
                exchange.failed("The receiver answered %s: %s".formatted(code, msa.field(3)));
            }
        } catch (RuntimeException ex) {
            exchange.acknowledged(null, null, ackRaw);
            exchange.failed("The reply could not be parsed: " + ex.getMessage());
        }
    }

    /**
     * Turns an accepted message into a domain event.
     *
     * <p>A flat map of the values a consumer is most likely to want, not the whole message: the raw
     * text is on the row, and an event carrying an entire message would be a second copy of it that
     * can drift. Payloads are maps rather than typed classes for the reason every event on this
     * platform is — so a consumer never fails to deserialize because a producer added a field.
     */
    private void publish(Hl7Message message, Hl7Exchange exchange) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exchangeId", String.valueOf(exchange.getId()));
        payload.put("messageType", message.messageType());
        payload.put("controlId", message.controlId());
        payload.put("sendingApplication", message.sendingApplication());
        payload.put("sendingFacility", message.sendingFacility());

        message.segment("PID").ifPresent(pid -> {
            payload.put("patientIdentifier",
                    Er7Parser.unescape(pid.component(3, 1), message.encoding()));
            payload.put("familyName", Er7Parser.unescape(pid.component(5, 1), message.encoding()));
            payload.put("givenName", Er7Parser.unescape(pid.component(5, 2), message.encoding()));
            payload.put("dateOfBirth", pid.field(7));
            payload.put("sex", pid.field(8));
        });

        List<Hl7Segment> observations = message.allSegments("OBX");
        if (!observations.isEmpty()) {
            payload.put("observations", observations.stream()
                    .map(obx -> Map.of(
                            "code", Er7Parser.unescape(obx.component(3, 1), message.encoding()),
                            "name", Er7Parser.unescape(obx.component(3, 2), message.encoding()),
                            "value", Er7Parser.unescape(obx.field(5), message.encoding()),
                            "units", obx.field(6),
                            "flag", obx.field(8)))
                    .toList());
        }

        events.publish(TOPIC, DomainEvent.of("interop.hl7.received", "Hl7Exchange",
                exchange.getId(), CurrentUser.idOrSystem().toString(), CorrelationId.current(),
                payload));
    }

    private Hl7Exchange save(Hl7Exchange exchange) {
        return exchanges.save(exchange);
    }
}
