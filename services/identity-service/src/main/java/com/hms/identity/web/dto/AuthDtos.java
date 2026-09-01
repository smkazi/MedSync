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

    public record AuditResponse(UUID id, String service, String action, String entity, String entityId, String detail,
                                String username, String correlationId, Instant occurredAt) {
    }

    public record MessageResponse(String message) {
    }

    public record JwksResponse(List<Object> keys) {
    }
}
