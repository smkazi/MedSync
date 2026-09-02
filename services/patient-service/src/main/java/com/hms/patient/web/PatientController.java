package com.hms.patient.web;

import com.hms.common.api.PageResponse;
import com.hms.common.security.Roles;
import com.hms.patient.service.PatientService;
import com.hms.patient.web.dto.PatientDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public PatientController(PatientService service) {
        this.service = service;
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
     * Releases the encrypted identifiers. Restricted more tightly than the chart itself and
     * audited on every call, because this is the data a breach would export.
     */
    @GetMapping("/{id}/identifiers")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR','RECEPTIONIST')")
    public PatientDtos.PatientIdentifiers identifiers(@PathVariable UUID id) {
        return service.readIdentifiers(id);
    }

    /**
     * Where the patient can be reached, and nothing else.
     *
     * <p>Its own endpoint rather than a field on the chart, because the caller is a service that
     * needs a phone number and giving it {@code CLINICAL_READ} to get one would hand it the rest
     * of the record. The same line {@code CHART_READ} draws between looking a patient up and
     * reading their chart, drawn once more a level lower.
     */
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
