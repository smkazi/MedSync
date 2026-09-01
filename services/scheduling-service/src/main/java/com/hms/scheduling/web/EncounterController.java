package com.hms.scheduling.web;

import com.hms.common.security.Roles;
import com.hms.scheduling.service.EncounterService;
import com.hms.scheduling.web.dto.SchedulingDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/encounters")
public class EncounterController {

    private final EncounterService service;

    public EncounterController(EncounterService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public SchedulingDtos.EncounterResponse open(@Valid @RequestBody SchedulingDtos.OpenEncounterRequest request) {
        return service.open(request);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.CLINICAL_READ)
    public SchedulingDtos.EncounterResponse get(@PathVariable UUID id) {
        return service.detail(id);
    }

    @GetMapping("/patients/{patientId}")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<SchedulingDtos.EncounterSummary> forPatient(@PathVariable UUID patientId) {
        return service.forPatient(patientId);
    }

    /**
     * Writes the note. Updates the current revision while it is unsigned, and creates an addendum
     * once it has been signed.
     */
    @PutMapping("/{id}/note")
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public SchedulingDtos.NoteResponse writeNote(@PathVariable UUID id,
                                                 @Valid @RequestBody SchedulingDtos.NoteRequest request) {
        return service.writeNote(id, request);
    }

    /** Signs the note. Only a doctor may attest to a clinical record. */
    @PostMapping("/{id}/note/sign")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR')")
    public SchedulingDtos.NoteResponse signNote(@PathVariable UUID id) {
        return service.signNote(id);
    }

    /** Every revision, so an addendum can be read against what it amended. */
    @GetMapping("/{id}/note/history")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<SchedulingDtos.NoteResponse> noteHistory(@PathVariable UUID id) {
        return service.noteHistory(id);
    }

    @PostMapping("/{id}/vitals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public SchedulingDtos.VitalsResponse recordVitals(@PathVariable UUID id,
                                                      @Valid @RequestBody SchedulingDtos.VitalsRequest request) {
        return service.recordVitals(id, request);
    }

    @PostMapping("/{id}/diagnoses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public SchedulingDtos.DiagnosisResponse addDiagnosis(@PathVariable UUID id,
                                                         @Valid @RequestBody SchedulingDtos.DiagnosisRequest request) {
        return service.addDiagnosis(id, request);
    }

    /** Closes the encounter. Refused while the note is unsigned. */
    @PostMapping("/{id}/close")
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public SchedulingDtos.EncounterResponse close(@PathVariable UUID id) {
        return service.close(id);
    }
}
