package com.hms.immunisation.service;

import com.hms.common.audit.AuditService;
import com.hms.common.careteam.CareRelationshipClient;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import com.hms.immunisation.domain.AdverseEvent;
import com.hms.immunisation.domain.Immunisation;
import com.hms.immunisation.domain.ImmunisationEnums.ImmunisationSource;
import com.hms.immunisation.domain.ImmunisationExemption;
import com.hms.immunisation.domain.VaccineLot;
import com.hms.immunisation.domain.VaccineProduct;
import com.hms.immunisation.repo.AdverseEventRepository;
import com.hms.immunisation.repo.ExemptionRepository;
import com.hms.immunisation.repo.ImmunisationRepository;
import com.hms.immunisation.repo.VaccineLotRepository;
import com.hms.immunisation.web.dto.ImmunisationDtos;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The register: recording a dose, and reading a patient's history of them.
 *
 * <p>The one rule worth stating at the top is the one the database enforces rather than this class:
 * a dose given here carries the lot it came out of, and a dose recorded from a card carries a
 * sentence saying what was seen instead. {@code chk_lot_iff_given_here} makes the wrong combination
 * of the two unrepresentable, so a caller cannot record a remembered dose as though this hospital
 * had given it — which would put an invented lot number into the one column a recall reads.
 */
@Service
public class ImmunisationService {

    private final ImmunisationRepository register;
    private final VaccineLotRepository lots;
    private final AdverseEventRepository adverseEvents;
    private final ExemptionRepository exemptions;
    private final CatalogueService catalogue;
    private final VaccineStockService stock;
    private final ImmunisationClock clock;
    private final CareRelationshipClient careTeam;
    private final AuditService audit;
    private final EventPublisher events;

    public ImmunisationService(ImmunisationRepository register, VaccineLotRepository lots,
                               AdverseEventRepository adverseEvents, ExemptionRepository exemptions,
                               CatalogueService catalogue, VaccineStockService stock,
                               ImmunisationClock clock, CareRelationshipClient careTeam,
                               AuditService audit, EventPublisher events) {
        this.register = register;
        this.lots = lots;
        this.adverseEvents = adverseEvents;
        this.exemptions = exemptions;
        this.catalogue = catalogue;
        this.stock = stock;
        this.clock = clock;
        this.careTeam = careTeam;
        this.audit = audit;
        this.events = events;
    }

    // ---- recording -----------------------------------------------------------

    /** A dose given here, out of a named lot. */
    @Transactional
    public ImmunisationDtos.DoseResponse recordGivenHere(ImmunisationDtos.RecordDoseRequest request) {
        careTeam.requirePatientAccess(request.patientId());
        VaccineProduct product = catalogue.requireProduct(request.productCode());
        if (!product.isActive()) {
            throw new ConflictException(("Vaccine product '%s' has been retired and is no longer "
                    + "given here.").formatted(product.getCode()));
        }
        if (request.givenOn().isAfter(clock.today())) {
            throw new ConflictException(("A dose cannot have been given on %s — that is in the "
                    + "future. Recording one there would put a dose in the register that has not "
                    + "happened yet.").formatted(request.givenOn()));
        }
        VaccineLot lot = stock.requireUsable(request.productCode(), request.lotNo());

        Immunisation dose = Immunisation.givenHere(request.patientId(), request.patientMrn(),
                request.encounterId(), product, lot, request.givenOn(), request.site(),
                CurrentUser.usernameOrSystem(), CurrentUser.usernameOrSystem());
        Immunisation saved = save(dose, product);
        lot.draw();
        return toResponse(saved, product, lot.getLotNo(), List.of());
    }

    /**
     * A dose given somewhere else.
     *
     * <p>Its own method and its own endpoint rather than a flag on the one above, because it is a
     * different act with a different set of required fields — and a flag on one endpoint is a flag
     * somebody forgets.
     */
    @Transactional
    public ImmunisationDtos.DoseResponse recordHistorical(
            UUID patientId, String patientMrn, String productCode, LocalDate givenOn,
            boolean dateEstimated, ImmunisationSource source, String evidence) {
        careTeam.requirePatientAccess(patientId);
        VaccineProduct product = catalogue.requireProduct(productCode);
        if (givenOn.isAfter(clock.today())) {
            throw new ConflictException(
                    "A dose cannot have been given on %s — that is in the future.".formatted(givenOn));
        }
        Immunisation dose = Immunisation.historical(patientId, patientMrn, product, givenOn,
                dateEstimated, source, evidence, CurrentUser.usernameOrSystem());
        Immunisation saved = save(dose, product);
        return toResponse(saved, product, null, List.of());
    }

