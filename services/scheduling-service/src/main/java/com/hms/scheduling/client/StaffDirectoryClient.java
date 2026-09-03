package com.hms.scheduling.client;

import com.hms.common.error.BadRequestException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Resolves a clinician's user id against patient-service's staff directory.
 *
 * <p>Built like {@link RoomDirectoryClient} and with the same failure policy, for the same kind of
 * reason. An unvalidated room code sends a patient to a room that may not exist; an unvalidated
 * {@code clinicianId} is worse, because since the care-team narrowing that column decides who may
 * read the chart. A UUID nobody checked, written onto an encounter, would be a membership claim
 * the platform made up. So this <strong>refuses the write</strong> rather than degrading, and
 * carries no fallback.
 *
 * <p>{@code staff.user_id} is the only mapping between a login and a person on this platform, which
 * is why the lookup is by user id rather than by staff id: the thing arriving in the request body
 * is a login's subject, and the question is whether a login belongs to somebody who works here.
 */
@Component
public class StaffDirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(StaffDirectoryClient.class);

    private final RestClient restClient;

    public StaffDirectoryClient(@Value("${hms.patient.base-url:http://localhost:8082}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    /** A clinician, as far as this service needs to know them. */
    public record Clinician(UUID userId, String fullName, String designation, String departmentCode) {
    }

    /**
     * Confirms the id belongs to somebody who currently works here.
     *
     * @throws BadRequestException if no active member of staff is linked to that login. The
     *                             caller's mistake — a mistyped id, or a clinician who has left —
     *                             so a 400 with the id named rather than a 502.
     * @throws IllegalStateException if the directory itself is unreachable. Deliberately not
     *                               swallowed: an unverified clinician id must never reach an
     *                               encounter, because access to the chart now turns on it.
     */
    public Clinician require(UUID clinicianId, String bearerToken) {
        Map<String, Object> body;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri("/staff/by-user/{userId}", clinicianId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(Map.class);
            body = response;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new BadRequestException("No clinician on the staff directory holds login "
                        + clinicianId + ". An encounter's clinician has to be somebody who works here:"
                        + " it is who may read the chart afterwards.");
            }
            log.error("Staff directory rejected a lookup for {}: {} {}",
                    clinicianId, ex.getStatusCode(), ex.getMessage());
            throw new IllegalStateException("Could not validate clinician " + clinicianId, ex);
        } catch (RuntimeException ex) {
            log.error("Staff directory unreachable while validating clinician {}", clinicianId, ex);
            throw new IllegalStateException("Could not validate clinician " + clinicianId
                    + ". The staff directory is unreachable, and an encounter must not be opened"
                    + " with an unverified clinician.", ex);
        }

        if (body == null || body.get("userId") == null) {
            throw new IllegalStateException("Staff directory returned no clinician for " + clinicianId);
        }
        return new Clinician(
                UUID.fromString(String.valueOf(body.get("userId"))),
                String.valueOf(body.get("fullName")),
                asString(body.get("designation")),
                asString(body.get("departmentCode")));
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
