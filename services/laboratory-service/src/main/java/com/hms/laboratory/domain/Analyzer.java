package com.hms.laboratory.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/** A connected instrument. Which protocol it speaks decides how its messages are decoded. */
@Entity
@Table(name = "analyzers")
public class Analyzer extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true, length = 80)
    private String name;

    @Column(name = "model", nullable = false, length = 80)
    private String model;

    @Column(name = "serial_no", length = 64)
    private String serialNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol", nullable = false, length = 16)
    private LabEnums.Protocol protocol = LabEnums.Protocol.ASTM;

    @Column(name = "transport", nullable = false, length = 16)
    private String transport = "TCP";

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_seen")
    private Instant lastSeen;

    protected Analyzer() {
    }

    public Analyzer(String name, String model, LabEnums.Protocol protocol, String transport) {
        this.name = name;
        this.model = model;
        this.protocol = protocol;
        this.transport = transport;
    }

    public String getName() {
        return name;
    }

    public String getModel() {
        return model;
    }

    public String getSerialNo() {
        return serialNo;
    }

    public LabEnums.Protocol getProtocol() {
        return protocol;
    }

    public String getTransport() {
        return transport;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    /** Records that the instrument has just transmitted, for the connectivity view. */
    public void touch() {
        this.lastSeen = Instant.now();
    }
}
