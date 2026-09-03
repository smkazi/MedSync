package com.hms.scheduling.client;

import com.hms.common.error.NotFoundException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Who the portal caller is, asked of the service that owns the register.
 *
 * <p>An appointment carries the patient's MRN as well as their id, because every screen that shows
 * one shows the MRN and joining across a service boundary to render a list is not a trade worth
 * making. A portal booking therefore needs an MRN that the token does not carry — and the way it
 * gets one is the point of this class.
 *
 * <p>It calls {@code GET /portal/me} with <strong>the patient's own token</strong>. Not
 * {@code /patients/{id}}, which a PATIENT role cannot open, and not a service credential: the one
 * endpoint on the platform that answers "who is this session" is exactly the endpoint to ask, and
 * asking it with the caller's own token means this service can learn nothing about anybody the
 * caller could not already read. Putting the MRN in the token as a second claim would have been one
 * fewer call and a claim to keep in step with a record that can be corrected.
 */
@Component
public class PortalIdentityClient {

    private final RestClient patients;

    public PortalIdentityClient(@Value("${hms.patient.base-url:http://localhost:8082}") String patientUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.patients = RestClient.builder()
                .baseUrl(patientUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    /** Only the two fields a booking needs. The portal profile carries more; this does not want it. */
    public record PortalIdentity(UUID id, String mrn) {
    }

    /**
     * Fails closed. A booking made with a guessed or blank MRN would be an appointment nobody can
     * find at the desk, so an unreachable register refuses the booking rather than inventing one.
     */
    public PortalIdentity require(String bearerToken) {
        try {
            PortalIdentity identity = patients.get()
                    .uri("/portal/me")
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .body(PortalIdentity.class);
            if (identity == null || identity.mrn() == null || identity.mrn().isBlank()) {
                throw new IllegalStateException("The patient register answered without an MRN");
            }
            return identity;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new NotFoundException(
                    "Your record could not be found. Ask the front desk to check your portal access.");
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            throw new AccessDeniedException("This session may not read a patient record");
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Could not read your record, so the booking was not made. Please try again.", ex);
        }
    }
}
