package com.hms.scheduling.client;

import com.hms.common.error.BadRequestException;
import java.net.http.HttpClient;
import java.time.Duration;
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
 * Resolves a room code against patient-service's facility directory.
 *
 * <p>Built the same way as {@link NoShowRiskClient} — directly rather than from an injected
 * {@code RestClient.Builder}, with HTTP/1.1 pinned — but with the opposite failure policy, and the
 * difference is the point.
 *
 * <p>A no-show score is decision support: if it cannot be fetched, the booking proceeds without
 * one, because making a non-essential service load-bearing for patient care would be worse than
 * losing a badge in the UI. A room is not decision support. Writing an unvalidated room code onto
 * an appointment would mean sending a patient to a room that may not exist, may not be a clinical
 * space, or may belong to another clinic — so this client <strong>fails the booking</strong> rather
 * than degrading, and carries no circuit breaker to fall back through.
 *
 * <p>What it returns is deliberately narrow: an id, a name, a floor and the wayfinding text. Only
 * the id and code are cached on the appointment; the rest is read live at render time, so renaming
 * a room does not leave stale directions on tomorrow's appointments.
 */
@Component
public class RoomDirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(RoomDirectoryClient.class);

    private final RestClient restClient;

    public RoomDirectoryClient(@Value("${hms.patient.base-url:http://localhost:8082}") String baseUrl) {
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
     * Looks the room up and confirms it can currently take a booking.
     *
     * @throws BadRequestException if the room does not exist, or exists but cannot be booked into
     *                             (a casualty bay, a corridor, a room out of service). Both are the
     *                             caller's mistake, so both are a 400 rather than a 502 — the front
     *                             desk needs to pick a different room, not to be told the platform
     *                             is broken.
     * @throws IllegalStateException if the directory itself is unreachable. Deliberately not
     *                               swallowed: an unvalidated room code must never reach an
     *                               appointment row.
     */
    public RoomLocation require(String roomCode, String bearerToken) {
        String code = roomCode.trim();
        Map<String, Object> body;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri("/rooms/{code}/location", code)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(Map.class);
            body = response;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new BadRequestException("No such room: '" + code + "'");
            }
            // A 403 here means the caller's own token cannot read the directory, which is a
            // configuration problem rather than a bad room code. Surfaced rather than disguised.
            log.error("Facility directory rejected a room lookup for {}: {} {}",
                    code, ex.getStatusCode(), ex.getMessage());
            throw new IllegalStateException("Could not validate room '" + code + "'", ex);
        } catch (RuntimeException ex) {
            log.error("Facility directory unreachable while validating room {}", code, ex);
            throw new IllegalStateException("Could not validate room '" + code
                    + "'. The facility directory is unreachable, and an appointment must not be"
                    + " written with an unverified room.", ex);
        }

        if (body == null || body.get("id") == null) {
            throw new IllegalStateException("Facility directory returned no room for '" + code + "'");
        }

        RoomLocation room = new RoomLocation(
                UUID.fromString(String.valueOf(body.get("id"))),
                String.valueOf(body.get("code")),
                String.valueOf(body.get("name")),
                asString(body.get("floorName")),
                asString(body.get("directions")),
                Boolean.TRUE.equals(body.get("bookable")));

        if (!room.bookable()) {
            // The directory's own answer, not a list of room types repeated here. A casualty bay,
            // an in-patient suite and a corridor are all unbookable for different reasons, and
            // which reasons those are is patient-service's business.
            throw new BadRequestException(room.name() + " (" + room.code()
                    + ") cannot take a booking. Pick a room from the bookable list.");
        }
        return room;
    }

    /** Optional lookup, for rendering an appointment that already has a room. */
    public Optional<RoomLocation> find(String roomCode, String bearerToken) {
        if (roomCode == null || roomCode.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(require(roomCode, bearerToken));
        } catch (BadRequestException | IllegalStateException ex) {
            // Rendering is not booking. A room that has since been decommissioned or renamed must
            // not stop an old appointment from being displayed.
            log.debug("Room {} could not be resolved for display: {}", roomCode, ex.getMessage());
            return Optional.empty();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record RoomLocation(UUID id, String code, String name, String floorName,
                               String directions, boolean bookable) {
    }
}