    /**
     * Writes the dose, letting the database be the arbiter of "already recorded".
     *
     * <p>{@code saveAndFlush} inside a catch, the pharmacy's shape, and the reason is the same: a
     * check-then-insert lets two callers both pass the check. Two clinics entering the same card,
     * or one nurse clicking twice, is exactly what {@code uq_dose_per_day} exists for, and only one
     * of them can win an insert.
     */
    private Immunisation save(Immunisation dose, VaccineProduct product) {
        Immunisation saved;
        try {
            saved = register.saveAndFlush(dose);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(("A dose of %s is already recorded for this patient on %s. "
                    + "If that is a different dose, it needs a different date; if it is the same "
                    + "one, it is already in the register.")
                    .formatted(dose.getProductCode(), dose.getGivenOn()));
        }
        audit.record("IMMUNISATION_RECORDED", "Immunisation", saved.getId(),
                "%s on %s, %s".formatted(saved.getProductCode(), saved.getGivenOn(),
                        saved.getSource()));

        // The product code AND the antigens it contains, rather than a count. A count cannot be
        // priced -- the correction the laboratory's release event needed once billing existed --
        // and the antigens are what any downstream register or coverage report keys on, which only
        // this service can expand a product into.
        Map<String, Object> payload = new HashMap<>();
        payload.put("patientId", saved.getPatientId().toString());
        payload.put("mrn", saved.getPatientMrn());
        payload.put("productCode", saved.getProductCode());
        payload.put("antigenCodes", List.copyOf(product.getAntigenCodes()));
        payload.put("givenOn", saved.getGivenOn().toString());
        payload.put("source", saved.getSource().name());
        events.publish(Topics.IMMUNISATION, DomainEvent.of("immunisation.recorded", "Immunisation",
                saved.getId(), CurrentUser.idOrSystem().toString(), CorrelationId.current(),
                payload));
        return saved;
    }

    // ---- adverse events ------------------------------------------------------

    /**
     * Reports an adverse event following a dose.
     *
     * <p>The onset check is here and not a CHECK constraint, and the migration says why: PostgreSQL
     * cannot compare against another table's row inside a CHECK, so it would need a trigger. An
     * event before the dose it followed is not an adverse event following immunisation.
     */
    @Transactional
    public ImmunisationDtos.AdverseEventResponse reportAdverseEvent(
            UUID immunisationId, ImmunisationDtos.ReportAefiRequest request) {
        Immunisation dose = register.findById(immunisationId).orElseThrow(
                () -> new NotFoundException("No such dose in the register"));
        careTeam.requirePatientAccess(dose.getPatientId());
        if (request.onsetOn().isBefore(dose.getGivenOn())) {
            throw new ConflictException(("The dose was given on %s and this event began on %s. An "
                    + "event before the dose is not an event following it.")
                    .formatted(dose.getGivenOn(), request.onsetOn()));
        }
        AdverseEvent event = adverseEvents.save(new AdverseEvent(immunisationId, request.onsetOn(),
                request.description(), request.seriousness(), request.outcome(),
                CurrentUser.usernameOrSystem()));

        // The action names the seriousness so the audit report can be filtered by it, and carries
        // no clinical words at all -- the description is what happened to a patient, and audit
        // detail must never carry clinical free text.
        audit.record("AEFI_REPORTED", "AdverseEvent", event.getId(),
                "%s, %s".formatted(request.seriousness(), request.outcome()));
        return toResponse(event);
    }

    // ---- exemptions ----------------------------------------------------------

    @Transactional
    public ImmunisationDtos.ExemptionResponse recordExemption(
            ImmunisationDtos.RecordExemptionRequest request) {
        careTeam.requirePatientAccess(request.patientId());
        ImmunisationExemption exemption = exemptions.save(new ImmunisationExemption(
                request.patientId(), request.patientMrn(), request.antigenCode(), request.kind(),
                request.reason(), request.expiresOn(), CurrentUser.usernameOrSystem()));
        // The kind and the antigen, and not the reason. The reason is a clinical sentence about a
        // patient and it lives on the row, in this service's clinical schema -- the same split
        // break-glass makes.
        audit.record("IMMUNISATION_EXEMPTION_RECORDED", "ImmunisationExemption", exemption.getId(),
                "%s for %s".formatted(request.kind(),
                        request.antigenCode() == null ? "every antigen" : request.antigenCode()));
        return toResponse(exemption, clock.today());
    }

