package com.hms.identity.service;

import com.hms.common.audit.AuditService;
import com.hms.common.data.QueryPatterns;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.identity.domain.Role;
import com.hms.identity.domain.User;
import com.hms.identity.repo.RoleRepository;
import com.hms.identity.repo.UserRepository;
import com.hms.identity.web.dto.AuthDtos;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrative user management: create, list, update, disable, reset password. */
@Service
public class UserAdminService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final AuditService audit;

    public UserAdminService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
                            TokenService tokens, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.audit = audit;
    }

    @Transactional
    public AuthDtos.UserResponse create(AuthDtos.CreateUserRequest request) {
        if (users.existsByUsernameIgnoreCase(request.username())) {
            throw new ConflictException("Username '" + request.username() + "' is already taken");
        }
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("Email '" + request.email() + "' is already registered");
        }
        User user = new User(request.username().toLowerCase(Locale.ROOT), request.email().toLowerCase(Locale.ROOT),
                passwordEncoder.encode(request.password()), request.fullName());
        user.replaceRoles(resolveRoles(request.roles()));
        users.save(user);
        audit.record("USER_CREATED", "User", user.getId(), "roles " + user.roleCodes());
        return UserMapper.toResponse(user);
    }

    @Transactional(readOnly = true)
    public Page<User> list(String query, String role, Pageable pageable) {
        return users.search(QueryPatterns.contains(query), QueryPatterns.exactOrAny(role), pageable);
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserResponse get(UUID id) {
        return UserMapper.toResponse(users.findById(id).orElseThrow(() -> NotFoundException.of("User", id)));
    }

    @Transactional
    public AuthDtos.UserResponse update(UUID id, AuthDtos.UpdateUserRequest request) {
        User user = users.findById(id).orElseThrow(() -> NotFoundException.of("User", id));
        if (request.email() != null && !request.email().equalsIgnoreCase(user.getEmail())) {
            if (users.existsByEmailIgnoreCase(request.email())) {
                throw new ConflictException("Email '" + request.email() + "' is already registered");
            }
            user.setEmail(request.email().toLowerCase(Locale.ROOT));
        }
        if (request.fullName() != null) {
            user.setFullName(request.fullName());
        }
        if (request.roles() != null) {
            user.replaceRoles(resolveRoles(request.roles()));
        }
        if (request.active() != null && request.active() != user.isActive()) {
            user.setActive(request.active());
            if (!request.active()) {
                // Disabling an account must end its sessions immediately, not at token expiry.
                tokens.revokeAllForUser(id, "user-disabled");
            }
        }
        audit.record("USER_UPDATED", "User", id, "active=" + user.isActive() + " roles " + user.roleCodes());
        return UserMapper.toResponse(user);
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        User user = users.findById(id).orElseThrow(() -> NotFoundException.of("User", id));
        user.changePassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        int revoked = tokens.revokeAllForUser(id, "password-reset");
        audit.record("PASSWORD_RESET", "User", id, revoked + " session(s) revoked");
    }

    @Transactional(readOnly = true)
    public java.util.List<AuthDtos.RoleResponse> listRoles() {
        return roles.findAll().stream().map(UserMapper::toResponse).toList();
    }

    private Set<Role> resolveRoles(Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            throw new BadRequestException("At least one role is required");
        }
        Set<Role> resolved = new LinkedHashSet<>(roles.findByCodeIn(codes));
        if (resolved.size() != codes.size()) {
            Set<String> known = resolved.stream().map(Role::getCode).collect(java.util.stream.Collectors.toSet());
            Set<String> unknown = new LinkedHashSet<>(codes);
            unknown.removeAll(known);
            throw new BadRequestException("Unknown role(s): " + unknown);
        }
        return resolved;
    }

}
