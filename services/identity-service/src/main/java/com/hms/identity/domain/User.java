package com.hms.identity.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User extends BaseEntity {

    /** How many consecutive failures lock the account, and for how long. */
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final java.time.Duration LOCK_DURATION = java.time.Duration.ofMinutes(15);

    @Column(name = "username", nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /**
     * The patient this is a portal account for, or null for the staff accounts that are most of
     * this table.
     *
     * <p>Set once, at enrolment, and there is no setter to move it. Re-pointing a live portal
     * account at a different patient would hand somebody a session that already has their old
     * secure-messaging thread in it, and would do so without either patient seeing anything
     * change. Taking access away is {@code deactivate()}; giving it to somebody else is a new
     * account.
     */
    @Column(name = "patient_id", updatable = false)
    private UUID patientId;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new LinkedHashSet<>();

    protected User() {
    }

    public User(String username, String email, String passwordHash, String fullName) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
    }

    /** A portal account: created for one patient, and authorised only over that patient's record. */
    public User(String username, String email, String passwordHash, String fullName, UUID patientId) {
        this(username, email, passwordHash, fullName);
        this.patientId = patientId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    /** Whether this account signs in to the portal rather than to the platform. */
    public boolean isPortalAccount() {
        return patientId != null;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public Set<String> roleCodes() {
        return roles.stream().map(Role::getCode).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public void replaceRoles(Set<Role> newRoles) {
        roles.clear();
        roles.addAll(newRoles);
    }

    /** True while a lockout from repeated failed logins is still in force. */
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    /** Consecutive failed sign-ins since the last success. Reset when the lockout goes on. */
    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public void changePassword(String newHash) {
        this.passwordHash = newHash;
        this.mustChangePassword = false;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    // Sign-in bookkeeping deliberately does NOT live here. Counting a failure or stamping a
    // success by mutating this entity contends on the @Version column, so two sign-ins for the
    // same account at the same instant fail one of them, and a read-modify-write counter loses
    // increments under exactly the burst a lockout exists to stop. Both are single SQL statements
    // in UserRepository, driven by LoginAttemptService. MAX_FAILED_ATTEMPTS and LOCK_DURATION
    // above are the policy those statements apply.
}
