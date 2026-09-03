package com.hms.interop.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Posts a bundle to an HTTP endpoint.
 *
 * <p>Generic on purpose, exactly like notification-service's SMS adapter: an ABDM Health
 * Information Provider bridge, a gateway a systems integrator runs, or a test double are all "a
 * URL that takes a JSON POST", and hard-coding one vendor's handshake would make the module
 * untestable and wrong for the next deployment.
 *
 * <p><strong>What this is not:</strong> ABDM's real data-flow is a multi-step protocol — a consent
 * manager, a callback, an encrypted payload with an ECDH key exchange, and a certified HIP. This
 * adapter posts a bundle over TLS with a bearer token. It is a place for that protocol to be
 * implemented against a sandbox, and the README says so rather than letting "we have an HTTP
 * adapter" read as "we are ABDM-compliant".
 *
 * <p>A non-2xx is a failed transmission and is reported as one. The bundle is not retried: a retry
 * loop needs a queue, a backoff and a decision about how long a disclosure stays worth attempting,
 * and guessing at those would be worse than a recorded failure somebody can act on.
 */
@Component
@ConditionalOnProperty(name = "hms.interop.gateway", havingValue = "HTTP")
public class HttpAbdmGateway implements AbdmGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpAbdmGateway.class);

    private final RestClient http;
    private final String url;
    private final String authHeader;
    private final String authValue;

    public HttpAbdmGateway(@Value("${hms.interop.gateway-url:}") String url,
                           @Value("${hms.interop.gateway-auth-header:Authorization}")
                           String authHeader,
                           @Value("${hms.interop.gateway-auth-value:}") String authValue) {
        this.url = url;
        this.authHeader = authHeader;
        this.authValue = authValue;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.http = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    @Override
    public Outcome send(Map<String, Object> bundle, String recipient, String reference) {
        if (url.isBlank()) {
            // Configured to use HTTP and given no URL. Refused rather than defaulted to the log
            // adapter: a deployment that meant to transmit and did not would otherwise find out
            // from a patient.
            return new Outcome(false, name(), ("hms.interop.gateway is HTTP and "
                    + "hms.interop.gateway-url is empty, so nothing was sent."));
        }
        try {
            var request = http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Consent-Reference", reference)
                    .header("X-Recipient", recipient);
            if (!authValue.isBlank()) {
                request = request.header(authHeader, authValue);
            }
            var response = request.body(bundle).retrieve().toBodilessEntity();
            boolean ok = response.getStatusCode().is2xxSuccessful();
            return new Outcome(ok, name(), ok
                    ? "Transmitted, gateway answered " + response.getStatusCode().value()
                    : "Gateway answered " + response.getStatusCode().value() + "; nothing was sent");
        } catch (RuntimeException ex) {
            log.error("[abdm] transmission failed for consent {}: {}", reference, ex.getMessage());
            return new Outcome(false, name(),
                    "The gateway could not be reached: " + ex.getMessage());
        }
    }

    @Override
    public String name() {
        return "HTTP";
    }
}
