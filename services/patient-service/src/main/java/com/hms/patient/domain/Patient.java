package com.hms.patient.domain;

import com.hms.common.crypto.EncryptedStringConverter;
import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/**
 * The demographic record. Every clinical object elsewhere in the platform points at this by id.
 *
 * <p>National id and insurance policy number are encrypted at rest: they identify a person outside
 * the hospital, are never searched on, and are the fields a breach would most damage.
 */
@Entity
@Table(name = "patients")
public class Patient extends BaseEntity {

    @Column(name = "mrn", nullable = false, unique = true, length = 24)
    private String mrn;

    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 80)
    private String lastName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "sex", nullable = false, length = 16)
    private Sex sex;

    @Column(name = "blood_group", length = 8)
    private String bloodGroup;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "address_line1", length = 160)
    private String addressLine1;

    @Column(name = "address_line2", length = 160)
    private String addressLine2;

    @Column(name = "city", length = 80)
    private String city;

    @Column(name = "state", length = 80)
    private String state;

    @Column(name = "postal_code", length = 16)
    private String postalCode;

    @Column(name = "country", length = 80)
    private String country;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "national_id")
    private String nationalId;

    @Column(name = "insurance_provider", length = 120)
    private String insuranceProvider;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "insurance_policy_no")
    private String insurancePolicyNo;

    @Column(name = "emergency_contact_name", length = 160)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", length = 32)
    private String emergencyContactPhone;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "deceased", nullable = false)
    private boolean deceased;

    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PatientAllergy> allergies = new ArrayList<>();

    protected Patient() {
    }

    public Patient(String mrn, String firstName, String lastName, LocalDate dateOfBirth, Sex sex) {
        this.mrn = mrn;
        this.firstName = firstName;
        this.lastName = lastName;
        this.dateOfBirth = dateOfBirth;
        this.sex = sex;
    }

    public String getMrn() {
        return mrn;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    /** Age in whole years today; the value clinicians actually read off a chart. */
    public int age() {
        return Period.between(dateOfBirth, LocalDate.now()).getYears();
    }

    public Sex getSex() {
        return sex;
    }

    public void setSex(Sex sex) {
        this.sex = sex;
    }

    public String getBloodGroup() {
        return bloodGroup;
    }

    public void setBloodGroup(String bloodGroup) {
        this.bloodGroup = bloodGroup;
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

    public String getAddressLine1() {
        return addressLine1;
    }

    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getNationalId() {
        return nationalId;
    }

    public void setNationalId(String nationalId) {
        this.nationalId = nationalId;
    }

    public String getInsuranceProvider() {
        return insuranceProvider;
    }

    public void setInsuranceProvider(String insuranceProvider) {
        this.insuranceProvider = insuranceProvider;
    }

    public String getInsurancePolicyNo() {
        return insurancePolicyNo;
    }

    public void setInsurancePolicyNo(String insurancePolicyNo) {
        this.insurancePolicyNo = insurancePolicyNo;
    }

    public String getEmergencyContactName() {
        return emergencyContactName;
    }

    public void setEmergencyContactName(String emergencyContactName) {
        this.emergencyContactName = emergencyContactName;
    }

    public String getEmergencyContactPhone() {
        return emergencyContactPhone;
    }

    public void setEmergencyContactPhone(String emergencyContactPhone) {
        this.emergencyContactPhone = emergencyContactPhone;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isDeceased() {
        return deceased;
    }

    public void setDeceased(boolean deceased) {
        this.deceased = deceased;
    }

    public List<PatientAllergy> getAllergies() {
        return allergies;
    }

    public void addAllergy(PatientAllergy allergy) {
        allergies.add(allergy);
    }

    public void removeAllergy(PatientAllergy allergy) {
        allergies.remove(allergy);
    }

    /** True when any recorded allergy is severe enough to warrant a chart-level warning. */
    public boolean hasCriticalAllergy() {
        return allergies.stream().anyMatch(PatientAllergy::isCritical);
    }
}
