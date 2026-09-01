package com.hms.laboratory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * The explanation for one instrument histogram flag code.
 *
 * <p>Sysmex analyzers transmit codes like {@code PL} or {@code RU} beside a result to say the
 * instrument could not separate a population cleanly. Unexplained, they are noise on a report; the
 * message says what may be wrong with the number and what to verify — which is the difference
 * between a flag and a usable one.
 *
 * <p>Not a {@code BaseEntity}: the code is the key, because the analyzer sends the code and a
 * surrogate id would add a lookup for nothing.
 */
@Entity
@Table(name = "histogram_flag_notes")
public class HistogramFlagNote {

    @Id
    @Column(name = "code", nullable = false, length = 8, updatable = false)
    private String code;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected HistogramFlagNote() {
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public boolean isActive() {
        return active;
    }
}
