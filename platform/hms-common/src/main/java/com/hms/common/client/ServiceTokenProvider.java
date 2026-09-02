package com.hms.common.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A bearer token for work that has no signed-in user behind it.
 *
 * <p>Every cross-service call in this platform until now forwarded the caller's own token, which is
 * the right default: the callee then applies the caller's authority rather than a broader one, and
 * nothing gains privilege by being asked through an intermediary. {@code RoomDirectoryClient} and
 * {@code NoShowRiskClient} both work that way.
 *
 * <p>A Kafka consumer has no caller. When a report is released and a patient has to be told, the
 * work is triggered by an event rather than a request, and an event deliberately carries no token —
 * a topic is the wrong place to put a credential, and a token that outlived the request that
 * created it would be a standing grant sitting in a log. So the consumer needs an identity of its
 * own, and this is it: a real account in identity-service, holding one narrow role, signing in with
 * a password supplied by the environment.
 *
 * <p><strong>Why not a symmetric internal key or an allowlisted internal path.</strong> Both were
 * considered and both are worse. A second authentication scheme means a second thing to get wrong,
 * and it would not appear in the audit trail or the role model at all — so "which principal read
 * this patient's phone number" would have no answer. A service account answers it in the same
 * place every other access is answered.
 *
 * <p><strong>The bean does not exist unless it is configured.</strong> No username means no
 * provider, which means the adapters that need one fall back to their inert default rather than
 * silently reaching for a credential that was never set. That is also what keeps a committed
 * default out of the repository: there is nothing to default to.
 *
 * <p>Not a security boundary on its own. What limits the damage if this password leaks is the
 * role the account holds — see {@code Roles.SERVICE}, which reads narrow contact details and
 * nothing else.
 */
@Component
// ConditionalOnExpression rather than ConditionalOnProperty: application.yml declares the key
// with an empty default so the variable is discoverable, and ConditionalOnProperty treats
// present-but-empty as configured. That produced a provider with no username, which is worse than
// none at all - it would sign in as "" and log a failure for every message.
@ConditionalOnExpression("!'${hms.service-account.username:}'.isEmpty()")
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);

    /**
     * How long before expiry a token is replaced.
     *
     * <p>Generous on purpose. A token that expires between the check and the call fails the call,
     * and the alternative cost is one extra sign-in per token lifetime.
     */
    private static final Duration RENEW_BEFORE = Duration.ofSeconds(60);

    private final RestClient identity;
    private final String username;
    private final String password;

    private volatile String token;
    private volatile Instant renewAt = Instant.EPOCH;

    public ServiceTokenProvider(
            @Value("${hms.identity.base-url:http://localhost:8081}") String identityBaseUrl,
            @Value("${hms.service-account.username}") String username,
            @Value("${hms.service-account.password:}") String password) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.identity = RestClient.builder()
                .baseUrl(identityBaseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        this.username = username;
        this.password = password;
    }

    /**
     * The current token, signing in first if there is not a usable one.
     *
     * <p>Empty rather than an exception when identity-service refuses or cannot be reached: the
     * callers of this are all doing work that must degrade rather than fail. A patient not being
     * texted that their report is ready is a bad outcome; a released report rolling back because
     * an SMS could not be addressed is a worse one.
     */
    public Optional<String> token() {
        String current = token;
        if (current != null && Instant.now().isBefore(renewAt)) {
            return Optional.of(current);
        }
        return signIn();
    }

    /** Forgets the cached token, so the next call signs in again. For a 401 the caller saw. */
    public void invalidate() {
        token = null;
        renewAt = Instant.EPOCH;
    }

    private synchronized Optional<String> signIn() {
        // Re-checked inside the lock: several consumer threads waking at once would otherwise
        // each sign in, and identity-service counts failed sign-ins per account towards a lockout.
        String current = token;
        if (current != null && Instant.now().isBefore(renewAt)) {
            return Optional.of(current);
        }
        if (password.isBlank()) {
            log.error("Service account '{}' has no password configured; set the password environment"
                    + " variable for this service or unset the username to disable it", username);
            return Optional.empty();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = identity.post()
                    .uri("/auth/login")
                    .body(Map.of("username", username, "password", password))
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("accessToken") instanceof String issued)) {
                log.error("Service account '{}' signed in but no access token came back", username);
                return Optional.empty();
            }
            long expiresIn = response.get("expiresIn") instanceof Number seconds
                    ? seconds.longValue()
                    : Duration.ofMinutes(15).toSeconds();
            token = issued;
            renewAt = Instant.now().plusSeconds(Math.max(expiresIn, RENEW_BEFORE.toSeconds() * 2))
                    .minus(RENEW_BEFORE);
            log.info("Service account '{}' signed in; token renews in {}s",
                    username, Duration.between(Instant.now(), renewAt).toSeconds());
            return Optional.of(issued);
        } catch (RuntimeException ex) {
            // Named plainly. The most likely causes are a wrong password and an account still
            // carrying the initial-password flag - which issues a token with no roles at all, so
            // every later call would 403 with nothing saying why.
            log.error("Service account '{}' could not sign in: {}", username, ex.getMessage());
            return Optional.empty();
        }
    }
}
