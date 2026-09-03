package com.hms.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request and response shapes for the auth and user-admin endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                                UserResponse user) {
    }

    public record UserResponse(UUID id, String username, String email, String fullName, boolean active,
                               boolean mustChangePassword, Set<String> roles, Instant lastLoginAt) {
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotBlank @Size(max = 160) String fullName,
            Set<String> roles) {
    }

    public record UpdateUserRequest(@Email String email, @Size(max = 160) String fullName, Boolean active,
                                    Set<String> roles) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 12, max = 128) String newPassword) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(min = 12, max = 128) String newPassword) {
    }

    public record RoleResponse(String code, String description) {
    }

    /**
     * Enrol a patient in the portal, or re-issue access to one already enrolled.
     *
     * <p>Sent by patient-service, which owns the record and has already found it. The identifiers
     * come from the record rather than from anything typed at the desk: the username is the MRN the
     * patient already knows, and the address is the one on file. A portal account whose email was
     * retyped by a receptionist is a portal account that can be reset to somebody else's inbox.
     */
    public record EnrolPortalAccountRequest(
            @jakarta.validation.constraints.NotNull UUID patientId,
            @NotBlank @Size(max = 24) String mrn,
            @NotBlank @Email String email,
            @NotBlank @Size(max = 160) String fullName) {
    }

    /**
     * What the desk hands over, and the only time the password exists in readable form.
     *
     * <p>It is generated here, returned once and stored only as a hash, so re-issuing is the only
     * way to recover from a patient who did not write it down. {@code mustChangePassword} is always
     * true on the way out: the receptionist who read this password out loud must not still know the
     * one that opens the record.
     */
    public record PortalAccountIssued(UUID patientId, String username, String temporaryPassword,
                                      Instant issuedAt) {
    }

    /** The state of a patient's portal access, with no credential in it. */
    public record PortalAccountResponse(UUID patientId, String username, String email, boolean active,
                                        boolean mustChangePassword, Instant lastLoginAt) {
    }

    /**
     * One audit row.
     *
     * <p>{@code actorId} is here because it is filterable. A report that lets you narrow by a value
     * it will not show you cannot be checked by the person reading it: there is no way to tell an
     * empty result from a mistyped id. It is null on system-initiated rows — a scheduler, a device
     * ingest, a refresh that carried no session — and that is the honest answer rather than a
     * placeholder.
     */
    public record AuditResponse(UUID id, String service, String action, String entity, String entityId, String detail,
                                String actorId, String username, String correlationId, Instant occurredAt) {
    }

    public record MessageResponse(String message) {
    }

    public record JwksResponse(List<Object> keys) {
    }
}
