package com.hms.scheduling.client;

import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
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
 * Raises what an order set names, in the two services that own those orders.
 *
 * <p><strong>This is a saga with compensation, not a transaction, and the code says so where it
 * matters.</strong> A prescription lives in pharmacy-service's schema and a laboratory order in
 * laboratory-service's, so no database transaction spans them and pretending otherwise would be
 * the most dangerous kind of comment. What the platform can promise, and does, is that applying a
 * set either raises everything it names or leaves nothing behind — achieved by ordering the two
 * steps deliberately and undoing the first if the second fails.
 *
 * <p><strong>The prescription goes first.</strong> It is the step that can be refused for a
 * clinical reason: an allergy, an interaction, a retired drug, a role that may not prescribe. If it
 * is refused, nothing has been raised at all and there is nothing to undo. A laboratory order,
 * checked only against a catalogue, almost always succeeds — so putting it second means the
 * compensating cancel is the rare path rather than the common one.
 *
 * <p>The caller's own token is forwarded, so both services apply the clinician's authority rather
 * than a broader one. A nurse applying a set that contains medicines is refused by pharmacy-service
 * itself, which is where that rule belongs; this service translates the refusal instead of
 * duplicating the role list.
 */
@Component
public class OrderingClient {

    private static final Logger log = LoggerFactory.getLogger(OrderingClient.class);

    private final RestClient laboratory;
    private final RestClient pharmacy;

    public OrderingClient(@Value("${hms.laboratory.base-url:http://localhost:8084}") String labUrl,
                          @Value("${hms.pharmacy.base-url:http://localhost:8087}") String pharmacyUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.laboratory = RestClient.builder().baseUrl(labUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient)).build();
        this.pharmacy = RestClient.builder().baseUrl(pharmacyUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient)).build();
    }

    /** What one call raised, so the caller can report it and, if it must, undo it. */
    public record Raised(UUID id, String describe) {
    }

    public Raised prescribe(Map<String, Object> body, String bearerToken) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = pharmacy.post()
                    .uri("/prescriptions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            // A 2xx with no body. Treated as a failure rather than as success with a null id,
            // because the caller's next act on the strength of "it worked" would be to raise the
            // laboratory order — and then there would be a prescription nobody can name to cancel.
            if (response == null || response.get("id") == null) {
                throw new IllegalStateException(
                        "The pharmacy accepted the prescription but returned no id, so it cannot be "
                                + "withdrawn if the rest of the set fails. Nothing further raised.");
            }
            UUID id = UUID.fromString(String.valueOf(response.get("id")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items =
                    (List<Map<String, Object>>) response.getOrDefault("items", List.of());
            return new Raised(id, items.size() + " medicine(s)");
        } catch (HttpClientErrorException ex) {
            throw translate(ex, "medicines");
        }
    }

    public Raised orderTests(Map<String, Object> body, String bearerToken) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = laboratory.post()
                    .uri("/lab/orders")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("id") == null) {
                throw new IllegalStateException(
                        "The laboratory accepted the order but returned no id.");
            }
            UUID id = UUID.fromString(String.valueOf(response.get("id")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items =
                    (List<Map<String, Object>>) response.getOrDefault("items", List.of());
            return new Raised(id, items.size() + " test(s)");
        } catch (HttpClientErrorException ex) {
            throw translate(ex, "tests");
        }
    }

    /**
     * Undoes a prescription that was raised a moment ago.
     *
     * @return true if it was cancelled. False is reported to the caller rather than thrown,
     *         because by then the interesting failure has already happened and the clinician needs
     *         to be told exactly what exists — "the tests could not be raised and the prescription
     *         could not be withdrawn either, cancel it by hand" is a usable instruction, and an
     *         exception that replaced the original one is not.
     */
    public boolean cancelPrescription(UUID id, String bearerToken) {
        try {
            pharmacy.post()
                    .uri("/prescriptions/{id}/cancel", id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException ex) {
            log.error("Compensating cancel failed for prescription {}", id, ex);
            return false;
        }
    }

    /**
     * The callee's own refusal, kept.
     *
     * <p>Not flattened to "could not apply the set". pharmacy-service writes its refusals for
     * people — the allergy it matched, the interaction and what to do instead — and those sentences
     * are the whole reason the check exists. A 403 is translated to a 403 rather than a 500,
     * because "a nurse may not prescribe" is an authorisation fact and not a platform failure.
     */
    private static RuntimeException translate(HttpClientErrorException ex, String what) {
        String detail = detailOf(ex);
        return switch (ex.getStatusCode().value()) {
            case 401, 403 -> new AccessDeniedException(
                    "Your role cannot raise the %s in this set. %s".formatted(what, detail).trim());
            case 409 -> new ConflictException(detail);
            default -> new BadRequestException(detail);
        };
    }

    private static String detailOf(HttpClientErrorException ex) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ex.getResponseBodyAs(Map.class);
            Object detail = body == null ? null : body.get("detail");
            return detail == null ? ex.getMessage() : String.valueOf(detail);
        } catch (RuntimeException ignored) {
            return ex.getMessage();
        }
    }
}
