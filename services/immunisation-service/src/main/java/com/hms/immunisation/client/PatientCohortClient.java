package com.hms.immunisation.client;

import com.hms.common.error.BadRequestException;
import com.hms.common.security.CurrentUser;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
 * A birth cohort from patient-service: who was born between two dates, and when.
 *
 * <p>This service holds no date of birth and deliberately does not copy one. Two reasons, and the
 * second is the load-bearing one: a copy would be a second home for another service's fact, and
 * that fact is <em>mutable</em> — front desks mistype birthdays and correct them, and a cached
 * birthday would go stale exactly at the moment somebody fixed it. A due list computed from a stale
 * birthday calls children at the wrong time, which is the one thing this feature exists not to do.
 *
 * <p>Read through the narrow {@code GET /patients/cohort}, which answers an id, an MRN, a name and a
 * date of birth and nothing else — the fourth narrowing of the {@code /contact}, {@code /allergies},
 * {@code /identify} kind, and the only one that hands over a birthday, which is why it has a role of
 * its own rather than reusing {@code PATIENT_IDENTIFY}. See {@code Roles.PATIENT_COHORT_READ} for
 * the argument.
 *
 * <p><strong>Forwards the caller's own token, read from the security context.</strong> Two variants
 * of this exist on the platform and they are in documented disagreement: three clients take the
 * token as a method parameter and {@code CareRelationshipClient} reads it from
 * {@link CurrentUser#token()}, its javadoc calling the parameter style "how this platform used to do
 * it". This is the second call site of the newer one, chosen on that argument: a token threaded by
 * hand is one somebody eventually forgets to thread, and that failure looks like a service asking as
 * nobody. Every new client should follow this one rather than the three.
 *
 * <p><strong>Fails closed.</strong> No token, no answer, or an unreachable directory all produce a
 * refusal rather than an empty cohort, because an empty cohort renders as a screen saying no
 * children are due anything — a wrong answer that looks like good news, which is the kind nobody
 * checks.
 *
 * <p><strong>Two reads, because there are two disclosures.</strong> A calling list needs a name —
 * somebody telephones a family and has to ask for a child. A coverage rate does not, and asking for
 * one would be asking for a name with no purpose behind it. So {@link #bornBetween} answers names
 * and {@link #bornBetweenWithoutNames} does not, each gated by its own role in patient-service. The
 * second one exists because the abuse suite refused the first version of the measure endpoint, and
 * the fix was to narrow the disclosure rather than widen the role.
 */
@Component
public class PatientCohortClient {

    private static final Logger log = LoggerFactory.getLogger(PatientCohortClient.class);

    private final RestClient patients;

    public PatientCohortClient(@Value("${hms.patient.base-url:http://localhost:8082}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.patients = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    /** One child: a birthday to compute against, and a name to ask for. */
    public record CohortMember(UUID id, String mrn, String fullName, LocalDate dateOfBirth) {
    }

    /**
     * A cohort, and whether it is all of one.
     *
     * <p>{@code truncated} is carried through rather than dropped, because the children past
     * patient-service's cap are precisely the ones nobody would telephone — the pick-list bug in
     * the README's findings, with a worse outcome.
     */
    public record Cohort(List<CohortMember> members, long total, boolean truncated, String note) {

        public Cohort {
            members = List.copyOf(members);
        }
    }

    /**
     * The children born between two dates, inclusive.
     *
     * @throws AccessDeniedException if the caller's own token cannot list a cohort. Their status
     *                               code and not a 500: a 500 here would hide an authorisation gap
     *                               behind an outage. {@code AccessDeniedException} rather than
     *                               {@code ForbiddenException}, following {@code AllergyClient} —
     *                               so the message below is <em>flattened</em> to the platform's
     *                               generic 403 wording on the way out, deliberately. This is a
     *                               role failure with nothing useful to tell the caller, and
     *                               spelling out which permission they lack on which other service
     *                               narrates the authorisation model to somebody who has just been
     *                               refused by it. The wording is here for the log and the reader
     * @throws BadRequestException   if patient-service refuses the range. It owns the rule and the
     *                               wording, so the message travels rather than being re-invented
     * @throws IllegalStateException if the directory is unreachable, or answers with a member who
     *                               has no birthday
     */
    public Cohort bornBetween(LocalDate bornFrom, LocalDate bornTo, Integer limit) {
        return read("/patients/cohort", bornFrom, bornTo, limit);
    }

    /**
     * The same cohort with the names left off: an id and a date of birth, and nothing else.
     *
     * <p>What a coverage rate reads. It needs a birthday to compute an age against and a key to
     * join the register on; it does not need to know who anybody is, and a rate that fetched names
     * would be fetching them for no purpose its caller could state.
     *
     * <p>This exists because the abuse suite refused the first version. The measure endpoint called
     * {@link #bornBetween} and an epidemiologist was answered 403 by patient-service — correctly,
     * since that role must not be able to list children by name. The fix was to narrow the
     * disclosure rather than to widen the role, which is the direction that decision should always
     * go. See {@code Roles.PATIENT_COHORT_DATES}.
     *
     * <p>Returns the same {@link Cohort} shape with {@code mrn} and {@code fullName} null, because
     * the caller's arithmetic is identical either way and a second nearly-identical record would be
     * two places to change the truncation contract.
     */
    public Cohort bornBetweenWithoutNames(LocalDate bornFrom, LocalDate bornTo, Integer limit) {
        return read("/patients/cohort/dates", bornFrom, bornTo, limit);
    }

    private Cohort read(String path, LocalDate bornFrom, LocalDate bornTo, Integer limit) {
        String bearerToken = CurrentUser.token().orElse(null);
        if (bearerToken == null || bearerToken.isBlank()) {
            // Nothing to forward means the question cannot be asked as anybody, and this service
            // does not hold a credential that could ask on its own behalf. By design: one that
            // could would be able to list every child in the district.
            throw new IllegalStateException("There is no caller token to read the patient directory "
                    + "with, so a due list cannot be built.");
        }
        Map<String, Object> body;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = patients.get()
                    .uri(builder -> {
                        builder.path(path)
                                .queryParam("bornFrom", bornFrom)
                                .queryParam("bornTo", bornTo);
                        if (limit != null) {
                            builder.queryParam("limit", limit);
                        }
                        return builder.build();
                    })
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(Map.class);
            body = response;
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            throw new AccessDeniedException("Your role cannot list a birth cohort, so a due list "
                    + "cannot be built. Listing the children born between two dates is a separate "
                    + "permission from reading the register.");
        } catch (HttpClientErrorException.BadRequest ex) {
            throw new BadRequestException("The patient directory refused that birth range: "
                    + ex.getResponseBodyAsString());
        } catch (RuntimeException ex) {
            log.error("Patient directory unreachable reading {} for {}..{}", path, bornFrom,
                    bornTo, ex);
            throw new IllegalStateException("The patient directory is unreachable, and a due list "
                    + "computed from a guessed age would call children at the wrong time. Nothing "
                    + "has been produced.", ex);
        }
        if (body == null) {
            throw new IllegalStateException("Empty response from the patient cohort endpoint");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) body.getOrDefault("members", List.of());
        List<CohortMember> members = rows.stream().map(PatientCohortClient::toMember).toList();
        return new Cohort(members, asLong(body.get("total"), members.size()),
                Boolean.TRUE.equals(body.get("truncated")), asString(body.get("note")));
    }

    /**
     * One row, validated after parsing rather than trusted.
     *
     * <p>A member with no birthday is refused, never defaulted — the rule
     * {@code PatientDirectoryClient} states as "not defaulted to 'Unknown'". Every date in a due
     * list is arithmetic on this field, so a missing one would not produce a gap on a screen; it
     * would produce a set of due dates measured from an invented birthday.
     */
    private static CohortMember toMember(Map<String, Object> row) {
        String mrn = asString(row.get("mrn"));
        String born = asString(row.get("dateOfBirth"));
        if (born == null || born.isBlank()) {
            throw new IllegalStateException(("The patient directory returned %s with no date of "
                    + "birth. Every date in a due list is arithmetic on it, so nothing is computed "
                    + "for this cohort.").formatted(mrn == null ? "a patient" : mrn));
        }
        try {
            return new CohortMember(UUID.fromString(asString(row.get("id"))), mrn,
                    asString(row.get("fullName")), LocalDate.parse(born));
        } catch (DateTimeParseException | IllegalArgumentException ex) {
            throw new IllegalStateException(("The patient directory returned an unreadable row for "
                    + "%s: %s").formatted(mrn == null ? "a patient" : mrn, ex.getMessage()), ex);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }
}
