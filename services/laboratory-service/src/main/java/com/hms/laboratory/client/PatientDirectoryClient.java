package com.hms.laboratory.client;

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
 * Reads the patient's identity from patient-service, for printing on a report.
 *
 * <p><strong>Why this exists when the ASTM worklist deliberately does without it.</strong> Both cross
 * the same service boundary and they answer differently, because the artefacts differ. An analyzer
 * files by sample id and sits in a shared room spooling to a local printer, so putting a name on its
 * worklist screen is identity disclosed for no clinical gain. A pathology report is handed to a
 * patient and filed in a chart, and a report without a name cannot safely be either. The boundary is
 * not "never cross it" — it is "cross it when the artefact genuinely needs what is on the other
 * side".
 *
 * <p><strong>Fail closed.</strong> If the name cannot be fetched, no report is produced. An
 * unidentified pathology report is worse than a missing one: it can be filed against the wrong
 * patient, and there is no way to tell afterwards that it was. Built on the same pattern as
 * scheduling's {@code RoomDirectoryClient}, which fails closed for the same class of reason.
 */
@Component
public class PatientDirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(PatientDirectoryClient.class);

    private final RestClient restClient;

    public PatientDirectoryClient(@Value("${hms.patient.base-url:http://localhost:8082}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    /**
     * The identity to print at the top of a report.
     *
     * @throws BadRequestException   if the patient does not exist. The order references an id that
     *                               patient-service does not know, which is a data problem somebody
     *                               needs to look at rather than a transient fault.
     * @throws IllegalStateException if the directory is unreachable or answers without a name.
     */
    public PatientIdentity require(UUID patientId, String bearerToken) {
        Map<String, Object> body;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri("/patients/{id}", patientId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(Map.class);
            body = response;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new BadRequestException("No such patient: " + patientId);
            }
            log.error("Patient directory rejected a lookup for {}: {}", patientId, ex.getStatusCode());
            throw new IllegalStateException("Could not read the patient for this report", ex);
        } catch (RuntimeException ex) {
            log.error("Patient directory unreachable while building a report for {}", patientId, ex);
            throw new IllegalStateException("The patient directory is unreachable, and a pathology"
                    + " report must not be printed without the patient's name.", ex);
        }

        String fullName = body == null ? null : asString(body.get("fullName"));
        if (fullName == null || fullName.isBlank()) {
            // Not defaulted to "Unknown". A report carrying a placeholder name looks like a report
            // and files like one, and the source project's own note against fabricating identifiers
            // makes the same point.
            throw new IllegalStateException("Patient " + patientId + " has no name to print on a report");
        }
        return new PatientIdentity(fullName, asString(body.get("sex")), asInteger(body.get("age")),
                asString(body.get("mrn")));
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Only what a report header needs. Nothing else is fetched, so nothing else can leak. */
    public record PatientIdentity(String fullName, String sex, Integer age, String mrn) {
    }
}
