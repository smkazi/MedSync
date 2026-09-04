package com.hms.immunisation.web;

import com.hms.common.security.Roles;
import com.hms.immunisation.domain.ImmunisationEnums.ImmunisationSource;
import com.hms.immunisation.service.CatalogueService;
import com.hms.immunisation.service.DueListService;
import com.hms.immunisation.service.ImmunisationService;
import com.hms.immunisation.service.MeasureService;
import com.hms.immunisation.service.VaccineStockService;
import com.hms.immunisation.web.dto.ImmunisationDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
 * The register's API.
 *
 * <p>Three gates, and the separation between them is what the {@code @PreAuthorize} on each method
 * says. A clinician gives a vaccine and records one from a card ({@code IMMUNISE}); the pharmacy
 * and the ward manage the cold chain ({@code VACCINE_STOCK}); and an administrator changes what
 * exists at all ({@code IMMUNISATION_CONFIG}) — because editing the catalogue changes what every
 * other answer means.
 */
@RestController
public class ImmunisationController {

    private final ImmunisationService register;
    private final CatalogueService catalogue;
    private final VaccineStockService stock;
    private final DueListService dueList;
    private final MeasureService measures;

    public ImmunisationController(ImmunisationService register, CatalogueService catalogue,
                                  VaccineStockService stock, DueListService dueList,
                                  MeasureService measures) {
        this.register = register;
        this.catalogue = catalogue;
        this.stock = stock;
        this.dueList = dueList;
        this.measures = measures;
    }

    // ---- the catalogue -------------------------------------------------------

    /**
     * What can be given.
     *
     * <p>Readable by anybody signed in, like the laboratory and radiology catalogues: it is a list
     * of vaccine names with no patient anywhere in it, and a recording screen that could not read
     * it would be a recording screen with an empty select.
     */
    @GetMapping("/vaccines/products")
    @PreAuthorize("isAuthenticated()")
    public List<ImmunisationDtos.ProductResponse> products() {
        return catalogue.products();
    }

    /** What those products protect against — the vocabulary a schedule and a measure are written in. */
    @GetMapping("/vaccines/antigens")
    @PreAuthorize("isAuthenticated()")
    public List<ImmunisationDtos.AntigenResponse> antigens() {
        return catalogue.antigens();
    }

