package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * What a NEWS2 band means in this hospital.
 *
 * <p>Rows, deliberately — and the counterpart to the decision that the score's own cut-offs are
 * <em>not</em> rows. NEWS2 is a national standard whose value is that 6 means the same thing
 * everywhere, so a deployment that could edit the bands could produce a number it calls NEWS2
 * which is not. What every trust genuinely decides for itself is the response: who is called, how
 * fast, and how often observations are repeated. A district general and a tertiary centre answer
 * that differently and both are right.
 *
 * <p>The band is the key and is not editable, because it is the calculator's output rather than a
 * name somebody chose.
 */
@Entity
@Table(name = "escalation_policies")
public class EscalationPolicy extends BaseEntity {

    @Column(name = "band", nullable = false, length = 16, updatable = false)
    private String band;

    @Column(name = "monitoring", nullable = false, length = 120)
    private String monitoring;

    @Column(name = "response", nullable = false, length = 400)
    private String response;

    @Column(name = "setting", nullable = false, length = 200)
    private String setting;

    protected EscalationPolicy() {
    }

    public String getBand() {
        return band;
    }

    public String getMonitoring() {
        return monitoring;
    }

    public String getResponse() {
        return response;
    }

    public String getSetting() {
        return setting;
    }

    public void revise(String newMonitoring, String newResponse, String newSetting) {
        if (newMonitoring != null && !newMonitoring.isBlank()) {
            this.monitoring = newMonitoring;
        }
        if (newResponse != null && !newResponse.isBlank()) {
            this.response = newResponse;
        }
        if (newSetting != null && !newSetting.isBlank()) {
            this.setting = newSetting;
        }
    }
}
