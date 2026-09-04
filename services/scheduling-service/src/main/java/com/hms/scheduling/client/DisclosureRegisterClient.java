package com.hms.scheduling.client;

import com.hms.common.error.ServiceUnavailableException;
import com.hms.common.security.CurrentUser;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Records in interop-service's register that a line list named these patients.
 *
 * <p><strong>HTTP and synchronous, not an event, and one fact settles it.</strong>
 * {@code hms.events.transport} defaults to {@code log}, so on the shipped compose deployment and in
 * CI an event-based disclosure write is a log line and the register stays empty — a platform
 * claiming an accounting of disclosures and having none. The whole point of that register is that a
 * patient can ask who has seen their record and be told; an accounting that is empty by default is
 * worse than no claim at all.
 *
 * <p><strong>Register first, file second.</strong> If interop-service cannot be reached, no file is
 * produced: the caller answers 503 and the operator tries again. The residual is real and is the
 * right way round — the two writes are not one transaction, so a crash between them leaves a
 * disclosure row for a file nobody received. An over-recorded disclosure is a question somebody can
 * answer ("we recorded sending this and did not send it"); an unrecorded one is a disclosure that
 * happened and cannot be found, which is the failure the register exists to prevent.
 *
 * <p>Forwards the caller's own token, read from the security context — the newer of the platform's
 * two variants, following {@code CareRelationshipClient} and {@code PatientCohortClient}. The
 * administrator gate is therefore enforced twice, once here and once there, and this service mints
 * no credential of its own: one that could write disclosures unattended would be able to fabricate
 * the register that is supposed to hold this hospital to account.
 */
@Component
public class DisclosureRegisterClient {

    private static final Logger log = LoggerFactory.getLogger(DisclosureRegisterClient.class);

    private final RestClient interop;

    public DisclosureRegisterClient(
            @Value("${hms.interop.base-url:http://localhost:8089}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.interop = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    /** One patient the list named, and how many of their rows it carried. */
    public record Subject(UUID patientId, String patientMrn, int rowCount) {
    }

    /**
     * Registers the disclosure, one row per patient.
     *
     * @return how many rows were written, so the caller can report it on the artefact it is about
     *         to produce
     * @throws AccessDeniedException      if the caller's own token cannot record a disclosure
     * @throws ServiceUnavailableException if the register is unreachable. <strong>The caller must
     *                               not produce its file</strong> — and it does not have to
     *                               remember that, because this throws rather than returning a
     *                               flag. The line list names patients, and a list that went out
     *                               with no record of having gone out is the one outcome this whole
     *                               design refuses
     */
    public int record(String recipient, List<Subject> subjects) {
        String bearerToken = CurrentUser.token().orElse(null);
        if (bearerToken == null || bearerToken.isBlank()) {
            // Not a 503: there is nothing to come back for. A caller with no token is a caller
            // outside a request, which is a wiring mistake and not an outage.
            throw new IllegalStateException("There is no caller token to record a disclosure with, "
                    + "so no line list is produced.");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = interop.post()
                    .uri("/interop/disclosures")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .body(Map.of("recipient", recipient, "subjects", subjects.stream()
                            .map(subject -> Map.of(
                                    "patientId", subject.patientId().toString(),
                                    "patientMrn", subject.patientMrn(),
                                    "rowCount", subject.rowCount()))
                            .toList()))
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                throw new IllegalStateException("Empty response from the disclosure register");
            }
            Object patients = body.get("patients");
            return patients instanceof Number number ? number.intValue() : subjects.size();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            throw new AccessDeniedException("Your role cannot record a public-health disclosure, so "
                    + "no line list is produced. Producing one without recording it is the one "
                    + "outcome this is built to refuse.");
        } catch (RuntimeException ex) {
            log.error("Could not record a public-health disclosure of {} patient(s) to {}",
                    subjects.size(), recipient, ex);
            throw new ServiceUnavailableException("The line list names patients, and the "
                    + "disclosure register could not record that it did. Nothing has been "
                    + "produced. Try again when the register is reachable.", ex);
        }
    }
}
