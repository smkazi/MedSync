package com.hms.pharmacy.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import com.hms.pharmacy.domain.Dispense;
import com.hms.pharmacy.domain.Formulary;
import com.hms.pharmacy.domain.PharmacyEnums.CheckOutcome;
import com.hms.pharmacy.domain.Prescription;
import com.hms.pharmacy.domain.PrescriptionItem;
import com.hms.pharmacy.domain.StockBatch;
import com.hms.pharmacy.repo.DispenseRepository;
import com.hms.pharmacy.repo.FormularyRepository;
import com.hms.pharmacy.repo.PrescriptionItemRepository;
import com.hms.pharmacy.repo.PrescriptionRepository;
import com.hms.pharmacy.repo.StockBatchRepository;
import com.hms.pharmacy.web.dto.PharmacyDtos;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock, and handing medicine over.
 *
 * <p>Three rules, in this order, and each one refuses rather than warns:
 *
 * <ol>
 *   <li><strong>The checks run again.</strong> A prescription cleared at prescribing time is not
 *       cleared now: an allergy may have been recorded in between, and the patient may have been
 *       started on something else. The pharmacist is the last person who can catch that, which is
 *       the whole reason the second check exists rather than trusting the first.</li>
 *   <li><strong>An expired batch cannot be dispensed.</strong> Enforced twice — the FEFO query
 *       excludes it, and the decrement re-checks it, because choosing a batch and writing the row
 *       can span midnight.</li>
 *   <li><strong>Never more than was prescribed.</strong> A database CHECK, not an if-statement:
 *       two pharmacists each handing over the outstanding quantity both pass a check.</li>
 * </ol>
 */
@Service
public class DispensingService {

    private final StockBatchRepository batches;
    private final DispenseRepository dispenses;
    private final PrescriptionItemRepository items;
    private final FormularyRepository formulary;
    private final PrescriptionRepository prescriptions;
    private final SafetyService safety;
    private final AuditService audit;
    private final EventPublisher events;

    public DispensingService(StockBatchRepository batches, DispenseRepository dispenses,
                             PrescriptionItemRepository items, FormularyRepository formulary,
                             PrescriptionRepository prescriptions, SafetyService safety,
                             AuditService audit, EventPublisher events) {
        this.batches = batches;
        this.dispenses = dispenses;
        this.items = items;
        this.formulary = formulary;
        this.prescriptions = prescriptions;
        this.safety = safety;
        this.audit = audit;
        this.events = events;
    }

    @Transactional
    public PharmacyDtos.StockBatchResponse receive(PharmacyDtos.ReceiveStockRequest request) {
        Formulary product = formulary.findByCode(request.drugCode())
                .orElseThrow(() -> new NotFoundException(
                        "Unknown drug code '" + request.drugCode() + "'"));
        LocalDate today = LocalDate.now();
        if (!request.expiresOn().isAfter(today)) {
            // Refused at the door rather than accepted and quarantined. Stock that cannot be
            // dispensed is not stock, and a batch on the shelf with an expiry in the past is how a
            // count comes to include boxes nobody may use.
            throw new BadRequestException(
                    "Batch %s of %s expires on %s, which is not in the future. Expired stock is not "
                            + "received into the pharmacy."
                            .formatted(request.batchNo(), product.getName(), request.expiresOn()));
        }
        StockBatch batch = new StockBatch(product.getCode(), request.batchNo().trim(),
                request.expiresOn(), request.quantity());
        StockBatch saved = batches.save(batch);
        audit.record("STOCK_RECEIVED", "StockBatch", saved.getId(),
                "%s batch %s, %d unit(s), expires %s".formatted(product.getCode(),
                        saved.getBatchNo(), saved.getQuantityOnHand(), saved.getExpiresOn()));
        return toResponse(saved, product.getName(), LocalDate.now());
    }

    @Transactional(readOnly = true)
    public List<PharmacyDtos.StockBatchResponse> stock(String drugCode) {
        LocalDate today = LocalDate.now();
        List<StockBatch> rows = drugCode == null || drugCode.isBlank()
                ? batches.findAll()
                : batches.findByDrugCodeOrderByExpiresOn(drugCode);
        Map<String, String> names = new LinkedHashMap<>();
        formulary.findAll().forEach(entry -> names.put(entry.getCode(), entry.getName()));
        return rows.stream()
                .sorted((left, right) -> left.getExpiresOn().compareTo(right.getExpiresOn()))
                .map(batch -> toResponse(batch, names.get(batch.getDrugCode()), today))
                .toList();
    }

