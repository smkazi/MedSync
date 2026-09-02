package com.hms.scheduling.web;

import com.hms.common.security.Roles;
import com.hms.scheduling.service.EscalationPolicyService;
import com.hms.scheduling.web.dto.SchedulingDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the hospital does about each early-warning band.
 *
 * <p>Its own endpoints rather than a field on the vitals response, because this is policy rather
 * than observation: it changes what a ward is told to do about a deteriorating patient, so it is an
 * administrator's to write and audited when they do.
 *
 * <p>There is deliberately no endpoint for the NEWS2 cut-offs. They are a national standard and
 * live in {@code News2Calculator}; an endpoint that could move them would let a deployment publish
 * a number it calls NEWS2 which is not NEWS2, and that is a wrong answer carrying the authority of
 * a standard. {@code docs/extensibility.md} records the decision.
 */
@RestController
@RequestMapping("/escalation-policies")
public class EscalationController {

    private final EscalationPolicyService policies;

    public EscalationController(EscalationPolicyService policies) {
        this.policies = policies;
    }

    /** Readable by anybody who reads a chart: the policy is what the score is for. */
    @GetMapping
    @PreAuthorize(Roles.CHART_READ)
    public List<SchedulingDtos.EscalationPolicyResponse> all() {
        return policies.all();
    }

    @PatchMapping("/{id}")
    @PreAuthorize(Roles.ADMIN_ONLY)
    public SchedulingDtos.EscalationPolicyResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody SchedulingDtos.UpdateEscalationPolicyRequest request) {
        return policies.update(id, request);
    }
}
