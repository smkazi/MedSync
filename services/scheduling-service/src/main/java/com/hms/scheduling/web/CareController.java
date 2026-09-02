package com.hms.scheduling.web;

import com.hms.common.security.Roles;
import com.hms.scheduling.domain.SchedulingEnums;
import com.hms.scheduling.service.CarePlanService;
import com.hms.scheduling.service.OrderSetService;
import com.hms.scheduling.web.dto.CareDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order sets and care plans.
 *
 * <p><strong>Applying a set is gated on {@link Roles#CLINICAL_WRITE}, not on who may
 * prescribe.</strong> A nurse applying a laboratory-only set is doing something a nurse does; a
 * nurse applying one that contains medicines is refused by pharmacy-service, because the caller's
 * own token is what goes downstream. Putting a second role list here would be a copy of that rule
 * which could drift from it, and the drift would be silent in the dangerous direction.
 *
 * <p>Writing a set, on the other hand, is administrative: an order set is a template applied in one
 * click by anybody who may chart, so who may compose one is a narrower question than who may use it.
 */
@RestController
public class CareController {

    private final OrderSetService orderSets;
    private final CarePlanService carePlans;

    public CareController(OrderSetService orderSets, CarePlanService carePlans) {
        this.orderSets = orderSets;
        this.carePlans = carePlans;
    }

    // ---- order sets ----------------------------------------------------------

    @GetMapping("/order-sets")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<CareDtos.OrderSetResponse> list(
            @RequestParam(required = false) String department,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return orderSets.available(department, includeInactive);
    }

    @GetMapping("/order-sets/{code}")
    @PreAuthorize(Roles.CLINICAL_READ)
    public CareDtos.OrderSetResponse read(@PathVariable String code) {
        return orderSets.read(code);
    }

    @PostMapping("/order-sets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ADMIN_ONLY)
    public CareDtos.OrderSetResponse create(
            @Valid @RequestBody CareDtos.CreateOrderSetRequest request) {
        return orderSets.create(request);
    }

    @PatchMapping("/order-sets/{code}")
    @PreAuthorize(Roles.ADMIN_ONLY)
    public CareDtos.OrderSetResponse update(@PathVariable String code,
                                            @Valid @RequestBody CareDtos.UpdateOrderSetRequest request) {
        return orderSets.update(code, request);
    }

    @PostMapping("/order-sets/{code}/apply")
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public CareDtos.ApplyOrderSetResponse apply(
            @PathVariable String code,
            @Valid @RequestBody CareDtos.ApplyOrderSetRequest request,
            HttpServletRequest httpRequest) {
        return orderSets.apply(code, request, bearerToken(httpRequest));
    }

    // ---- care plans ----------------------------------------------------------

    @GetMapping("/care-plans")
    @PreAuthorize(Roles.CHART_READ)
    public List<CareDtos.CarePlanResponse> plans(@RequestParam UUID patientId) {
        return carePlans.forPatient(patientId);
    }

    @GetMapping("/care-plans/encounters/{encounterId}")
    @PreAuthorize(Roles.CHART_READ)
    public CareDtos.CarePlanResponse forEncounter(@PathVariable UUID encounterId) {
        return carePlans.forEncounter(encounterId);
    }

    @PostMapping("/care-plans")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public CareDtos.CarePlanResponse createPlan(
            @Valid @RequestBody CareDtos.CreateCarePlanRequest request) {
        return carePlans.create(request);
    }

    @PostMapping("/care-plans/{id}/goals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public CareDtos.CarePlanResponse addGoal(@PathVariable UUID id,
                                             @Valid @RequestBody CareDtos.AddGoalRequest request) {
        return carePlans.addGoal(id, request);
    }

    @PatchMapping("/care-plans/goals/{goalId}")
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public CareDtos.CarePlanResponse recordGoal(
            @PathVariable UUID goalId,
            @Valid @RequestBody CareDtos.RecordGoalRequest request) {
        return carePlans.recordGoal(goalId, request);
    }

    @PostMapping("/care-plans/{id}/close")
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public CareDtos.CarePlanResponse close(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "COMPLETED") SchedulingEnums.CarePlanStatus outcome) {
        return carePlans.close(id, outcome);
    }

    /**
     * The caller's own bearer token, forwarded when this service raises orders elsewhere.
     *
     * <p>Read off the header rather than rebuilt: a resource server holds decoded claims, not the
     * encoded string, and re-signing one would mean holding a signing key here.
     */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring("Bearer ".length()).trim();
    }
}
