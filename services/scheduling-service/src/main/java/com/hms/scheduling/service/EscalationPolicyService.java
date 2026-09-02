package com.hms.scheduling.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.NotFoundException;
import com.hms.scheduling.domain.EscalationPolicy;
import com.hms.scheduling.repo.EscalationPolicyRepository;
import com.hms.scheduling.web.dto.SchedulingDtos;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hospital's response to each early-warning band.
 *
 * <p>Read on every vitals response, so it is read as one query and handed to the mapper as a map
 * rather than looked up per observation — a chart with twelve sets of observations would otherwise
 * make twelve identical queries.
 */
@Service
public class EscalationPolicyService {

    private final EscalationPolicyRepository policies;
    private final AuditService audit;

    public EscalationPolicyService(EscalationPolicyRepository policies, AuditService audit) {
        this.policies = policies;
        this.audit = audit;
    }

    /** Every band's policy, keyed by band. Missing rows are simply absent from the map. */
    @Transactional(readOnly = true)
    public Map<String, SchedulingDtos.EscalationView> byBand() {
        return policies.findAll().stream().collect(Collectors.toMap(
                EscalationPolicy::getBand,
                policy -> new SchedulingDtos.EscalationView(policy.getMonitoring(),
                        policy.getResponse(), policy.getSetting()),
                (first, second) -> first));
    }

    @Transactional(readOnly = true)
    public List<SchedulingDtos.EscalationPolicyResponse> all() {
        return policies.findAllByOrderByBandAsc().stream()
                .map(policy -> new SchedulingDtos.EscalationPolicyResponse(policy.getId(),
                        policy.getBand(), policy.getMonitoring(), policy.getResponse(),
                        policy.getSetting()))
                .toList();
    }

    /**
     * Revises one band's response.
     *
     * <p>Audited, because it changes what a ward is told to do about a deteriorating patient. The
     * band itself is not editable: it is the calculator's output, not a name somebody chose.
     */
    @Transactional
    public SchedulingDtos.EscalationPolicyResponse update(
            UUID id, SchedulingDtos.UpdateEscalationPolicyRequest request) {
        EscalationPolicy policy = policies.findById(id)
                .orElseThrow(() -> NotFoundException.of("EscalationPolicy", id));
        policy.revise(request.monitoring(), request.response(), request.setting());
        EscalationPolicy saved = policies.save(policy);
        audit.record("ESCALATION_POLICY_UPDATED", "EscalationPolicy", saved.getId(),
                "%s: %s".formatted(saved.getBand(), saved.getMonitoring()));
        return new SchedulingDtos.EscalationPolicyResponse(saved.getId(), saved.getBand(),
                saved.getMonitoring(), saved.getResponse(), saved.getSetting());
    }

    /** Convenience for callers that hold one band. */
    public Function<String, SchedulingDtos.EscalationView> lookup() {
        Map<String, SchedulingDtos.EscalationView> byBand = byBand();
        return byBand::get;
    }
}
