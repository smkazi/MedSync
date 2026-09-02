package com.hms.notification.service;

import com.hms.common.client.ServiceTokenProvider;
import com.hms.notification.channel.Recipient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Reads patient-service's narrow contact endpoint as a service account.
 *
 * <p>`GET /patients/{id}/contact` returns a phone number, an email address and whether the record
 * is active — no name, no MRN, no date of birth, nothing clinical. It exists precisely so this
 * service does not have to hold {@code CLINICAL_READ} to find out where to send a message.
 *
 * <p><strong>Degrades rather than throws, everywhere.</strong> No service account configured, a
 * sign-in that failed, an unreachable patient-service, a 403, an archived record: every one of them
 * answers empty and the notification is recorded {@code SUPPRESSED} with a reason. The policy is
 * the {@code NoShowRiskClient} one rather than the {@code RoomDirectoryClient} one, and the choice
 * is deliberate — an unverified room code must never reach an appointment, but a patient not being
 * texted is not worth failing a clinical transaction over.
 *
 * <p>An archived record is treated as unreachable on purpose. Messaging one is at best useless and
 * at worst a message to somebody who has died, and the endpoint returns {@code active} so that this
 * decision can be made here rather than assumed.
 */
@Component
public class PatientContactDirectory implements ContactDirectory {

    private static final Logger log = LoggerFactory.getLogger(PatientContactDirectory.class);

    private final RestClient patients;
    private final ObjectProvider<ServiceTokenProvider> tokens;

    public PatientContactDirectory(
            @Value("${hms.patient.base-url:http://localhost:8082}") String baseUrl,
            // ObjectProvider rather than a required dependency: ServiceTokenProvider only exists
            // when a service account is configured, and this class has a real answer for its
            // absence. A conditional on the bean would put that answer in Spring's wiring instead
            // of in code somebody reading this file can see.
            ObjectProvider<ServiceTokenProvider> tokens) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.patients = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        this.tokens = tokens;
    }

    @Override
    public Optional<Recipient> find(UUID patientId) {
        ServiceTokenProvider provider = tokens.getIfAvailable();
        if (provider == null) {
            return Optional.empty();
        }
        Optional<String> token = provider.token();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = patients.get()
                    .uri("/patients/{id}/contact", patientId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get())
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                return Optional.empty();
            }
            if (Boolean.FALSE.equals(body.get("active"))) {
                log.info("Patient {} is archived; not messaging", patientId);
                return Optional.empty();
            }
            Recipient recipient = new Recipient(patientId, text(body.get("phone")), text(body.get("email")));
            return recipient.isUnreachable() ? Optional.empty() : Optional.of(recipient);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                // The token was fine when it was cached and is not now. Dropped so the next
                // message signs in again rather than repeating a request that cannot succeed.
                provider.invalidate();
            }
            log.warn("Contact lookup for patient {} refused: {}", patientId, ex.getStatusCode());
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("Contact lookup for patient {} failed: {}", patientId, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public String unavailableReason() {
        return tokens.getIfAvailable() == null
                ? "No service account is configured, so patient contact details could not be looked"
                        + " up. The message was composed and recorded but not sent."
                : "No usable phone number or email address is on file for this patient, or the"
                        + " record is archived.";
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }
}
