package com.hms.laboratory.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An analyzer transmission, retained verbatim.
 *
 * <p>This exists because results get questioned. When a clinician disputes a value, the raw frame
 * is the evidence of what the instrument actually sent — and when a parse fails, it is the only way
 * to find out why without asking the lab to re-run the sample.
 */
@Entity
@Table(name = "device_messages")
public class DeviceMessage extends BaseEntity {

    @Column(name = "analyzer_id")
    private UUID analyzerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol", nullable = false, length = 16)
    private LabEnums.Protocol protocol;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "text")
    private String rawPayload;

    @Column(name = "payload_bytes", nullable = false)
    private int payloadBytes;

    @Column(name = "sample_id", length = 64)
    private String sampleId;

    @Column(name = "matched_order_id")
    private UUID matchedOrderId;

    @Column(name = "parsed_ok", nullable = false)
    private boolean parsedOk;

    @Column(name = "result_count", nullable = false)
    private int resultCount;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    protected DeviceMessage() {
    }

    public DeviceMessage(UUID analyzerId, LabEnums.Protocol protocol, String rawPayload) {
        this.analyzerId = analyzerId;
        this.protocol = protocol;
        this.rawPayload = rawPayload;
        this.payloadBytes = rawPayload == null ? 0 : rawPayload.length();
    }

    public LabEnums.Protocol getProtocol() {
        return protocol;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public int getPayloadBytes() {
        return payloadBytes;
    }

    public String getSampleId() {
        return sampleId;
    }

    public UUID getMatchedOrderId() {
        return matchedOrderId;
    }

    public UUID getAnalyzerId() {
        return analyzerId;
    }

    public boolean isParsedOk() {
        return parsedOk;
    }

    public int getResultCount() {
        return resultCount;
    }

    public String getError() {
        return error;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void recordSuccess(String sampleId, UUID matchedOrderId, int resultCount) {
        this.sampleId = truncate(sampleId, 64);
        this.matchedOrderId = matchedOrderId;
        this.resultCount = resultCount;
        this.parsedOk = true;
        this.error = null;
    }

    public void recordFailure(String error) {
        this.parsedOk = false;
        this.error = truncate(error, 500);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
