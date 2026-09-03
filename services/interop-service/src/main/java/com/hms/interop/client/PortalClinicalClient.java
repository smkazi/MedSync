package com.hms.interop.client;

import com.hms.common.error.NotFoundException;
import com.hms.interop.client.dto.ClinicalViews.EncounterView;
import com.hms.interop.client.dto.ClinicalViews.LabOrderView;
import com.hms.interop.client.dto.ClinicalViews.PatientView;
import com.hms.interop.client.dto.ClinicalViews.PrescriptionView;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * The same four services, read through the doors a patient actually has.
 *
 * <p>A patient exporting their own record cannot use {@link ClinicalDataClient}: every endpoint it
 * calls is gated on a clinical role, which is exactly the property that makes a PATIENT session
 * safe. So this is the portal's own fan-out, and it differs from the staff one in two ways that
 * matter.
 *
 * <p>First, <strong>it takes no ids.</strong> The staff export is given the encounters, orders and
 * prescriptions to include, because an administrator exporting a record is answering a specific
 * request. A patient asking for their record means all of it, and asking them to enumerate their
 * own encounter ids would be asking them to do the platform's job. So this client lists first and
 * then reads, and the export is whatever the portal itself would show.
 *
 * <p>Second, <strong>it inherits the portal's own filters rather than reimplementing them.</strong>
 * Unverified laboratory results are absent because {@code /portal/reports} does not offer them, and
 * unsigned notes are absent because {@code /portal/encounters} has already dropped them. A second
 * copy of either rule here would be a second copy to keep in step, and the day they disagreed the
 * export would be the one that was wrong.
 */
@Component
public class PortalClinicalClient {

    private final RestClient patients;
    private final RestClient scheduling;
    private final RestClient laboratory;
    private final RestClient pharmacy;

    public PortalClinicalClient(
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

    /** What {@code /portal/me} answers. Named locally, like every other view in this service. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PortalProfileView(UUID id, String mrn, String firstName, String lastName,
                             LocalDate dateOfBirth, String sex, String phone, String email,
                             String city, String state, String country, boolean active) {
    }

    /** One row of {@code /portal/reports}: enough to know whether there is a report to fetch. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PortalReportRow(UUID orderId, boolean reportAvailable) {
    }

    /**
     * Who this session is.
     *
     * <p>{@code deceased} is hard-coded false rather than read, and it is the one field this client
     * invents. The portal profile does not carry it — a screen that told its reader the hospital
     * believes them to be dead would be a defect however true it was — and the person holding this
     * session has just signed in, which is the strongest evidence available.
     */
    public PatientView me(String bearerToken) {
        PortalProfileView profile = get(patients, "/portal/me", bearerToken,
                PortalProfileView.class, "your record");
        return new PatientView(profile.id(), profile.mrn(), profile.firstName(), profile.lastName(),
                profile.dateOfBirth(), profile.sex(), profile.phone(), profile.email(),
                profile.city(), profile.state(), profile.country(), profile.active(), false);
    }

    public List<EncounterView> myEncounters(String bearerToken) {
        return getList(scheduling, "/portal/encounters", bearerToken,
                new ParameterizedTypeReference<List<EncounterView>>() { }, "your visits");
    }

    public List<PrescriptionView> myPrescriptions(String bearerToken) {
        return getList(pharmacy, "/portal/prescriptions", bearerToken,
                new ParameterizedTypeReference<List<PrescriptionView>>() { }, "your prescriptions");
    }

    /** Released orders only, because that is all {@code /portal/reports} will hand over. */
    public List<LabOrderView> myReleasedLabOrders(String bearerToken) {
        return getList(laboratory, "/portal/reports", bearerToken,
                new ParameterizedTypeReference<List<PortalReportRow>>() { }, "your results").stream()
                .filter(PortalReportRow::reportAvailable)
                .map(row -> get(laboratory, "/portal/reports/" + row.orderId(), bearerToken,
                        LabOrderView.class, "one of your results"))
                .toList();
    }

    /**
     * Fails closed, for the reason the staff client does: a record assembled from whatever happened
     * to be reachable is a partial record presented as a whole one, and the patient downloading it
     * has no way to tell which section is missing.
     */
    private <T> T get(RestClient client, String path, String bearerToken, Class<T> type, String what) {
        try {
            T body = client.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .body(type);
            if (body == null) {
                throw new IllegalStateException(what + " answered success with no body");
            }
            return body;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new NotFoundException("Could not find " + what);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            throw new AccessDeniedException("This session may not read " + what);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Could not read " + what + ", so the export was not made. Please try again.", ex);
        }
    }

    private <T> List<T> getList(RestClient client, String path, String bearerToken,
                                ParameterizedTypeReference<List<T>> type, String what) {
        try {
            List<T> body = client.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .body(type);
            return body == null ? List.of() : body;
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            throw new AccessDeniedException("This session may not read " + what);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Could not read " + what + ", so the export was not made. Please try again.", ex);
        }
    }
}
