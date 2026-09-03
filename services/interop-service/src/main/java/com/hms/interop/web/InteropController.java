package com.hms.interop.web;

import com.hms.common.security.Roles;
import com.hms.interop.service.ConsentService;
import com.hms.interop.service.ExchangeService;
import com.hms.interop.web.dto.InteropDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consent, exchange and export over HTTP.
 *
 * <p>Four authorities, and the gaps between them are the design. {@link Roles#CONSENT_READ} lets a
 * clinician see whether a consent exists; {@link Roles#CONSENT_WRITE} lets the front desk record
 * what the patient decided; {@link Roles#HEALTH_INFORMATION_SHARE} lets a clinician send a record
 * under one; {@link Roles#EHI_EXPORT} — administrators alone — exports a whole chart. Nobody holds
 * all four, and the person who records that a patient consented is not the person who acts on it.
 *
 * <p>The bearer token is read off the request rather than injected, because every read this
 * service does is made with the caller's own authority: it holds no service account, so it cannot
 * export a record its caller could not read. The same reason pharmacy-service reads the header.
 */
@RestController
public class InteropController {

    private final ConsentService consents;
    private final ExchangeService exchange;

    public InteropController(ConsentService consents, ExchangeService exchange) {
        this.consents = consents;
        this.exchange = exchange;
    }

    // ---- consent -------------------------------------------------------------

    @GetMapping("/consents")
    @PreAuthorize(Roles.CONSENT_READ)
    public List<InteropDtos.ConsentResponse> consents(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(defaultValue = "false") boolean includeFinished) {
        return patientId == null ? consents.list(includeFinished) : consents.forPatient(patientId);
    }

    @GetMapping("/consents/{artefactId}")
    @PreAuthorize(Roles.CONSENT_READ)
    public InteropDtos.ConsentResponse consent(@PathVariable String artefactId) {
        return consents.read(artefactId);
    }

    @PostMapping("/consents")
    @PreAuthorize(Roles.CONSENT_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public InteropDtos.ConsentResponse request(
            @Valid @RequestBody InteropDtos.RequestConsentRequest request) {
        return consents.request(request);
    }

    /**
     * Records that the patient granted it.
     *
     * <p>"Records", not "grants": the decision is the patient's and this endpoint writes down what
     * they decided. The distinction is the reason a clinician cannot call it.
     */
    @PostMapping("/consents/{artefactId}/grant")
    @PreAuthorize(Roles.CONSENT_WRITE)
    public InteropDtos.ConsentResponse grant(@PathVariable String artefactId,
            @RequestBody(required = false) @Valid InteropDtos.GrantConsentRequest request) {
        return consents.grant(artefactId, request);
    }

    @PostMapping("/consents/{artefactId}/deny")
    @PreAuthorize(Roles.CONSENT_WRITE)
    public InteropDtos.ConsentResponse deny(@PathVariable String artefactId) {
        return consents.deny(artefactId);
    }

    @PostMapping("/consents/{artefactId}/revoke")
    @PreAuthorize(Roles.CONSENT_WRITE)
    public InteropDtos.ConsentResponse revoke(@PathVariable String artefactId,
            @Valid @RequestBody InteropDtos.RevokeConsentRequest request) {
        return consents.revoke(artefactId, request);
    }

    /**
     * Marks lapsed consents expired.
     *
     * <p>Housekeeping a deployment's scheduler can call, and nothing depends on it: the
     * authorisation check compares against the clock on every share, so a consent this has not
     * caught up with is still refused. Exposed so the tidying is observable rather than hidden in
     * a container's crontab.
     */
    @PostMapping("/consents/expire-lapsed")
    @PreAuthorize(Roles.ADMIN_ONLY)
    public InteropDtos.MessageResponse expireLapsed() {
        int expired = consents.expireLapsed();
        return new InteropDtos.MessageResponse(
                "%d lapsed consent(s) marked expired.".formatted(expired));
    }

    // ---- exchange ------------------------------------------------------------

    /**
     * Sends one record to whoever a consent names.
     *
     * <p>Every refusal here names consent, and each one says which of the four conditions failed:
     * not granted, lapsed, the wrong kind of information, or a record outside the period the
     * consent covers. A generic 403 would teach whoever reads it to look for a way round.
     */
    @PostMapping("/interop/share")
    @PreAuthorize(Roles.HEALTH_INFORMATION_SHARE)
    public InteropDtos.ShareResponse share(
            @Valid @RequestBody InteropDtos.ShareRequest request,
            HttpServletRequest httpRequest) {
        return exchange.share(request, bearer(httpRequest));
    }

    /**
     * A patient's whole record, as FHIR.
     *
     * <p>The caller names what to include. That is not laziness about assembling it: this service
     * holds no index of a patient's encounters, and inventing one would be a second copy of
     * something four services already own — so the screen that knows what a patient has passes
     * the ids, and the export is exactly what was asked for and nothing more.
     */
    @PostMapping("/interop/export/{patientId}")
    @PreAuthorize(Roles.EHI_EXPORT)
    public Map<String, Object> export(@PathVariable UUID patientId,
            @RequestParam(required = false) List<UUID> encounterId,
            @RequestParam(required = false) List<UUID> labOrderId,
            @RequestParam(required = false) List<UUID> prescriptionId,
            HttpServletRequest httpRequest) {
        return exchange.exportForPatient(patientId,
                encounterId == null ? List.of() : encounterId,
                labOrderId == null ? List.of() : labOrderId,
                prescriptionId == null ? List.of() : prescriptionId,
                bearer(httpRequest));
    }

    /**
     * What has been released about a patient, and under what.
     *
     * <p>The accounting of disclosures. Readable by the same people who may read a consent,
     * because the question "who has seen my record" is one a patient asks whoever is in front of
     * them.
     */
    @GetMapping("/interop/disclosures")
    @PreAuthorize(Roles.CONSENT_READ)
    public List<InteropDtos.DisclosureResponse> disclosures(@RequestParam UUID patientId) {
        return exchange.disclosuresFor(patientId);
    }

    /**
     * The caller's own token, forwarded to whichever service owns the data.
     *
     * <p>Read from the header rather than rebuilt from the decoded {@code Jwt}: a resource server
     * holds the claims, not the encoded string, and re-signing one here would mean holding a
     * signing key in the service whose whole job is sending data outside the building.
     */
    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header == null ? "" : header;
    }
}
