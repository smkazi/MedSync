package com.hms.admissions.client;

import com.hms.common.error.BadRequestException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Beds, from patient-service's facility directory.
 *
 * <p><strong>Fails closed.</strong> This is the {@code RoomDirectoryClient} policy rather than the
 * {@code NoShowRiskClient} one, and the choice is the whole reason both exist. A no-show score is
 * decision support and a booking proceeds without it. A bed is not: allocating one this service
 * could not verify means sending a patient to a bay that may not exist, may not be a clinical
 * space, or may belong to another department — so an unreachable directory refuses the admission
 * rather than guessing, and there is no circuit breaker to degrade through.
 *
 * <p>Forwards the caller's own bearer token, like every other cross-service call driven by a
 * request. There is no service account here and there should not be: admitting a patient is
 * something a clinician does, so the callee applies the clinician's authority rather than a
 * broader one.
 */
@Component
public class BedDirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(BedDirectoryClient.class);

    /**
     * The room types casualty is made of.
     *
     * <p>Asked for by type rather than by room code, which is the seam patient-service documents:
     * this service does not need to know which codes make up casualty in a given building, and a
     * hospital that adds a second resus room does not have to redeploy this one.
     */
    public static final List<String> CASUALTY_TYPES = List.of("EMERGENCY_BAY", "EMERGENCY_ROOM");

    /** The room types an in-patient stays in. */
    public static final List<String> INPATIENT_TYPES = List.of("WARD", "SUITE");

    private final RestClient patients;

    public BedDirectoryClient(@Value("${hms.patient.base-url:http://localhost:8082}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.patients = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    /** A bed, as the directory describes it. */
    public record Bed(UUID id, String code, String label, String roomCode, String roomName,
                      String floorName) {
    }

    /**
     * Every active bed of the given room types.
     *
     * @throws IllegalStateException if the directory is unreachable. Deliberately not swallowed:
     *                               an empty list would read as "the hospital is full", which is a
     *                               very different instruction to a casualty department.
     */
    public List<Bed> bedsOfTypes(List<String> types, String bearerToken) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = patients.get()
                    .uri(uriBuilder -> uriBuilder.path("/beds")
                            .queryParam("type", types.toArray())
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(List.class);
            return body == null ? List.of() : body.stream().map(BedDirectoryClient::toBed).toList();
        } catch (RuntimeException ex) {
            log.error("Facility directory unreachable while listing {} beds", types, ex);
            throw new IllegalStateException(
                    "The facility directory is unreachable, so the free beds cannot be listed. "
                            + "An empty list would read as 'the hospital is full', which is not "
                            + "something this service is willing to imply.", ex);
        }
    }

    /**
     * Confirms one bed exists and is of a type this pathway may use.
     *
     * @throws BadRequestException   when the bed does not exist or is the wrong kind of space —
     *                               both are the caller's mistake, so both are a 400 and the
     *                               message says which
     * @throws IllegalStateException when the directory cannot answer
     */
    public Bed require(UUID bedId, List<String> allowedTypes, String bearerToken) {
        Optional<Bed> found = bedsOfTypes(allowedTypes, bearerToken).stream()
                .filter(bed -> bed.id().equals(bedId))
                .findFirst();
        if (found.isEmpty()) {
            throw new BadRequestException(
                    ("Bed %s is not an available %s bed. It may not exist, may be out of service, "
                            + "or may be a different kind of space — pick one from the free list.")
                            .formatted(bedId, String.join(" or ", allowedTypes).toLowerCase(
                                    java.util.Locale.ROOT).replace('_', ' ')));
        }
        return found.get();
    }

    private static Bed toBed(Map<String, Object> row) {
        return new Bed(UUID.fromString((String) row.get("id")), (String) row.get("code"),
                (String) row.get("label"), (String) row.get("roomCode"),
                (String) row.get("roomName"), (String) row.get("floorName"));
    }

    /** Distinguishes a bad bed from a broken directory for the caller's error handling. */
    public static boolean isDirectoryFailure(RuntimeException ex) {
        return ex instanceof IllegalStateException || ex instanceof RestClientResponseException;
    }
}
