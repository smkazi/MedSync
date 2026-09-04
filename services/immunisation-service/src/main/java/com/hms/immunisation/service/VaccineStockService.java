package com.hms.immunisation.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.immunisation.domain.VaccineLot;
import com.hms.immunisation.domain.VaccineProduct;
import com.hms.immunisation.repo.VaccineLotRepository;
import com.hms.immunisation.web.dto.ImmunisationDtos;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vaccine stock, by lot.
 *
 * <p>Two rules the pharmacy taught, one department along. **An expired lot cannot be drawn from**,
 * and that is a refusal rather than a warning: an expired vaccine is not a weaker vaccine, it is a
 * dose that did not happen and a child who will not be called back. And **stock is not seeded** —
 * the catalogue is, in the migration, and the lots are not, because fake inventory in a real clinic
 * is worse than an empty shelf and a lot number nobody received is a lot number a recall would
 * chase.
 */
@Service
public class VaccineStockService {

    private final VaccineLotRepository lots;
    private final CatalogueService catalogue;
    private final ImmunisationClock clock;
    private final AuditService audit;

    public VaccineStockService(VaccineLotRepository lots, CatalogueService catalogue,
                               ImmunisationClock clock, AuditService audit) {
        this.lots = lots;
        this.catalogue = catalogue;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ImmunisationDtos.LotResponse> forProduct(String productCode) {
        VaccineProduct product = catalogue.requireProduct(productCode);
        LocalDate today = clock.today();
        return lots.findByProductCodeOrderByExpiresOnAsc(productCode).stream()
                .map(lot -> toResponse(lot, product.getName(), today))
                .toList();
    }

    /**
     * The lot a dose came out of, named by the label on the vial.
     *
     * <p>Refuses an expired or withdrawn lot here, where the message can say which it was, rather
     * than letting a foreign key succeed and a child be recorded as vaccinated with something that
     * would not have worked.
     */
    @Transactional(readOnly = true)
    public VaccineLot requireUsable(String productCode, String lotNo) {
        VaccineLot lot = lots.findByProductCodeAndLotNo(productCode, lotNo).orElseThrow(
                () -> new NotFoundException(
                        "No lot '%s' of %s has been received here".formatted(lotNo, productCode)));
        LocalDate today = clock.today();
        if (lot.getWithdrawnReason() != null) {
            throw new ConflictException(("Lot %s was withdrawn: %s. It cannot be given.")
                    .formatted(lotNo, lot.getWithdrawnReason()));
        }
        if (lot.hasExpired(today)) {
            throw new ConflictException(("Lot %s expired on %s. An expired vaccine is not a weaker "
                    + "one — it is a dose that did not happen and a child nobody calls back.")
                    .formatted(lotNo, lot.getExpiresOn()));
        }
        if (lot.getQuantityOnHand() <= 0) {
            throw new ConflictException("Lot %s has no doses left".formatted(lotNo));
        }
        return lot;
    }

    @Transactional
    public ImmunisationDtos.LotResponse receive(ImmunisationDtos.ReceiveLotRequest request) {
        VaccineProduct product = catalogue.requireProduct(request.productCode());
        lots.findByProductCodeAndLotNo(request.productCode(), request.lotNo())
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "Lot %s of %s has already been received".formatted(
                                    request.lotNo(), request.productCode()));
                });
        if (request.expiresOn().isBefore(clock.today())) {
            throw new ConflictException(("Lot %s expired on %s. Receiving expired stock into the "
                    + "fridge is how it gets given.").formatted(request.lotNo(), request.expiresOn()));
        }
        VaccineLot lot = lots.save(new VaccineLot(request.productCode(), request.lotNo(),
                request.expiresOn(), request.quantity(), clock.today(), request.vvmStage()));
        audit.record("VACCINE_LOT_RECEIVED", "VaccineLot", lot.getId(),
                "%s lot %s, %d doses, expires %s".formatted(
                        request.productCode(), request.lotNo(), request.quantity(),
                        request.expiresOn()));
        return toResponse(lot, product.getName(), clock.today());
    }

    /** Takes a lot out of use: expired, recalled, or a cold chain somebody found broken. */
    @Transactional
    public ImmunisationDtos.LotResponse withdraw(UUID lotId, String reason) {
        VaccineLot lot = lots.findById(lotId).orElseThrow(
                () -> new NotFoundException("No such lot"));
        lot.withdraw(reason);
        audit.record("VACCINE_LOT_WITHDRAWN", "VaccineLot", lot.getId(),
                "%s lot %s".formatted(lot.getProductCode(), lot.getLotNo()));
        return toResponse(lot, catalogue.requireProduct(lot.getProductCode()).getName(), clock.today());
    }

    /**
     * Who received a vial of this lot.
     *
     * <p>The query a bad week runs, and the whole reason a dose given here is required to carry a
     * lot number. A lot may have been received more than once under the same number by different
     * products, so this answers per lot id.
     */
    @Transactional(readOnly = true)
    public List<VaccineLot> byLotNumber(String lotNo) {
        return lots.findByLotNo(lotNo);
    }

    private static ImmunisationDtos.LotResponse toResponse(VaccineLot lot, String productName,
                                                           LocalDate today) {
        return new ImmunisationDtos.LotResponse(lot.getId(), lot.getProductCode(), productName,
                lot.getLotNo(), lot.getExpiresOn(), lot.getQuantityOnHand(), lot.getReceivedOn(),
                lot.getVvmStage(), lot.getWithdrawnReason(), lot.isUsable(today));
    }
}
