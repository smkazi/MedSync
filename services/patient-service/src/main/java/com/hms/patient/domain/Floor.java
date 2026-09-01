package com.hms.patient.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A storey of the building.
 *
 * <p>{@code level} is signed so a basement is {@code -1} rather than a special case, and it is
 * unique: two rows claiming to be the same storey is a data-entry error, not a valid building.
 */
@Entity
@Table(name = "floors")
public class Floor extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 8)
    private String code;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "level", nullable = false)
    private short level;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Floor() {
    }

    public Floor(String code, String name, short level) {
        this.code = code;
        this.name = name;
        this.level = level;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public short getLevel() {
        return level;
    }

    public void setLevel(short level) {
        this.level = level;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
