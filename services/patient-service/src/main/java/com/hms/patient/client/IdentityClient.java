package com.hms.patient.client;

import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Portal accounts, which live in identity-service because credentials do.
 *
 * <p>The direction is deliberate and it is the only one that works. patient-service owns the
 * record, so it is the only service that can say a patient exists, read the MRN a portal username
 * has to be, and read the address the account has to reach; identity-service owns passwords,
 * lockout and token signing, so it is the only service that can mint the account. Enrolment needs
 * both, and this is the pair that does not create a cycle: every service already depends on
 * identity-service for its JWKS, so patient-service calling it is a call down the stack, while
 * identity-service calling patient-service would be a call back up.
 *
 * <p><strong>Forwards the caller's own token</strong>, like interop-service's clinical client and
 * for the same reason: identity-service audits the receptionist who caused the enrolment, and this
 * service holds no credential of its own that could mint an account for anybody.
 */
@Component
public class IdentityClient {

    private final RestClient identity;

    public IdentityClient(@Value("${hms.identity.base-url:http://localhost:8081}") String identityUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.identity = RestClient.builder()
                .baseUrl(identityUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    /** What the desk hands over. The password exists in readable form here and nowhere else. */
    public record PortalAccountIssued(UUID patientId, String username, String temporaryPassword,
                                      Instant issuedAt) {
    }

    /** The state of a patient's portal access, with no credential in it. */
    public record PortalAccountState(UUID patientId, String username, String email, boolean active,
                                     boolean mustChangePassword, Instant lastLoginAt) {
    }

    private record EnrolRequest(UUID patientId, String mrn, String email, String fullName) {
    }

    public PortalAccountIssued enrol(UUID patientId, String mrn, String email, String fullName,
                                     String bearerToken) {
        return call(() -> identity.post()
                .uri("/admin/portal-accounts")
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(new EnrolRequest(patientId, mrn, email, fullName))
                .retrieve()
                .body(PortalAccountIssued.class), "issue portal access");
    }

    public PortalAccountState find(UUID patientId, String bearerToken) {
        return call(() -> identity.get()
                .uri("/admin/portal-accounts/" + patientId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .retrieve()
                .body(PortalAccountState.class), "read portal access");
    }

    public PortalAccountState withdraw(UUID patientId, String bearerToken) {
        return call(() -> identity.delete()
                .uri("/admin/portal-accounts/" + patientId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .retrieve()
                .body(PortalAccountState.class), "withdraw portal access");
    }

    /**
     * The {@code detail} out of an RFC 9457 problem document, or the whole body if it is not one.
     *
     * <p>Every service on this platform renders its refusals through the same handler, so this is
     * reading a shape we control rather than guessing at a foreign one — and the fallback is the
     * raw body rather than a swallowed message, because a refusal nobody can read is worse than an
     * ugly one.
     */
    private static String detailOf(HttpClientErrorException ex) {
        String body = ex.getResponseBodyAsString();
        try {
            String detail = new tools.jackson.databind.ObjectMapper().readTree(body).path("detail").asString();
            return detail == null || detail.isBlank() ? body : detail;
        } catch (RuntimeException parseFailure) {
            return body;
        }
    }

    /**
     * One call, with the failure translation in one place.
     *
     * <p>Fails closed: an unreachable identity-service is an error, never a quiet "no account".
     * Telling a receptionist that a patient has no portal access when the truth is that nobody
     * could ask would have them issue a second set of credentials for an account that already
     * exists, which is the one outcome worth avoiding here.
     */
    private static <T> T call(java.util.function.Supplier<T> request, String what) {
        try {
            T body = request.get();
            if (body == null) {
                throw new IllegalStateException(
                        "identity-service answered success with no body when asked to " + what);
            }
            return body;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new NotFoundException("This patient has no portal access. The front desk can issue it.");
        } catch (HttpClientErrorException.Conflict ex) {
            // The refusal identity-service wrote names which identifier collided and why that
            // matters, and a receptionist can act on it. Replacing it with one of ours would lose
            // that, so the RFC 9457 detail is unwrapped and passed through.
            throw new ConflictException(detailOf(ex));
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            throw new AccessDeniedException("Your role may not " + what + " for a patient");
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Could not " + what + ": identity-service is unreachable", ex);
        }
    }
}
