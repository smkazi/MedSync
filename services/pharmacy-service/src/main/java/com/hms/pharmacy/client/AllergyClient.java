package com.hms.pharmacy.client;

import com.hms.common.error.NotFoundException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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
 * What the patient reacts to, from patient-service.
 *
 * <p><strong>Fails closed, and this is the most consequential instance of that policy on the
 * platform.</strong> If the allergy list cannot be read, the prescription is refused. The
 * alternative — proceeding without the check and noting that it could not run — is a prescription
 * written against an unknown allergy history, which is exactly the state this module exists to
 * prevent. A false block costs a phone call; a false pass costs a patient, and the two are not
 * symmetric.
 *
 * <p>Read through the narrow {@code GET /patients/{id}/allergies} rather than the chart. The
 * endpoint exists so that a pharmacist can be told what a patient reacts to without being handed
 * demographics, appointments and every lab order — the same narrowing {@code /contact} makes for
 * the messaging service one level lower.
 *
 * <p>Forwards the caller's own token. A prescriber prescribing and a pharmacist dispensing each
 * reach patient-service with their own authority, so the audit trail there names the person who
 * caused the read rather than a service account shared by everybody.
 */
@Component
public class AllergyClient {

    private static final Logger log = LoggerFactory.getLogger(AllergyClient.class);

    private final RestClient patients;

    public AllergyClient(@Value("${hms.patient.base-url:http://localhost:8082}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.patients = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    /**
     * One recorded allergy.
     *
     * @param substance what the patient reacts to, as somebody typed it. Matched against ingredient
     *                  codes case-insensitively and on whole words — see
     *                  {@code AllergyChecker}, which is where the matching rules live and are
     *                  tested, rather than here.
     */
    public record Allergy(String substance, String reaction, String severity, boolean critical) {
    }

    /**
     * @throws IllegalStateException if the list cannot be read. Never an empty list: "no allergies
     *                               recorded" and "we could not find out" are different facts, and
     *                               conflating them is how a check silently stops running.
     */
    public List<Allergy> forPatient(UUID patientId, String bearerToken) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = patients.get()
                    .uri("/patients/{id}/allergies", patientId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                throw new IllegalStateException("Empty response from the allergy endpoint");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) body.getOrDefault("allergies", List.of());
            return rows.stream().map(AllergyClient::toAllergy).toList();
        } catch (HttpClientErrorException.NotFound ex) {
            // "There is no such patient" is not the same failure as "the record could not be
            // reached", and answering 500 for the first would tell a prescriber the platform is
            // broken when in fact they have a wrong id. Translated to the caller's own 404 rather
            // than swallowed into the fail-closed path below.
            throw new NotFoundException("No patient with id " + patientId);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            // The caller's token cannot read the allergy list. Their problem, and their status
            // code: a 500 here would hide an authorisation gap behind an outage.
            // AccessDeniedException rather than a new exception type: hms-common's handler already
            // maps it to 403, and the platform gains nothing from a second name for the same thing.
            throw new AccessDeniedException(
                    "Your role cannot read this patient's allergy list, so a medicine cannot be "
                            + "checked against it.");
        } catch (RuntimeException ex) {
            log.error("Allergy list unreachable for patient {}", patientId, ex);
            throw new IllegalStateException(
                    "The patient's allergy list could not be read, so this medicine cannot be "
                            + "checked against it. Nothing has been prescribed. Try again, or "
                            + "record the order on paper and reconcile it when the record is "
                            + "available.", ex);
        }
    }

    private static Allergy toAllergy(Map<String, Object> row) {
        String severity = String.valueOf(row.getOrDefault("severity", "")).toUpperCase(Locale.ROOT);
        return new Allergy((String) row.get("substance"), (String) row.get("reaction"), severity,
                Boolean.TRUE.equals(row.get("critical")));
    }
}
