package com.hms.scheduling.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Asks ai-service how likely a patient is to miss an appointment.
 *
 * <p>Wrapped in a circuit breaker with a fallback that returns nothing, because decision support
 * is an enhancement to a booking, not a precondition for one. If the AI service is slow or down,
 * the appointment is still booked — it simply carries no risk score, and the UI shows no badge.
 * The alternative, failing the booking, would make a non-essential service load-bearing for
 * patient care.
 */
@Component
public class NoShowRiskClient {

    private static final Logger log = LoggerFactory.getLogger(NoShowRiskClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public NoShowRiskClient(ObjectMapper objectMapper,
                            @Value("${hms.ai.base-url:http://localhost:8000}") String baseUrl,
                            @Value("${hms.ai.enabled:true}") boolean enabled) {
        // Built directly rather than from an injected RestClient.Builder: Spring Boot 4 moved
        // RestClient auto-configuration into its own module, and this client needs no shared
        // interceptors or load balancing. A directly-built RestClient carries no message
        // converters, so the body is serialised explicitly below rather than handed over as an
        // object - which would silently send an empty body.
        // Pinned to HTTP/1.1: Java's HttpClient defaults to attempting an HTTP/2 upgrade, which
        // ai-service's ASGI server rejects outright as an invalid request.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /** The score and band for one appointment, or empty when decision support is unavailable. */
    @CircuitBreaker(name = "aiService", fallbackMethod = "unavailable")
    @TimeLimiter(name = "aiService")
    public CompletableFuture<Optional<Risk>> score(Request request, String bearerToken) {
        if (!enabled) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.post()
                    .uri("/ai/appointments/no-show-risk")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(request))
                    .retrieve()
                    .body(Map.class);
            if (body == null || body.get("risk_score") == null) {
                return Optional.empty();
            }
            BigDecimal score = new BigDecimal(String.valueOf(body.get("risk_score")));
            String band = String.valueOf(body.getOrDefault("risk_band", "LOW"));
            return Optional.of(new Risk(score, band));
        });
    }

    /**
     * The breaker's fallback. Deliberately silent at warn level and never rethrows: a booking must
     * not fail because a risk score could not be fetched.
     */
    @SuppressWarnings("unused")
    private CompletableFuture<Optional<Risk>> unavailable(Request request, String bearerToken,
                                                          Throwable cause) {
        log.warn("No-show risk unavailable ({}); booking without a score", cause.getMessage());
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /** The request shape ai-service expects, in its snake_case field names. */
    public record Request(int lead_time_days, int patient_age, int previous_appointments,
                          int previous_no_shows, int hour_of_day, int day_of_week,
                          boolean is_first_visit, boolean has_reminder_contact,
                          double distance_km, String priority) {
    }

    public record Risk(BigDecimal score, String band) {
    }
}
