package com.hms.identity.service;

import com.hms.identity.domain.AuditLogEntry;
import com.hms.identity.domain.Role;
import com.hms.identity.domain.User;
import com.hms.identity.web.dto.AuthDtos;

/** Entity to DTO translation, kept in one place so the API shape never drifts per controller. */
public final class UserMapper {

    private UserMapper() {
    }

    public static AuthDtos.UserResponse toResponse(User user) {
        return new AuthDtos.UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getFullName(),
                user.isActive(), user.isMustChangePassword(), user.roleCodes(), user.getLastLoginAt());
    }

    public static AuthDtos.RoleResponse toResponse(Role role) {
        return new AuthDtos.RoleResponse(role.getCode(), role.getDescription());
    }

    public static AuthDtos.AuditResponse toResponse(AuditLogEntry entry) {
        return new AuthDtos.AuditResponse(entry.getId(), entry.getService(), entry.getAction(), entry.getEntity(),
                entry.getEntityId(), entry.getDetail(), entry.getActorId(), entry.getUsername(),
                entry.getCorrelationId(), entry.getOccurredAt());
    }
}
