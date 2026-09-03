package com.hms.interop.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One HL7 v2 message that crossed the boundary, and what was said back.
 *
 * <p>The raw text is the point. An interface engine is judged almost entirely on its ability to
 * answer "what did you actually receive at nine o'clock", and a record that holds only the parsed
 * result cannot: the interesting messages are the ones that did not parse.
 *
 * <p>Both directions share this type because they are the same object — a message, an
 * acknowledgement, and a verdict — and separating them would double every query somebody runs while
 * chasing one exchange across a boundary.
 */
@Entity
@Table(name = "hl7_messages")
public class Hl7Exchange extends BaseEntity {

    public enum Direction {
        /** Received by this platform. */
        IN,
        /** Sent by it. */
        OUT
    }

    public enum Transport {
        MLLP, HTTP
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 3, updatable = false)
    private Direction direction;

    @Column(name = "raw", nullable = false, updatable = false)
    private String raw;

    @Column(name = "message_type", length = 16)
    private String messageType;

    @Column(name = "control_id", length = 64)
    private String controlId;

    @Column(name = "sending_application", length = 64)
    private String sendingApplication;

    @Column(name = "sending_facility", length = 64)
    private String sendingFacility;

    @Column(name = "receiving_application", length = 64)
    private String receivingApplication;

    @Column(name = "receiving_facility", length = 64)
    private String receivingFacility;

    @Column(name = "message_at")
    private Instant messageAt;

    @Column(name = "ack_code", length = 2)
    private String ackCode;

    @Column(name = "ack_text", length = 500)
    private String ackText;

    @Column(name = "ack_raw")
    private String ackRaw;

    @Column(name = "error", length = 1000)
    private String error;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport", nullable = false, length = 8, updatable = false)
    private Transport transport;

    @Column(name = "peer", length = 120)
    private String peer;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    protected Hl7Exchange() {
    }

    public Hl7Exchange(Direction direction, String raw, Transport transport, String peer) {
        this.direction = direction;
        this.raw = raw;
        this.transport = transport;
        this.peer = peer;
    }

    /** Fills in what the header said, once it has been read. */
    public void describe(String messageType, String controlId, String sendingApplication,
                         String sendingFacility, String receivingApplication,
                         String receivingFacility, Instant messageAt) {
        this.messageType = trim(messageType, 16);
        this.controlId = trim(controlId, 64);
        this.sendingApplication = trim(sendingApplication, 64);
        this.sendingFacility = trim(sendingFacility, 64);
        this.receivingApplication = trim(receivingApplication, 64);
        this.receivingFacility = trim(receivingFacility, 64);
        this.messageAt = messageAt;
    }

    public void acknowledged(String code, String text, String rawAck) {
        this.ackCode = code;
        this.ackText = trim(text, 500);
        this.ackRaw = rawAck;
    }

    public void failed(String reason) {
        this.error = trim(reason, 1000);
    }

    /**
     * Truncates a value to the column it is going into.
     *
     * <p>A sender is not obliged to respect this platform's column widths, and a 300-character
     * facility name is a reason to store 64 of them rather than to reject a clinical message. The
     * raw text is kept whole regardless, so nothing is actually lost.
     */
    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    public Direction getDirection() {
        return direction;
    }

    public String getRaw() {
        return raw;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getControlId() {
        return controlId;
    }

    public String getSendingApplication() {
        return sendingApplication;
    }

    public String getSendingFacility() {
        return sendingFacility;
    }

    public String getReceivingApplication() {
        return receivingApplication;
    }

    public String getReceivingFacility() {
        return receivingFacility;
    }

    public Instant getMessageAt() {
        return messageAt;
    }

    public String getAckCode() {
        return ackCode;
    }

    public String getAckText() {
        return ackText;
    }

    public String getAckRaw() {
        return ackRaw;
    }

    public String getError() {
        return error;
    }

    public Transport getTransport() {
        return transport;
    }

    public String getPeer() {
        return peer;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
