package com.hms.pharmacy.web;

import com.hms.common.security.Roles;
import com.hms.pharmacy.service.AdministrationService;
import com.hms.pharmacy.service.DispensingService;
import com.hms.pharmacy.service.FormularyService;
import com.hms.pharmacy.service.PrescriptionService;
import com.hms.pharmacy.service.SafetyService;
import com.hms.pharmacy.web.dto.PharmacyDtos;
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
 * The medication loop over HTTP.
 *
 * <p>Three roles and three verbs, and the split is the point: {@link Roles#PRESCRIBE} writes an
 * order, {@link Roles#PHARMACY_WRITE} hands the medicine over, {@link Roles#MEDICATION_ADMINISTER}
 * records the dose at the bedside. No role holds more than one of the three, so no single account
 * can write an order, dispense against it and sign that it was given — which is what makes the loop
 * a control rather than a workflow.
 *
 * <p>The bearer token is read off the request rather than injected, because two of these
 * operations forward it to patient-service for the allergy check. Read from the header rather than
 * rebuilt from the {@code Jwt}: a resource server holds the decoded claims, not the encoded string,
 * and re-signing one would mean holding a signing key here.
 */
@RestController
public class PharmacyController {

    private final FormularyService formulary;
    private final SafetyService safety;
    private final PrescriptionService prescriptions;
    private final DispensingService dispensing;
    private final AdministrationService administration;

    public PharmacyController(FormularyService formulary, SafetyService safety,
                              PrescriptionService prescriptions, DispensingService dispensing,
                              AdministrationService administration) {
        this.formulary = formulary;
        this.safety = safety;
        this.prescriptions = prescriptions;
        this.dispensing = dispensing;
        this.administration = administration;
    }

    // ---- formulary and interactions -----------------------------------------

    @GetMapping("/pharmacy/formulary")
    @PreAuthorize(Roles.MEDICATION_READ)
    public List<PharmacyDtos.FormularyResponse> catalogue(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return formulary.catalogue(q, includeInactive);
    }

    @PostMapping("/pharmacy/formulary")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.PHARMACY_WRITE)
    public PharmacyDtos.FormularyResponse addToFormulary(
            @Valid @RequestBody PharmacyDtos.CreateFormularyRequest request) {
        return formulary.add(request);
    }

    @PatchMapping("/pharmacy/formulary/{code}")
    @PreAuthorize(Roles.PHARMACY_WRITE)
    public PharmacyDtos.FormularyResponse updateFormulary(
            @PathVariable String code,
            @Valid @RequestBody PharmacyDtos.UpdateFormularyRequest request) {
        return formulary.update(code, request);
    }

    /**
     * The interaction table, readable by anybody who may read a medication order.
     *
     * <p>Readable widely on purpose: a nurse who has been told two medicines interact should be
     * able to look up what the platform thinks and what it says to do about it, rather than
     * receiving the warning only as a refusal.
     */
    @GetMapping("/pharmacy/interactions")
    @PreAuthorize(Roles.MEDICATION_READ)
    public List<PharmacyDtos.InteractionResponse> interactions() {
        return formulary.pairings();
    }

    @PostMapping("/pharmacy/interactions")
    @PreAuthorize(Roles.PHARMACY_WRITE)
    public PharmacyDtos.InteractionResponse recordInteraction(
            @Valid @RequestBody PharmacyDtos.UpsertInteractionRequest request) {
        return formulary.upsert(request);
    }

    // ---- the check on its own ------------------------------------------------

    /**
     * Runs the checks without writing anything.
     *
     * <p>Exists so a prescribing screen can show the warnings before the button is pressed. The
     * same code path the write uses, so the two cannot disagree about what is safe — a preview
     * computed differently from the enforcement is worse than no preview.
     */
    @PostMapping("/pharmacy/check")
    @PreAuthorize(Roles.MEDICATION_READ)
    public PharmacyDtos.SafetyCheckResponse check(
            @Valid @RequestBody PharmacyDtos.CheckRequest request,
            HttpServletRequest httpRequest) {
        return safety.check(request.patientId(), request.drugCodes(), bearerToken(httpRequest));
    }

    // ---- prescribing ---------------------------------------------------------

    @PostMapping("/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.PRESCRIBE)
    public PharmacyDtos.PrescriptionResponse prescribe(
            @Valid @RequestBody PharmacyDtos.PrescribeRequest request,
            HttpServletRequest httpRequest) {
        return prescriptions.prescribe(request, bearerToken(httpRequest));
    }

    @GetMapping("/prescriptions/{id}")
    @PreAuthorize(Roles.MEDICATION_READ)
    public PharmacyDtos.PrescriptionResponse readPrescription(@PathVariable UUID id) {
        return prescriptions.read(id);
    }

    @GetMapping("/prescriptions")
    @PreAuthorize(Roles.MEDICATION_READ)
    public List<PharmacyDtos.PrescriptionResponse> list(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) UUID encounterId) {
        if (patientId != null) {
            return prescriptions.forPatient(patientId);
        }
        if (encounterId != null) {
            return prescriptions.forEncounter(encounterId);
        }
        return prescriptions.queue();
    }

    @PostMapping("/prescriptions/{id}/cancel")
    @PreAuthorize(Roles.PRESCRIBE)
    public PharmacyDtos.PrescriptionResponse cancel(@PathVariable UUID id) {
        return prescriptions.cancel(id);
    }

    // ---- stock and dispensing ------------------------------------------------

    @GetMapping("/pharmacy/stock")
    @PreAuthorize(Roles.MEDICATION_READ)
    public List<PharmacyDtos.StockBatchResponse> stock(@RequestParam(required = false) String drugCode) {
        return dispensing.stock(drugCode);
    }

    @PostMapping("/pharmacy/stock")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.PHARMACY_WRITE)
    public PharmacyDtos.StockBatchResponse receive(
            @Valid @RequestBody PharmacyDtos.ReceiveStockRequest request) {
        return dispensing.receive(request);
    }

    @PostMapping("/pharmacy/dispenses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.PHARMACY_WRITE)
    public PharmacyDtos.DispenseResponse dispense(
            @Valid @RequestBody PharmacyDtos.DispenseRequest request,
            HttpServletRequest httpRequest) {
        return dispensing.dispense(request, bearerToken(httpRequest));
    }

    // ---- administration ------------------------------------------------------

    @PostMapping("/emar/administer")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.MEDICATION_ADMINISTER)
    public PharmacyDtos.AdministrationResponse administer(
            @Valid @RequestBody PharmacyDtos.AdministerRequest request) {
        return administration.administer(request);
    }

    @PostMapping("/emar/not-given")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.MEDICATION_ADMINISTER)
    public PharmacyDtos.AdministrationResponse notGiven(
            @Valid @RequestBody PharmacyDtos.NotGivenRequest request) {
        return administration.notGiven(request);
    }

    @GetMapping("/emar/items/{itemId}")
    @PreAuthorize(Roles.MEDICATION_READ)
    public List<PharmacyDtos.AdministrationResponse> doses(@PathVariable UUID itemId) {
        return administration.forItem(itemId);
    }

    /**
     * The caller's own bearer token, for the calls this service makes on their behalf.
     *
     * <p>Read off the header rather than reconstructed: a resource server holds decoded claims, not
     * the encoded string, and re-signing one would mean holding a signing key in every service.
     * Returns null rather than throwing when absent — the security filter has already refused an
     * unauthenticated request, and a method that throws here would be dead code that looks live.
     */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring("Bearer ".length()).trim();
    }
}