    /**
     * Hands over part or all of one prescribed item.
     *
     * @param bearerToken the caller's own token, forwarded for the second safety check
     */
    @Transactional
    public PharmacyDtos.DispenseResponse dispense(PharmacyDtos.DispenseRequest request,
                                                  String bearerToken) {
        PrescriptionItem item = items.findById(request.prescriptionItemId())
                .orElseThrow(() -> new NotFoundException(
                        "No prescription item with id " + request.prescriptionItemId()));
        Prescription prescription = item.getPrescription();
        if (!prescription.isActive()) {
            throw new BadRequestException("This prescription is "
                    + prescription.getStatus().name().toLowerCase(java.util.Locale.ROOT)
                    + " and cannot be dispensed against.");
        }
        if (request.quantity() > item.outstanding()) {
            throw new BadRequestException(
                    "%d unit(s) requested but only %d of the prescribed %d remain outstanding."
                            .formatted(request.quantity(), item.outstanding(), item.getQuantity()));
        }

        // The second check. Run over everything still active for this patient rather than over this
        // item alone: the interaction that matters at the counter is with the other medicines the
        // patient is actually on, which may have been prescribed after this line was written.
        PharmacyDtos.SafetyCheckResponse check = safety.check(prescription.getPatientId(),
                List.of(item.getDrugCode()), bearerToken);
        if (check.outcome() == CheckOutcome.REFUSED) {
            throw new ConflictException("This cannot be dispensed: " + check.message()
                    + " The prescriber has to be told.");
        }

        // Read out what is needed for the event before the decrement, because the decrement
        // detaches everything — see below.
        UUID patientId = prescription.getPatientId();
        String patientMrn = prescription.getPatientMrn();
        UUID itemId = item.getId();
        String drugCode = item.getDrugCode();
        String drugName = item.getDrugName();

        StockBatch batch = chooseBatch(drugCode, request.batchId(), request.quantity());
        // The conditional UPDATE is the control. Zero rows means somebody else took the stock, or
        // midnight passed and the batch expired between the choice and the write.
        int taken = batches.take(batch.getId(), request.quantity(), LocalDate.now());
        if (taken == 0) {
            throw new ConflictException(
                    ("Batch %s of %s no longer has %d unit(s) available — it has just been used or "
                            + "has expired. Refresh the stock list and pick again.")
                            .formatted(batch.getBatchNo(), drugName, request.quantity()));
        }

        // Re-read, and this is not defensive: `take` is a @Modifying query with
        // `clearAutomatically = true`, which clears the persistence context so that the stock row
        // this transaction holds cannot be a stale copy of the one the UPDATE just changed. The
        // side effect is that everything loaded before it — this item and its prescription — is
        // now detached, and mutating a detached entity writes nothing. That is exactly how this
        // method first failed: the quantity came back correct in the response (a merge on save)
        // while the prescription stayed ACTIVE after its last item was fully dispensed, because
        // the status was set on an instance nobody was tracking.
        PrescriptionItem attached = items.findById(itemId).orElseThrow();
        attached.recordDispensed(request.quantity());
        items.save(attached);
        Prescription owning = attached.getPrescription();
        owning.markCompletedIfFullyDispensed();
        prescriptions.save(owning);

        Dispense dispense = dispenses.save(new Dispense(itemId, batch.getId(),
                request.quantity(), CurrentUser.usernameOrSystem()));
        audit.record("MEDICATION_DISPENSED", "Dispense", dispense.getId(),
                "%s batch %s, %d unit(s), item %s".formatted(drugCode, batch.getBatchNo(),
                        request.quantity(), itemId));
        // Carries what a bed-day charge carries: enough for billing to price it, nothing clinical.
        events.publish(Topics.PHARMACY, DomainEvent.of("pharmacy.dispensed", "Dispense",
                dispense.getId(), CurrentUser.idOrSystem().toString(), CorrelationId.current(),
                Map.of("patientId", patientId.toString(),
                        "mrn", patientMrn,
                        "drugCode", drugCode,
                        "quantity", request.quantity())));

        return new PharmacyDtos.DispenseResponse(dispense.getId(), itemId, drugName,
                batch.getBatchNo(), batch.getExpiresOn(), request.quantity(),
                dispense.getDispensedBy(), dispense.getDispensedAt(), attached.outstanding());
    }

    /**
     * First expiry, first out — unless the caller names a batch.
     *
     * <p>FEFO by default because it is what a pharmacy should do and what a picker choosing by hand
     * at the end of a shift will not always do. Naming a batch is allowed for the case where the
     * shelf disagrees with the system, and the named batch is held to exactly the same rules: an
     * expired one is refused whether it was chosen by the picker or by this method.
     */
    private StockBatch chooseBatch(String drugCode, UUID requested, int quantity) {
        LocalDate today = LocalDate.now();
        if (requested != null) {
            StockBatch batch = batches.findById(requested)
                    .orElseThrow(() -> new NotFoundException("No stock batch with id " + requested));
            if (!batch.getDrugCode().equals(drugCode)) {
                throw new BadRequestException(
                        "Batch %s is %s, not the medicine on this prescription line."
                                .formatted(batch.getBatchNo(), batch.getDrugCode()));
            }
            if (batch.expiredOn(today)) {
                throw new ConflictException(
                        "Batch %s expired on %s and cannot be dispensed."
                                .formatted(batch.getBatchNo(), batch.getExpiresOn()));
            }
            if (batch.getQuantityOnHand() < quantity) {
                throw new ConflictException(
                        "Batch %s has %d unit(s), fewer than the %d requested."
                                .formatted(batch.getBatchNo(), batch.getQuantityOnHand(), quantity));
            }
            return batch;
        }

        List<StockBatch> usable = batches.usable(drugCode, today);
        return usable.stream()
                .filter(batch -> batch.getQuantityOnHand() >= quantity)
                .findFirst()
                .orElseThrow(() -> new ConflictException(usable.isEmpty()
                        ? "There is no unexpired stock of %s. Nothing has been dispensed."
                                .formatted(drugCode)
                        : ("No single unexpired batch of %s holds %d unit(s). Dispense a smaller "
                                + "quantity, or split it across batches one at a time so each "
                                + "hand-over records the batch it came from.")
                                .formatted(drugCode, quantity)));
    }

    private static PharmacyDtos.StockBatchResponse toResponse(StockBatch batch, String drugName,
                                                              LocalDate today) {
        return new PharmacyDtos.StockBatchResponse(batch.getId(), batch.getDrugCode(), drugName,
                batch.getBatchNo(), batch.getExpiresOn(), batch.getQuantityOnHand(),
                batch.getReceivedOn(), batch.expiredOn(today),
                ChronoUnit.DAYS.between(today, batch.getExpiresOn()));
    }
}
