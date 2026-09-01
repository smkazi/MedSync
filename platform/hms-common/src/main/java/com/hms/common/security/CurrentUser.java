package com.hms.common.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Reads the authenticated principal out of the security context without leaking Spring types upward. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<UUID> id() {
        return jwt().map(Jwt::getSubject).flatMap(CurrentUser::parseUuid);
    }

    public static Optional<String> username() {
        return jwt().map(token -> token.getClaimAsString("preferred_username"));
    }

    /** The user id, or a stable all-zero id for system-initiated actions (device ingest, schedulers). */
    public static UUID idOrSystem() {
        return id().orElse(new UUID(0L, 0L));
    }

    public static String usernameOrSystem() {
        return username().orElse("system");
    }

    private static Optional<Jwt> jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return Optional.of(token.getToken());
        }
        return Optional.empty();
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
