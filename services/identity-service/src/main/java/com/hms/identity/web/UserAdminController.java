package com.hms.identity.web;

import com.hms.common.api.PageResponse;
import com.hms.common.security.Roles;
import com.hms.identity.service.UserAdminService;
import com.hms.identity.service.UserMapper;
import com.hms.identity.web.dto.AuthDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@PreAuthorize(Roles.ADMIN_ONLY)
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthDtos.UserResponse create(@Valid @RequestBody AuthDtos.CreateUserRequest request) {
        return service.create(request);
    }

    @GetMapping("/users")
    public PageResponse<AuthDtos.UserResponse> list(@RequestParam(required = false) String q,
                                                    @RequestParam(required = false) String role,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(
                service.list(q, role, PageRequest.of(page, Math.min(size, 100), Sort.by("username"))),
                UserMapper::toResponse);
    }

    @GetMapping("/users/{id}")
    public AuthDtos.UserResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PatchMapping("/users/{id}")
    public AuthDtos.UserResponse update(@PathVariable UUID id,
                                        @Valid @RequestBody AuthDtos.UpdateUserRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/users/{id}/password")
    public AuthDtos.MessageResponse resetPassword(@PathVariable UUID id,
                                                  @Valid @RequestBody AuthDtos.ResetPasswordRequest request) {
        service.resetPassword(id, request.newPassword());
        return new AuthDtos.MessageResponse("Password reset. The user must change it at next sign-in.");
    }

    @GetMapping("/roles")
    public List<AuthDtos.RoleResponse> roles() {
        return service.listRoles();
    }
}
