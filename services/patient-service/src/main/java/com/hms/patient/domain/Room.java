package com.hms.patient.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * A room in the building.
 *
 * <p>Master data, not state. This row says the room exists, what it is for and how to find it; who
 * is in it right now is admissions-service's business, and who is booked into it is
 * scheduling-service's.
 */
@Entity
@Table(name = "rooms")
public class Room extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 16)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /**
     * Eager rather than lazy, deliberately. A room is essentially never useful without knowing
     * whether it is clinical or schedulable — the booking picker, the validation rules and the
     * response mapper all read those flags — so a lazy association here would mean either an extra
     * query per room or a LazyInitializationException outside the transaction.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "room_type_code", nullable = false)
    private RoomType roomType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "floor_id", nullable = false)
    private Floor floor;

    /** The clinic this room belongs to, or null for anything non-clinical. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    /**
     * Bed positions the room was designed to hold.
     *
     * <p>Kept alongside the actual {@code beds} rows rather than derived from them, because it is
     * the designed capacity — which is what you compare the real bed count against when someone
     * asks why a six-position bay only has five beds recorded in it.
     */
    @Column(name = "capacity", nullable = false)
    private short capacity;

    @Column(name = "width_ft", precision = 5, scale = 2)
    private BigDecimal widthFt;

    @Column(name = "length_ft", precision = 5, scale = 2)
    private BigDecimal lengthFt;

    /**
     * What the wayfinding signage says, written as an instruction to a person.
     *
     * <p>Rendered verbatim on the patient's appointment, so "From reception, follow the signs for
     * General" and not "GF, adjacent GF-RCP".
     */
    @Column(name = "directions", length = 255)
    private String directions;

    /**
     * Whether an appointment may be booked into <em>this</em> room.
     *
     * <p>Narrower than the type's {@link RoomType#isSchedulable()} flag, and both are needed. The
     * type says a kind of room can carry appointments; this says this particular one currently
     * does. A consulting room closed for refurbishment keeps its schedulable type — so old
     * appointments still resolve their location — with {@code bookable} false.
     */
    @Column(name = "bookable", nullable = false)
    private boolean bookable;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "notes", length = 500)
    private String notes;

    protected Room() {
    }

    public Room(String code, String name, RoomType roomType, Floor floor) {
        this.code = code;
        this.name = name;
        this.roomType = roomType;
        this.floor = floor;
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

    public RoomType getRoomType() {
        return roomType;
    }

    public void setRoomType(RoomType roomType) {
        this.roomType = roomType;
    }

    public Floor getFloor() {
        return floor;
    }

    public void setFloor(Floor floor) {
        this.floor = floor;
    }

    public Department getDepartment() {
        return department;
    }

    public void setDepartment(Department department) {
        this.department = department;
    }

    public short getCapacity() {
        return capacity;
    }

    public void setCapacity(short capacity) {
        this.capacity = capacity;
    }

    public BigDecimal getWidthFt() {
        return widthFt;
    }

    public void setWidthFt(BigDecimal widthFt) {
        this.widthFt = widthFt;
    }

    public BigDecimal getLengthFt() {
        return lengthFt;
    }

    public void setLengthFt(BigDecimal lengthFt) {
        this.lengthFt = lengthFt;
    }

    public String getDirections() {
        return directions;
    }

    public void setDirections(String directions) {
        this.directions = directions;
    }

    public boolean isBookable() {
        return bookable;
    }

    public void setBookable(boolean bookable) {
        this.bookable = bookable;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    /**
     * Whether an appointment may currently be booked into this room.
     *
     * <p>All three conditions, and each rules out a different mistake: the type must permit
     * appointments at all (so nothing lands in a casualty bay), this room must be marked bookable
     * (so a room out of service is skipped), and the room must be active (so a decommissioned one
     * is too — it stays in the directory only so old appointments still resolve their location).
     */
    public boolean isBookableNow() {
        return active && bookable && roomType != null && roomType.isSchedulable();
    }

    /** Convenience for callers that only care whether patients are treated here. */
    public boolean isClinical() {
        return roomType != null && roomType.isClinical();
    }
}
