package com.hms.notification.channel;

import com.hms.notification.domain.NotificationEnums;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * SMS, or anything else reached by an HTTP POST.
 *
 * <p><strong>Generic on purpose, and this is the decision worth explaining.</strong> Every SMS and
 * WhatsApp gateway on the market takes a different POST body, a different authentication header and
 * a different success shape, and none of them agrees with the others. Hard-coding one would pick a
 * vendor for every deployment of this platform, add a paid dependency, and make the module
 * untestable without that vendor's sandbox. So the request is assembled from configuration: a URL,
 * a header, and the field names the gateway expects for the destination and the text.
 *
 * <p>What that buys: a deployment points this at whichever provider it already pays — or at an
 * open-source SMS gateway on a SIM modem, which is what a small hospital actually runs — and
 * changes three properties rather than writing a class.
 *
 * <p>What it costs: no provider-specific delivery receipts, and no per-provider error taxonomy. A
 * non-2xx is a failure and the body is recorded verbatim. That is the right trade for a module
 * whose job is one sentence and a link.
 *
 * <p>Only present when a URL is configured, so a deployment without an SMS contract simply does
 * not have this channel and falls back to the log.
 */
@Component
@ConditionalOnExpression("!'${hms.notification.sms.url:}'.isEmpty()")
public class HttpGatewayChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(HttpGatewayChannel.class);

    private final RestClient client;
    private final String url;
    private final String toField;
    private final String textField;
    private final Map<String, String> extraFields;
    private final String authHeaderName;
    private final String authHeaderValue;

    public HttpGatewayChannel(
            @Value("${hms.notification.sms.url}") String url,
            @Value("${hms.notification.sms.to-field:to}") String toField,
            @Value("${hms.notification.sms.text-field:text}") String textField,
            // Whatever else the gateway insists on: a sender id, a route, an account reference.
            // A "key=value,key=value" string rather than a bound Map, because a Map bound through
            // @Value cannot represent "no extra fields" - an empty property is a String and Spring
            // has no converter for it, which fails the whole context at startup rather than
            // leaving the channel unconfigured.
            @Value("${hms.notification.sms.extra-fields:}") String extraFields,
            @Value("${hms.notification.sms.auth-header:Authorization}") String authHeaderName,
            // No default. A gateway credential in a repository is a gateway credential in a
            // repository however it is spelled, and an empty value here means the header is simply
            // not sent - which is correct for a gateway on a private network.
            @Value("${hms.notification.sms.auth-value:}") String authHeaderValue) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.client = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        this.url = url;
        this.toField = toField;
        this.textField = textField;
        this.extraFields = parseFields(extraFields);
        this.authHeaderName = authHeaderName;
        this.authHeaderValue = authHeaderValue;
    }

    @Override
    public NotificationEnums.Channel kind() {
        return NotificationEnums.Channel.SMS;
    }

    @Override
    public Delivery send(Recipient recipient, Message message) {
        if (!recipient.hasPhone()) {
            return Delivery.failed(null, "No phone number on file");
        }
        Map<String, String> body = new LinkedHashMap<>(extraFields);
        body.put(toField, recipient.phone());
        body.put(textField, message.body());

        try {
            RestClient.RequestBodySpec request = client.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON);
            if (!authHeaderValue.isBlank()) {
                request = request.header(authHeaderName, authHeaderValue);
            }
            String response = request.body(body).retrieve().body(String.class);
            return Delivery.sent(recipient.phone(), summarise(response));
        } catch (RuntimeException ex) {
            log.warn("SMS gateway rejected a message for patient {}: {}",
                    recipient.patientId(), ex.getMessage());
            return Delivery.failed(recipient.phone(), ex.getMessage());
        }
    }

    /** Parses {@code key=value,key=value}. Blank means none, which is the common case. */
    private static Map<String, String> parseFields(String configured) {
        if (configured == null || configured.isBlank()) {
            return Map.of();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String pair : configured.split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                parsed.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        }
        return Map.copyOf(parsed);
    }

    /**
     * A gateway's success body can be anything from {@code OK} to a page of JSON, and the delivery
     * log wants a note rather than a document.
     */
    private static String summarise(String response) {
        if (response == null || response.isBlank()) {
            return "accepted";
        }
        String trimmed = response.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 197) + "...";
    }
}