    // ---- reading -------------------------------------------------------------

    /** One patient's whole register: every dose, and every reason a dose will not be given. */
    @Transactional(readOnly = true)
    public ImmunisationDtos.RegisterResponse forPatient(UUID patientId) {
        careTeam.requirePatientAccess(patientId);
        List<Immunisation> doses = register.findByPatientIdOrderByGivenOnAsc(patientId);
        List<ImmunisationExemption> recorded = exemptions.findByPatientIdOrderByRecordedAtAsc(patientId);
        LocalDate today = clock.today();

        // The adverse events for every dose in one query rather than one per dose. Small lists
        // here, and the shape matters: a register screen for a fully vaccinated child is a dozen
        // doses, and a query each would be a dozen round trips for one page.
        Map<UUID, List<AdverseEvent>> eventsByDose = new HashMap<>();
        if (!doses.isEmpty()) {
            for (AdverseEvent event : adverseEvents.findByImmunisationIdInOrderByOnsetOnAsc(
                    doses.stream().map(Immunisation::getId).toList())) {
                eventsByDose.computeIfAbsent(event.getImmunisationId(), k -> new ArrayList<>())
                        .add(event);
            }
        }

        // The lot numbers for the doses that have one, in one query for the same reason.
        Map<UUID, String> lotNumbers = new HashMap<>();
        List<UUID> lotIds = doses.stream().map(Immunisation::getLotId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (!lotIds.isEmpty()) {
            for (VaccineLot lot : lots.findAllById(lotIds)) {
                lotNumbers.put(lot.getId(), lot.getLotNo());
            }
        }

        String mrn = doses.isEmpty()
                ? recorded.stream().findFirst().map(ImmunisationExemption::getPatientMrn).orElse(null)
                : doses.get(0).getPatientMrn();

        List<ImmunisationDtos.DoseResponse> dosed = doses.stream().map(dose -> toResponse(dose,
                        catalogue.requireProduct(dose.getProductCode()),
                        lotNumbers.get(dose.getLotId()),
                        eventsByDose.getOrDefault(dose.getId(), List.of())))
                .toList();

        return new ImmunisationDtos.RegisterResponse(patientId, mrn, dosed,
                recorded.stream().map(e -> toResponse(e, today)).toList());
    }

    /** Whether this patient's register may be read at all, without refusing. For a screen. */
    @Transactional(readOnly = true)
    public boolean mayRead(UUID patientId) {
        return careTeam.mayRead(patientId);
    }

    // ---- mapping -------------------------------------------------------------

    static ImmunisationDtos.DoseResponse toResponse(Immunisation dose, VaccineProduct product,
                                                    String lotNo, List<AdverseEvent> events) {
        return new ImmunisationDtos.DoseResponse(dose.getId(), dose.getPatientId(),
                dose.getPatientMrn(), dose.getEncounterId(), dose.getProductCode(),
                dose.getProductName(), product.getAntigenCodes().stream().sorted().toList(), lotNo,
                dose.getSource(), dose.getGivenOn(), dose.isGivenOnEstimated(), dose.getRoute(),
                dose.getSite(), dose.getGivenBy(), dose.getEvidence(), dose.getRecordedAt(),
                dose.getRecordedBy(), events.stream().map(ImmunisationService::toResponse).toList());
    }

    static ImmunisationDtos.AdverseEventResponse toResponse(AdverseEvent event) {
        return new ImmunisationDtos.AdverseEventResponse(event.getId(), event.getImmunisationId(),
                event.getOnsetOn(), event.getDescription(), event.getSeriousness(),
                event.getOutcome(), event.isReportable(), event.getReportedBy(),
                event.getReportedAt());
    }

    static ImmunisationDtos.ExemptionResponse toResponse(ImmunisationExemption exemption,
                                                         LocalDate today) {
        return new ImmunisationDtos.ExemptionResponse(exemption.getId(), exemption.getPatientId(),
                exemption.getAntigenCode(), exemption.getKind(), exemption.getReason(),
                exemption.getExpiresOn(), exemption.isLiveOn(today), exemption.getRecordedBy(),
                exemption.getRecordedAt());
    }

    /** The lot number a dose came from, when it has one. Used by the recall read. */
    @Transactional(readOnly = true)
    public Optional<String> lotNumberOf(Immunisation dose) {
        return Optional.ofNullable(dose.getLotId()).flatMap(lots::findById).map(VaccineLot::getLotNo);
    }
}
