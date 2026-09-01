package com.hms.patient.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A clinician or staff member.
 *
 * <p>{@code userId} points at an identity-service account but is deliberately not a foreign key:
 * services own their own schemas, and a staff record must survive an identity outage.
 */
@Entity
@Table(name = "staff")
public class Staff extends BaseEntity {

    @Column(name = "user_id", unique = true)
    private UUID userId;

    @Column(name = "employee_no", nullable = false, unique = true, length = 32)
    private String employeeNo;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(name = "designation", nullable = false, length = 64)
    private String designation;

    @Column(name = "specialty", length = 120)
    private String specialty;

    @Column(name = "license_no", length = 64)
    private String licenseNo;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Staff() {
    }

    public Staff(String employeeNo, String fullName, String designation) {
        this.employeeNo = employeeNo;
        this.fullName = fullName;
        this.designation = designation;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getEmployeeNo() {
        return employeeNo;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Department getDepartment() {
        return department;
    }

    public void setDepartment(Department department) {
        this.department = department;
    }

    public String getDesignation() {
        return designation;
    }

    public void setDesignation(String designation) {
        this.designation = designation;
    }

    public String getSpecialty() {
        return specialty;
    }

    public void setSpecialty(String specialty) {
        this.specialty = specialty;
    }

    public String getLicenseNo() {
        return licenseNo;
    }

    public void setLicenseNo(String licenseNo) {
        this.licenseNo = licenseNo;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
