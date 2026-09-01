package com.hms.patient.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One physical bed position.
 *
 * <p>Modelled individually so a bed can be allocated, occupied and reported on by name. A curtained
 * bay position is a bed here exactly as a walled room's bed is: the difference changes how the
 * board reads, not how allocation works.
 *
 * <p>Occupancy is not here. This row says the bed exists; whether someone is in it is
 * admissions-service's, and enforced there by a partial unique index rather than by a flag on this
 * table — a boolean here would be a second copy of the truth, and the two would drift.
 */
@Entity
@Table(name = "beds")
public class Bed extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    /** Unique within the room, not globally: "Bed 1" means something different in each. */
    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "label", length = 60)
    private String label;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Bed() {
    }

    public Bed(Room room, String code, String label) {
        this.room = room;
        this.code = code;
        this.label = label;
    }

    public Room getRoom() {
        return room;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
