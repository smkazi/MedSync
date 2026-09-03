package com.hms.scheduling.web;

import com.hms.common.security.Roles;
import com.hms.scheduling.domain.PatientCareGrant;
import com.hms.scheduling.service.CareTeamGuard;
import com.hms.scheduling.web.dto.SchedulingDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "May I see this patient's record?" — asked by the services that hold the rest of it.
 *
 * <p>scheduling-service owns the care team, so it owns the answer. laboratory-service and
 * pharmacy-service hold records that belong to a patient rather than to an encounter, and they
 * cannot answer this from their own tables: a laboratory order knows who ordered it, not who is
 * looking after the person.
 *
 * <p><strong>The answer is about the caller, never about a user named in the request.</strong>
 * There is no {@code userId} parameter here, and that absence is the security property: the asking
 * service forwards the clinician's own bearer token, so the identity comes from a signed claim
 * rather than from a field a caller could set. A service that could ask "is Dr Rao related to this
 * patient" could enumerate the entire care team of every patient on the platform.
 *
 * <p>Deliberately cheap and deliberately synchronous. Every read of a laboratory order by a
 * clinician goes through it, so it is two indexed existence checks and no joins to anything large.
 * The alternative — each service keeping a replica fed by events — was rejected because a clinician
 * who joins a care team must see the patient <em>now</em>: a replica that is a few seconds behind
 * is a doctor being told a chart is not theirs while they are standing at the bedside, and the
 * failure is invisible to everybody except the person it happens to.
 */
@RestController
@RequestMapping("/care-relationships")
public class CareRelationshipController {

    private final CareTeamGuard guard;

    public CareRelationshipController(CareTeamGuard guard) {
        this.guard = guard;
    }

    /**
     * Whether the caller may see this patient's clinical record.
     *
     * <p>{@code CHART_READ}, because this answers a question about a clinical record and the people
     * who may ask it are the people who may hold one. An answer of {@code true} for an
     * administrator or a pathologist is correct rather than a bypass: the narrowing has never
     * applied to them, and this endpoint reports the rule rather than a different one.
     */
    @GetMapping("/{patientId}")
    @PreAuthorize(Roles.CHART_READ)
    public SchedulingDtos.CareRelationshipResponse relationship(@PathVariable UUID patientId) {
        return new SchedulingDtos.CareRelationshipResponse(patientId,
                guard.mayReadPatientRecord(patientId));
    }

    /**
     * Break-glass for the whole of a patient's record.
     *
     * <p>Wider than the encounter version, and the case it exists for is the one that has no
     * encounter to break into: a covering clinician who needs a blood result for somebody they have
     * never charted, or a walk-in test ordered against a patient and nothing else.
     *
     * <p>{@link Roles#CARE_TEAM_JOIN} rather than {@code CHART_READ}: administrators and the
     * service lines are not narrowed, so they have no glass to break and this is not theirs.
     */
    @PostMapping("/{patientId}/break-glass")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.CARE_TEAM_JOIN)
    public SchedulingDtos.PatientCareGrantResponse breakGlass(@PathVariable UUID patientId,
            @Valid @RequestBody SchedulingDtos.BreakGlassRequest request) {
        return toResponse(guard.breakGlassForPatient(patientId, request.reason()));
    }

    /** Every exception granted on this patient — the card on a chart, and the review's raw material. */
    @GetMapping("/{patientId}/grants")
    @PreAuthorize(Roles.CHART_READ)
    public List<SchedulingDtos.PatientCareGrantResponse> grants(@PathVariable UUID patientId) {
        return guard.grantsFor(patientId).stream()
                .map(CareRelationshipController::toResponse)
                .toList();
    }

    private static SchedulingDtos.PatientCareGrantResponse toResponse(PatientCareGrant grant) {
        return new SchedulingDtos.PatientCareGrantResponse(grant.getId(), grant.getPatientId(),
                grant.getUserId(), grant.getReason(), grant.getGrantedAt(), grant.getExpiresAt());
    }
}
