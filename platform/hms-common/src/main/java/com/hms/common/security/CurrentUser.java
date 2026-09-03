package com.hms.common.security;

import com.hms.common.error.BadRequestException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Reads the authenticated principal out of the security context without leaking Spring types upward. */
public final class CurrentUser {

    /** The claim identity-service puts on a portal token. Read here and nowhere else. */
    public static final String PATIENT_CLAIM = "patient_id";

    private CurrentUser() {
    }

    public static Optional<UUID> id() {
        return jwt().map(Jwt::getSubject).flatMap(CurrentUser::parseUuid);
    }

    /**
     * The patient a portal session belongs to, if this is a portal session.
     *
     * <p>The claim exists only on a token minted for an account linked to a patient record, and it
     * is the only place a portal endpoint may learn whose record it is looking at. Not the path,
     * not a query parameter, not a body field: a portal endpoint that took a patient id from the
     * caller would be one tampered request away from another person's chart, and no amount of
     * checking afterwards is as good as there being nothing to tamper with.
     */
    public static Optional<UUID> patientId() {
        return jwt().map(token -> token.getClaimAsString(PATIENT_CLAIM)).flatMap(CurrentUser::parseUuid);
    }

    /**
     * The patient this portal session belongs to, or a refusal saying the session names none.
     *
     * <p>A PATIENT token with no {@code patient_id} should not exist — enrolment writes the link
     * and the role together — but "should not exist" is not a guarantee, and the alternative to
     * failing here is a query with a null patient id, which in this platform's repositories means
     * either everybody's rows or nobody's. Neither is an acceptable answer to "show me my results".
     */
    public static UUID requirePatientId() {
        return patientId().orElseThrow(() -> new BadRequestException(
                "This session is not linked to a patient record, so there is nothing of yours to "
                        + "show. Ask the front desk to re-issue your portal access."));
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

    /**
     * Whether the caller holds any of these roles.
     *
     * <p>For the handful of decisions a {@code @PreAuthorize} expression cannot make, because they
     * depend on data the method has not loaded yet — a care-team check that applies to clinicians
     * and not to administrators, say. Reads the granted authorities rather than the {@code roles}
     * claim, so it agrees with what the SpEL on the method next to it decided.
     */
    public static boolean hasAnyRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        Set<String> wanted = Arrays.stream(roles).map(role -> "ROLE_" + role)
                .collect(Collectors.toUnmodifiableSet());
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(wanted::contains);
    }

    /**
     * The caller's own bearer token, for a service that has to ask another one a question on their
     * behalf.
     *
     * <p>Taken from the security context rather than threaded down from the controller as an
     * argument, which is how this platform used to do it: a token passed by hand is a token
     * somebody eventually forgets to pass, and the failure is a service quietly asking as nobody.
     * Reading it here means a call made inside a request always carries the caller's own authority
     * and can never carry more.
     *
     * <p>Empty outside a request — a Kafka listener, a scheduled job — which is correct: there is
     * no caller to act on behalf of, and whatever needs doing must be justified some other way.
     */
    public static Optional<String> token() {
        return jwt().map(Jwt::getTokenValue);
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
