package com.hms.interop.client;

import com.hms.common.error.NotFoundException;
import com.hms.interop.client.dto.ClinicalViews.EncounterView;
import com.hms.interop.client.dto.ClinicalViews.LabOrderView;
import com.hms.interop.client.dto.ClinicalViews.PatientView;
import com.hms.interop.client.dto.ClinicalViews.PrescriptionView;
import java.net.http.HttpClient;
import java.time.Duration;
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
 * Everything a bundle is built from, read from the services that own it.
 *
 * <p>Four services, one client, and no local copy of any of it. This module holds consent
 * artefacts and a disclosure log; the clinical record stays where it was written, which is what
 * makes an export a view of the record rather than a second record that can drift from it.
 *
 * <p><strong>Forwards the caller's own token.</strong> So the audit trail in patient-service and
 * scheduling-service names the person who caused the read, and so this service cannot read
 * anything its caller could not: an interop service with a powerful service account would be the
 * most attractive credential on the platform — one password away from every chart.
 *
 * <p>Fails closed, like the pharmacy's allergy client, and for a stricter reason: a bundle
 * assembled from whatever happened to be reachable is a partial medical record presented as a
 * complete one, and a receiving clinician has no way to tell. So an unreachable service is an
 * error rather than a section quietly missing from the export.
 */
@Component
public class ClinicalDataClient {

    private static final Logger log = LoggerFactory.getLogger(ClinicalDataClient.class);

    private final RestClient patients;
    private final RestClient scheduling;
    private final RestClient laboratory;
    private final RestClient pharmacy;

    public ClinicalDataClient(
            @Value("${hms.patient.base-url:http://localhost:8082}") String patientUrl,
            @Value("${hms.scheduling.base-url:http://localhost:8083}") String schedulingUrl,
            @Value("${hms.laboratory.base-url:http://localhost:8084}") String laboratoryUrl,
            @Value("${hms.pharmacy.base-url:http://localhost:8087}") String pharmacyUrl) {
        this.patients = client(patientUrl);
        this.scheduling = client(schedulingUrl);
        this.laboratory = client(laboratoryUrl);
        this.pharmacy = client(pharmacyUrl);
    }

    private static RestClient client(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    public PatientView patient(UUID patientId, String bearerToken) {
        return get(patients, "/patients/" + patientId, bearerToken, PatientView.class,
                "patient", patientId);
    }

    public EncounterView encounter(UUID encounterId, String bearerToken) {
        return get(scheduling, "/encounters/" + encounterId, bearerToken, EncounterView.class,
                "encounter", encounterId);
    }

    public LabOrderView labOrder(UUID orderId, String bearerToken) {
        return get(laboratory, "/lab/orders/" + orderId, bearerToken, LabOrderView.class,
                "laboratory order", orderId);
    }

    public PrescriptionView prescription(UUID prescriptionId, String bearerToken) {
        return get(pharmacy, "/prescriptions/" + prescriptionId, bearerToken,
                PrescriptionView.class, "prescription", prescriptionId);
    }

    /**
     * One read, with the failure translation in one place.
     *
     * <p>A 404 from the callee is the caller's 404 and a 403 is their 403 — the lesson the
     * pharmacy's allergy client learned the hard way, where an unknown patient id came back as a
     * 500 and told a prescriber the platform was broken when the fix was in their hands. Anything
     * else is an {@code IllegalStateException}: the export fails rather than proceeding with a
     * gap.
     */
    private <T> T get(RestClient client, String path, String bearerToken, Class<T> type,
                      String what, UUID id) {
        try {
            T body = client.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .body(type);
            if (body == null) {
                // A 2xx with no body. Rare, and treated as a failure rather than as an empty
                // record: an export missing a section nobody asked to omit is the one outcome
                // this client exists to prevent.
                throw new IllegalStateException(
                        "%s %s answered success with no body".formatted(what, id));
            }
            return body;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new NotFoundException("No %s with id %s".formatted(what, id));
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            throw new AccessDeniedException(
                    "Your role may not read the %s this export needs".formatted(what));
        } catch (RuntimeException ex) {
            log.error("Cannot read {} {} for an export: {}", what, id, ex.getMessage());
            throw new IllegalStateException(("The %s could not be read, so nothing has been "
                    + "released. A partial record sent as a complete one is worse than a "
                    + "refusal.").formatted(what), ex);
        }
    }
}
