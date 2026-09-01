package com.hms.identity.web;

import com.hms.common.error.BadRequestException;
import com.hms.common.security.CurrentUser;
import com.hms.identity.service.AuthService;
import com.hms.identity.web.dto.AuthDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public AuthDtos.TokenResponse login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                        HttpServletRequest httpRequest) {
        return authService.login(request.username(), request.password(), httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/refresh")
    public AuthDtos.TokenResponse refresh(@Valid @RequestBody AuthDtos.RefreshRequest request,
                                          HttpServletRequest httpRequest) {
        return authService.refresh(request.refreshToken(), httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/logout")
    public ResponseEntity<AuthDtos.MessageResponse> logout(@Valid @RequestBody AuthDtos.RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.ok(new AuthDtos.MessageResponse("Logged out"));
    }

    @GetMapping("/me")
    public AuthDtos.UserResponse me() {
        return authService.currentUser(CurrentUser.id()
                .orElseThrow(() -> new BadRequestException("Token has no subject")));
    }

    @PostMapping("/change-password")
    public AuthDtos.MessageResponse changePassword(@Valid @RequestBody AuthDtos.ChangePasswordRequest request) {
        authService.changeOwnPassword(
                CurrentUser.id().orElseThrow(() -> new BadRequestException("Token has no subject")),
                request.currentPassword(), request.newPassword());
        return new AuthDtos.MessageResponse("Password changed. All other sessions have been signed out.");
    }
}