    @PostMapping("/vaccines/antigens")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.IMMUNISATION_CONFIG)
    public ImmunisationDtos.AntigenResponse addAntigen(
            @Valid @RequestBody ImmunisationDtos.CreateAntigenRequest request) {
        return catalogue.addAntigen(request);
    }

    @PostMapping("/vaccines/products")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.IMMUNISATION_CONFIG)
    public ImmunisationDtos.ProductResponse addProduct(
            @Valid @RequestBody ImmunisationDtos.CreateProductRequest request) {
        return catalogue.addProduct(request);
    }

    /** Retiring a product, or bringing one back. There is no delete: doses reference it forever. */
    @PatchMapping("/vaccines/products/{code}")
    @PreAuthorize(Roles.IMMUNISATION_CONFIG)
    public ImmunisationDtos.ProductResponse setProductActive(
            @PathVariable String code, @Valid @RequestBody SetActiveRequest request) {
        return catalogue.setProductActive(code, request.active());
    }

    public record SetActiveRequest(@NotNull Boolean active) {
    }

    // ---- the schedule --------------------------------------------------------

    /**
     * The published schedules, with their doses.
     *
     * <p>Readable by anybody signed in, like the catalogue above: a national immunisation schedule
     * is a public health document with no patient anywhere in it, and a recording screen that could
     * not read it could not tell a nurse what the child in front of them is due.
     */
    @GetMapping("/immunisations/schedules")
    @PreAuthorize("isAuthenticated()")
    public List<ImmunisationDtos.ScheduleResponse> schedules() {
        return dueList.published();
    }

    /**
     * What a birth cohort is due.
     *
     * <p>Asked for a birth range rather than for a patient, and there is no unbounded form of this
     * query on purpose — see {@code DueListService} for why an "every overdue child" endpoint would
     * have to ship every patient identifier on the platform to answer.
     *
     * <p>{@code IMMUNISATION_READ} says which jobs may look at immunisations, and — the one
     * deliberate exception that constant's own javadoc names — the care-relationship narrowing does
     * <em>not</em> apply per row here: a cohort narrowed to the caller's own patients is not a
     * cohort. What gates it instead is patient-service's own {@code PATIENT_COHORT_READ}, enforced
     * there against this caller's forwarded token, so a pharmacist holding {@code IMMUNISATION_READ}
     * is refused a list of children and their birthdays.
     */
    @GetMapping("/immunisations/due")
    @PreAuthorize(Roles.IMMUNISATION_READ)
    public ImmunisationDtos.DueListResponse due(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bornFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bornTo,
            @RequestParam(required = false) String scheduleCode,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt) {
        return dueList.due(bornFrom, bornTo, scheduleCode, limit, asAt);
    }

    // ---- quality measures ----------------------------------------------------

    /**
     * What is measured, and by whose specification.
     *
     * <p>Readable by anybody signed in, like the catalogue and the schedule: a measure definition
     * is a published specification with no patient in it. The <em>rates</em> are gated below.
     */
    @GetMapping("/measures")
    @PreAuthorize("isAuthenticated()")
    public List<ImmunisationDtos.MeasureResponse> measures() {
        return this.measures.published();
    }

    /**
     * One period's rate.
     *
     * <p>An aggregate, and it is built so there is nothing to narrow: no identifier is selected
     * into the response, so the care-relationship check has no row to check. That is what lets
     * {@code EPIDEMIOLOGIST} hold this without holding anything per-patient — see
     * {@link Roles#EPIDEMIOLOGIST} for why that is a safety property rather than a coincidence.
     *
     * <p>The period bounds birthdays rather than doses: a child is in the initial population when
     * their Nth birthday falls inside it.
     */
    @GetMapping("/measures/{code}/rate")
    @PreAuthorize(Roles.QUALITY_MEASURE_READ)
    public ImmunisationDtos.MeasureRateResponse rate(
            @PathVariable String code,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodTo,
            @RequestParam(required = false) String scheduleCode) {
        return measures.rate(code, periodFrom, periodTo, scheduleCode);
    }

    // ---- stock ---------------------------------------------------------------

    @GetMapping("/vaccines/lots")
    @PreAuthorize(Roles.VACCINE_STOCK)
    public List<ImmunisationDtos.LotResponse> lots(@RequestParam String productCode) {
        return stock.forProduct(productCode);
    }

    @PostMapping("/vaccines/lots")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.VACCINE_STOCK)
    public ImmunisationDtos.LotResponse receive(
            @Valid @RequestBody ImmunisationDtos.ReceiveLotRequest request) {
        return stock.receive(request);
    }

    @PostMapping("/vaccines/lots/{id}/withdraw")
    @PreAuthorize(Roles.VACCINE_STOCK)
    public ImmunisationDtos.LotResponse withdraw(
            @PathVariable UUID id, @Valid @RequestBody ImmunisationDtos.WithdrawLotRequest request) {
        return stock.withdraw(id, request.reason());
    }

    // ---- doses ---------------------------------------------------------------

    @PostMapping("/immunisations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.IMMUNISE)
    public ImmunisationDtos.DoseResponse record(
            @Valid @RequestBody ImmunisationDtos.RecordDoseRequest request) {
        return register.recordGivenHere(request);
    }

    /**
     * A dose given somewhere else.
     *
     * <p>Its own path rather than a flag on the endpoint above. The two demand different fields —
     * one a lot number, the other a sentence saying what was seen — and a flag on one endpoint is a
     * flag somebody forgets, which is how a remembered dose ends up in the register with an
     * invented lot number.
     */
    @PostMapping("/immunisations/historical")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.IMMUNISE)
    public ImmunisationDtos.DoseResponse recordHistorical(
            @Valid @RequestBody RecordHistoricalRequest request) {
        return register.recordHistorical(request.patientId(), request.patientMrn(),
                request.productCode(), request.givenOn(), request.dateEstimated(),
                request.source(), request.evidence());
    }

    /**
     * A dose from a card, a letter, or a parent's memory.
     *
     * <p>{@code evidence} is required with a floor: "parent reported" with nothing after it is a
     * claim with no provenance, and the next clinician cannot tell it from a record. {@code source}
     * is validated by the service rather than here, because the one value it must refuse —
     * {@code ADMINISTERED_HERE} — is a rule about which endpoint this is rather than about the
     * shape of the body.
     */
    public record RecordHistoricalRequest(
            @NotNull UUID patientId,
            @NotBlank @Size(max = 24) String patientMrn,
            @NotBlank @Size(max = 32) String productCode,
            @NotNull LocalDate givenOn,
            boolean dateEstimated,
            @NotNull ImmunisationSource source,
            @NotBlank @Size(min = 8, max = 500) String evidence) {
    }

    @PostMapping("/immunisations/{id}/adverse-events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.IMMUNISE)
    public ImmunisationDtos.AdverseEventResponse reportAdverseEvent(
            @PathVariable UUID id, @Valid @RequestBody ImmunisationDtos.ReportAefiRequest request) {
        return register.reportAdverseEvent(id, request);
    }

    @PostMapping("/immunisations/exemptions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.IMMUNISE)
    public ImmunisationDtos.ExemptionResponse recordExemption(
            @Valid @RequestBody ImmunisationDtos.RecordExemptionRequest request) {
        return register.recordExemption(request);
    }

    /**
     * One patient's register.
     *
     * <p>{@code IMMUNISATION_READ} says which jobs may look at immunisations at all; the
     * care-relationship narrowing inside the service says whose patients — so a doctor reading this
     * for somebody they are not looking after is refused in the platform's own words, with the
     * sentence telling them how to open it.
     */
    @GetMapping("/immunisations/patients/{patientId}")
    @PreAuthorize(Roles.IMMUNISATION_READ)
    public ImmunisationDtos.RegisterResponse forPatient(@PathVariable UUID patientId) {
        return register.forPatient(patientId);
    }
}
