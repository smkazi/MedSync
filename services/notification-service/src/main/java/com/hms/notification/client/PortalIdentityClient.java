package com.hms.notification.client;

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
 * Who the portal caller is, so a thread can be filed under an MRN the desk recognises.
 *
 * <p>Read from {@code /portal/me} with the patient's own token rather than taken from the token's
 * {@code preferred_username}. The portal account's username happens to be the MRN today, and a
 * thread filed on that coincidence would be filed wrongly the day somebody changes the enrolment
 * convention — silently, and only for threads opened after the change.
 *
 * <p>Distinct from {@link com.hms.notification.service.PatientContactDirectory}, which answers
 * "where can this patient be reached" for the outbound channel and needs a service account to do it
 * without a session. This needs no account at all: the caller is the patient.
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

    public record PortalIdentity(UUID id, String mrn) {
    }

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
                    "Could not read your record, so the message was not sent. Please try again.", ex);
        }
    }
}
