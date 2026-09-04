package com.hms.patient.web;

import com.hms.common.api.PageResponse;
import com.hms.common.security.Roles;
import com.hms.patient.client.IdentityClient;
import com.hms.patient.service.PatientService;
import com.hms.patient.service.PortalEnrolmentService;
import com.hms.patient.web.dto.PatientDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/patients")
public class PatientController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PatientService service;
    private final PortalEnrolmentService portal;

    public PatientController(PatientService service, PortalEnrolmentService portal) {
        this.service = service;
        this.portal = portal;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.FRONT_DESK)
    public PatientDtos.PatientResponse register(@Valid @RequestBody PatientDtos.CreatePatientRequest request) {
        return service.register(request);
    }

    @GetMapping
    @PreAuthorize(Roles.CLINICAL_READ)
    public PageResponse<PatientDtos.PatientSummary> search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.search(q, includeInactive,
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("lastName", "firstName"))));
    }

    /**
     * A name for an MRN, for a caller who may not read the register.
     *
     * <p>Above {@code /{id}} in this class deliberately: {@code /patients/identify} would
     * otherwise be a candidate for the {@code UUID} path variable and answer 400 rather than
     * matching. Spring resolves the more specific literal path first regardless, and the ordering
     * here says so to the next person reading it.
     */
    @GetMapping("/identify")
    @PreAuthorize(Roles.PATIENT_IDENTIFY)
    public List<PatientDtos.PatientIdentity> identify(@RequestParam(required = false) String q) {
        return service.identify(q);
    }

    /**
     * The children born between two dates: a birthday to compute against, and a name to ask for.
     *
     * <p>Above {@code /{id}} for the reason {@code /identify} is, and gated by its own role rather
     * than that one's — {@code PATIENT_IDENTIFY}'s own javadoc names date of birth as the field it
     * exists to withhold, and this endpoint's whole purpose is to hand it over. See
     * {@link Roles#PATIENT_COHORT_READ} for the argument.
     *
     * <p>The caller is an immunisation clinic working out who to call in. It cannot page: the
     * response says when it was truncated and the answer to that is a narrower range, because a
     * caller walking a decade in pages is doing something this endpoint does not exist for.
     */
    @GetMapping("/cohort")
    @PreAuthorize(Roles.PATIENT_COHORT_READ)
    public PatientDtos.Cohort cohort(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bornFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bornTo,
            @RequestParam(required = false) Integer limit) {
        return service.cohort(bornFrom, bornTo, limit);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.CLINICAL_READ)
    public PatientDtos.PatientResponse get(@PathVariable UUID id) {
        return service.getById(id);
    }

    @GetMapping("/by-mrn/{mrn}")
    @PreAuthorize(Roles.CLINICAL_READ)
    public PatientDtos.PatientResponse getByMrn(@PathVariable String mrn) {
        return service.getByMrn(mrn);
    }

    /**
     * The patient's wristband, as SVG.
     *
     * <p>{@code FRONT_DESK} rather than {@code CLINICAL_READ}: banding a patient is registration's
     * and the ward's job, and the two roles that hold a clinical read without doing it — a
     * pathologist and, through their own gates, the service lines — have no patient in front of
     * them to band.
     *
     * <p>Served as {@code image/svg+xml} so a browser prints it rather than displaying markup, and
     * with {@code no-store} for the reason the tube label is: a band is generated for one patient at
     * one moment, and a cached one is how the wrong wrist ends up with the right barcode.
     */
    @GetMapping(value = "/{id}/wristband", produces = "image/svg+xml")
    @PreAuthorize(Roles.FRONT_DESK)
    public ResponseEntity<String> wristband(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.wristband(id));
    }

    /**
     * Releases the encrypted identifiers. Restricted more tightly than the chart itself and
     * audited on every call, because this is the data a breach would export.
     */
    @GetMapping("/{id}/identifiers")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR','RECEPTIONIST')")
    public PatientDtos.PatientIdentifiers identifiers(@PathVariable UUID id) {
        return service.readIdentifiers(id);
    }

    /**
     * Links an ABHA to a record that already exists.
     *
     * <p>{@code PUT} rather than {@code PATCH} because it replaces both halves together: a number
     * with no address cannot be sent anything and an address with no number cannot be resolved, so
     * there is no partial state worth expressing.
     */
    @PutMapping("/{id}/abha")
    @PreAuthorize(Roles.ABHA_LINK)
    public PatientDtos.PatientResponse linkAbha(@PathVariable UUID id,
            @Valid @RequestBody PatientDtos.LinkAbhaRequest request) {
        return service.linkAbha(id, request);
    }

    /**
     * Where the patient can be reached, and nothing else.
     *
     * <p>Its own endpoint rather than a field on the chart, because the caller is a service that
     * needs a phone number and giving it {@code CLINICAL_READ} to get one would hand it the rest
     * of the record. The same line {@code CHART_READ} draws between looking a patient up and
     * reading their chart, drawn once more a level lower.
     */
    /**
     * Issues or re-issues this patient's portal access, answering the one-time password once.
     *
     * <p>{@code POST} on a sub-resource rather than a field on the record, because issuing a
     * credential is an act and not a state: calling it twice issues two passwords, of which only
     * the second works, and that is the correct behaviour rather than an idempotency problem.
     *
     * <p>The response is the only time the password exists in readable form anywhere. It is not
     * stored, not logged and not emailed — the front desk reads it to the patient, who changes it
     * on first sign-in, at which point every session issued with it is revoked.
     */
    @PostMapping("/{id}/portal-account")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.PORTAL_ENROL)
    public IdentityClient.PortalAccountIssued issuePortalAccess(@PathVariable UUID id,
            HttpServletRequest request) {
        return portal.enrol(id, bearer(request));
    }

    /** Whether this patient has portal access, and whether they have ever used it. */
    @GetMapping("/{id}/portal-account")
    @PreAuthorize(Roles.PORTAL_ENROL)
    public IdentityClient.PortalAccountState portalAccess(@PathVariable UUID id,
            HttpServletRequest request) {
        return portal.find(id, bearer(request));
    }

    /** Withdraws portal access and ends every live session. The account and its history stay. */
    @DeleteMapping("/{id}/portal-account")
    @PreAuthorize(Roles.PORTAL_ENROL)
    public IdentityClient.PortalAccountState withdrawPortalAccess(@PathVariable UUID id,
            HttpServletRequest request) {
        return portal.withdraw(id, bearer(request));
    }

    /**
     * The caller's own token, forwarded to identity-service.
     *
     * <p>Read from the header rather than rebuilt from the decoded {@code Jwt}: a resource server
     * holds the claims, not the encoded string, and this service holds no signing key with which
     * to mint a replacement — which is the property that stops it enrolling anybody on its own.
     */
    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader(org.springframework.http.HttpHeaders.AUTHORIZATION);
        return header == null ? "" : header;
    }

    @GetMapping("/{id}/contact")
    @PreAuthorize(Roles.CONTACT_READ)
    public PatientDtos.PatientContact contact(@PathVariable UUID id) {
        return service.readContact(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize(Roles.FRONT_DESK)
    public PatientDtos.PatientResponse update(@PathVariable UUID id,
                                              @Valid @RequestBody PatientDtos.UpdatePatientRequest request) {
        return service.update(id, request);
    }

    /** Archives rather than deletes: a medical record is a legal document. */
    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.ADMIN_ONLY)
    public PatientDtos.MessageResponse archive(@PathVariable UUID id) {
        service.archive(id);
        return new PatientDtos.MessageResponse("Patient chart archived");
    }

    /**
     * What the patient reacts to, and nothing else.
     *
     * <p>The pharmacy's endpoint. A dispense has to be checked against the allergy list, and the
     * pharmacist doing the checking must not need the chart to do it — so this is
     * {@link Roles#ALLERGY_READ} rather than {@link Roles#CLINICAL_READ}, the same narrowing
     * {@code /contact} makes one level lower. The list is also on the chart response for anybody
     * who may read a chart; this is the door for somebody who may not.
     */
    @GetMapping("/{id}/allergies")
    @PreAuthorize(Roles.ALLERGY_READ)
    public PatientDtos.PatientAllergies allergies(@PathVariable UUID id) {
        return service.readAllergies(id);
    }

    @PostMapping("/{id}/allergies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public PatientDtos.AllergyResponse addAllergy(@PathVariable UUID id,
                                                  @Valid @RequestBody PatientDtos.AddAllergyRequest request) {
        return service.addAllergy(id, request);
    }

    @DeleteMapping("/{id}/allergies/{allergyId}")
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public PatientDtos.MessageResponse removeAllergy(@PathVariable UUID id, @PathVariable UUID allergyId) {
        service.removeAllergy(id, allergyId);
        return new PatientDtos.MessageResponse("Allergy removed");
    }
}
