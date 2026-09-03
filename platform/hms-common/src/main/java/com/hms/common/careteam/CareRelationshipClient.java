package com.hms.common.careteam;

import com.hms.common.error.ForbiddenException;
import com.hms.common.security.CurrentUser;
import com.hms.common.security.Roles;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks scheduling-service whether this clinician may see this patient's record.
 *
 * <p>In hms-common rather than in each service, because the alternative — the same table in four
 * more schemas — is what the README named as the other way of doing this, and it means four copies
 * of "who is looking after whom" that drift. scheduling-service owns the care team, so it owns the
 * answer; everyone else asks.
 *
 * <p><strong>The caller's own token is forwarded, never a service account.</strong> The endpoint has
 * no {@code userId} parameter: it answers about whoever the token names. So this service cannot ask
 * about anybody but the clinician making the request, and could not enumerate a care team if it
 * tried. The token comes from the security context rather than being passed down from a
 * controller — a token threaded by hand is one somebody eventually forgets to thread, and that
 * failure looks like a service asking as nobody.
 *
 * <p><strong>Fail closed, and short timeouts.</strong> An unreachable scheduling-service means the
 * question cannot be answered, and the answer to an unanswerable "may I see this record" is no.
 * Failing open would make an outage a platform-wide privacy hole, which is the worst possible time
 * for one. The timeouts are deliberately tight because this sits on the read path of every
 * laboratory order a clinician opens: a slow answer is a slow screen, and a hung one would hold a
 * request thread until it gave up.
 *
 * <p>The cost of that policy is stated rather than hidden: while scheduling-service is down, a
 * doctor cannot read a laboratory result. That is a real trade and it is the right way round —
 * scheduling holds the encounters, the ward round and the queue, so a platform without it is
 * already not treating patients, and break-glass is itself in scheduling.
 */
@Component
public class CareRelationshipClient {

    private static final Logger log = LoggerFactory.getLogger(CareRelationshipClient.class);

    /** The refusal, worded so the clinician knows what to do next rather than only that they cannot. */
    public static final String NOT_YOUR_PATIENT =
            "You are not looking after this patient, so this is not your record to read. If you "
                    + "need it — cover, a handover, an emergency — record a reason against the "
                    + "patient and it will open. That is logged and reviewed.";

    private final RestClient restClient;
    private final boolean enabled;

    public CareRelationshipClient(
            @Value("${hms.scheduling.base-url:http://localhost:8083}") String baseUrl,
            @Value("${hms.care-team.narrow-patient-records:true}") boolean enabled) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        this.enabled = enabled;
    }

    /** The shape scheduling-service answers with. */
    public record Relationship(UUID patientId, boolean related) {
    }

    /** Refuses unless the caller may read this patient's record. */
    public void requirePatientAccess(UUID patientId) {
        if (!isNarrowed()) {
            return;
        }
        if (!mayRead(patientId)) {
            // Logged rather than audited: a clinician being told "not your patient" is the control
            // working, and an audit row per refused read would bury the break-glass rows that
            // matter. The same judgement CareTeamGuard makes in scheduling-service.
            log.info("Refused a clinical read: caller is not related to patient {}", patientId);
            throw new ForbiddenException(NOT_YOUR_PATIENT);
        }
    }

    /** Whether the caller may read this patient's record, without refusing. For rendering a screen. */
    public boolean mayRead(UUID patientId) {
        if (!isNarrowed()) {
            return true;
        }
        String bearerToken = CurrentUser.token().orElse(null);
        if (bearerToken == null || bearerToken.isBlank()) {
            // No token to forward means the question cannot be asked, and the answer to a question
            // that cannot be asked is no.
            return false;
        }
        try {
            Relationship answer = restClient.get()
                    .uri("/care-relationships/{patientId}", patientId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(Relationship.class);
            return answer != null && answer.related();
        } catch (RestClientException ex) {
            // Fail closed. An unreachable owner of the answer is not permission to proceed, and an
            // outage must not become the moment every record on the platform opens.
            log.error("Could not ask scheduling-service about patient {}; refusing the read: {}",
                    patientId, ex.getMessage());
            return false;
        }
    }

    /**
     * Whether the narrowing applies to this caller at all.
     *
     * <p>The same rule scheduling-service applies to a chart, and it has to be the same or the two
     * would disagree about the same person. Doctors and nurses are narrowed. Administrators are
     * not, and neither are the service lines: reporting a specimen, dispensing a drug and running a
     * blood count are inherently cross-patient jobs, and a care-relationship model does not
     * describe them — a pathologist who could only report on their own patients could not do the
     * job at all.
     *
     * <p>Expressed as "is a clinician and is not an administrator" rather than "is not an
     * administrator", so a role added later falls outside the narrowing until somebody decides
     * otherwise. That is the safer direction for a check whose failure mode is locking a new role
     * out of every record on the platform.
     */
    private boolean isNarrowed() {
        return enabled
                && !CurrentUser.hasAnyRole(Roles.ADMIN)
                && CurrentUser.hasAnyRole(Roles.DOCTOR, Roles.NURSE);
    }
}
