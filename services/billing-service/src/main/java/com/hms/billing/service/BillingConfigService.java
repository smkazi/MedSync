package com.hms.billing.service;

import com.hms.billing.domain.ChargeItem;
import com.hms.billing.domain.Payer;
import com.hms.billing.domain.PayerTariff;
import com.hms.billing.domain.TaxRate;
import com.hms.billing.repo.ChargeItemRepository;
import com.hms.billing.repo.PayerRepository;
import com.hms.billing.repo.PayerTariffRepository;
import com.hms.billing.repo.TaxRateRepository;
import com.hms.billing.web.dto.BillingDtos;
import com.hms.common.audit.AuditService;
import com.hms.common.data.QueryPatterns;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The charge list, the tax rates and the payers: billing's configuration.
 *
 * <p>All rows, all editable without a deployment, because a price list changes and a tax rate
 * changes by statute. Two things are deliberately <em>not</em> editable in place:
 *
 * <ul>
 *   <li><strong>A tax rate is superseded, never amended.</strong> Changing a percentage would
 *       silently re-tax nothing (invoices copy the rate onto the line) but would leave the table
 *       lying about what was charged in March. A new rate closes the old period the day it starts.
 *   </li>
 *   <li><strong>A charge item's code is permanent</strong>, like every code on this platform: it is
 *       on invoice lines and in {@code posted_charges}, and neither would learn it had changed.</li>
 * </ul>
 */
@Service
public class BillingConfigService {

    private final BillingClock clock;
    private final ChargeItemRepository chargeItems;
    private final TaxRateRepository taxRates;
    private final PayerRepository payers;
    private final PayerTariffRepository tariffs;
    private final AuditService audit;

    public BillingConfigService(BillingClock clock, ChargeItemRepository chargeItems,
                                TaxRateRepository taxRates,
                                PayerRepository payers, PayerTariffRepository tariffs,
                                AuditService audit) {
        this.clock = clock;
        this.chargeItems = chargeItems;
        this.taxRates = taxRates;
        this.payers = payers;
        this.tariffs = tariffs;
        this.audit = audit;
    }

    // ---- tax -----------------------------------------------------------------

    /** The rate for a code on a date, if there is one in force. */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> percentOn(String code, LocalDate on) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return taxRates.effectiveOn(code, on).stream().findFirst().map(TaxRate::getPercent);
    }

    @Transactional(readOnly = true)
    public List<BillingDtos.TaxRateResponse> rates() {
        LocalDate today = clock.today();
        return taxRates.findAllByOrderByCodeAscEffectiveFromDesc().stream()
                .map(rate -> new BillingDtos.TaxRateResponse(rate.getId(), rate.getCode(),
                        rate.getName(), rate.getPercent(), rate.getEffectiveFrom(),
                        rate.getEffectiveTo(), rate.appliesOn(today)))
                .toList();
    }

    /**
     * Adds a rate, closing whatever it supersedes.
     *
     * <p>Refused for a date in the past: back-dating a rate change would mean invoices already
     * raised and paid were taxed at a rate the table now says did not apply, and no amount of
     * recalculation fixes a receipt somebody has already been given.
     */
    @Transactional
    public BillingDtos.TaxRateResponse addRate(BillingDtos.CreateTaxRateRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (request.effectiveFrom().isBefore(clock.today())) {
            throw new BadRequestException(
                    ("A rate cannot start in the past: invoices raised before today were taxed at "
                            + "the rate that applied then, and back-dating this one would make the "
                            + "table disagree with the receipts. Start it today or later."));
        }
        List<TaxRate> current = taxRates.effectiveOn(code, request.effectiveFrom());
        for (TaxRate rate : current) {
            rate.supersededFrom(request.effectiveFrom());
            taxRates.save(rate);
        }
        TaxRate saved = taxRates.save(new TaxRate(code, request.name().trim(), request.percent(),
                request.effectiveFrom(), null));
        audit.record("TAX_RATE_ADDED", "TaxRate", saved.getId(),
                "%s at %s%% from %s".formatted(code, request.percent(), request.effectiveFrom()));
        return new BillingDtos.TaxRateResponse(saved.getId(), saved.getCode(), saved.getName(),
                saved.getPercent(), saved.getEffectiveFrom(), saved.getEffectiveTo(),
                saved.appliesOn(clock.today()));
    }

    // ---- charge items --------------------------------------------------------

    @Transactional(readOnly = true)
    public List<BillingDtos.ChargeItemResponse> catalogue(String query, boolean includeInactive) {
        LocalDate today = clock.today();
        return chargeItems.search(QueryPatterns.contains(query), includeInactive).stream()
                .map(item -> toResponse(item, today))
                .toList();
    }

    /**
     * A charge item, if there is one with that code and it is chargeable.
     *
     * <p>Exists for charge capture, which asks a question {@link #require} cannot answer: an event
     * naming something the price list has never heard of is a configuration gap somebody has to
     * close, not a request that failed. The listener needs to say so and carry on rather than
     * throw at a Kafka consumer.
     *
     * <p>Inactive items answer empty. Retiring a charge item is how a deployment says "stop
     * charging for this", and a retired item that kept billing through the event path would make
     * that button a lie.
     */
    @Transactional(readOnly = true)
    public Optional<ChargeItem> chargeable(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return chargeItems.findByCode(code.trim().toUpperCase(Locale.ROOT))
                .filter(ChargeItem::isActive);
    }

    @Transactional(readOnly = true)
    public ChargeItem require(String code) {
        return chargeItems.findByCode(code.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("No charge item with code " + code));
    }

    @Transactional
    public BillingDtos.ChargeItemResponse addItem(BillingDtos.CreateChargeItemRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (chargeItems.findByCode(code).isPresent()) {
            throw new ConflictException("A charge item with code '" + code + "' already exists");
        }
        boolean taxable = Boolean.TRUE.equals(request.taxable());
        String rateCode = request.taxRateCode() == null || request.taxRateCode().isBlank()
                ? null : request.taxRateCode().trim().toUpperCase(Locale.ROOT);
        if (taxable && rateCode == null) {
            // Also a CHECK constraint. Refused here so the message names the field rather than the
            // constraint: a taxable item with no rate is taxed at nothing, which is the same as
            // exempt without anybody having decided it.
            throw new BadRequestException(
                    "A taxable item has to name a tax rate. Without one it is taxed at nothing, "
                            + "which is exemption by accident rather than by decision.");
        }
        if (rateCode != null && percentOn(rateCode, clock.today()).isEmpty()) {
            throw new BadRequestException(
                    "No tax rate '%s' is in force today. Add the rate first.".formatted(rateCode));
        }
        ChargeItem saved = chargeItems.save(new ChargeItem(code, request.name().trim(),
                blankToNull(request.departmentCode()), request.unitPrice(), taxable, rateCode));
        audit.record("CHARGE_ITEM_ADDED", "ChargeItem", saved.getId(),
                "%s at %s%s".formatted(code, request.unitPrice(),
                        taxable ? " plus " + rateCode : " (exempt)"));
        return toResponse(saved, clock.today());
    }

    @Transactional
    public BillingDtos.ChargeItemResponse updateItem(String code,
                                                     BillingDtos.UpdateChargeItemRequest request) {
        ChargeItem item = require(code);
        if (request.name() != null && !request.name().isBlank()) {
            item.setName(request.name().trim());
        }
        if (request.unitPrice() != null) {
            item.setUnitPrice(request.unitPrice());
        }
        if (request.active() != null) {
            item.setActive(request.active());
        }
        chargeItems.save(item);
        // A price change is audited with both numbers, because "why is this invoice different from
        // last month's" is a question somebody asks months later.
        audit.record("CHARGE_ITEM_UPDATED", "ChargeItem", item.getId(),
                "%s now %s, active=%s".formatted(code, item.getUnitPrice(), item.isActive()));
        return toResponse(item, clock.today());
    }

    // ---- payers --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<BillingDtos.PayerResponse> allPayers() {
        return payers.findAllByOrderByNameAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Payer requirePayer(String code) {
        return payers.findByCode(code.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("No payer with code " + code));
    }

    @Transactional
    public BillingDtos.PayerResponse addPayer(BillingDtos.CreatePayerRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (payers.findByCode(code).isPresent()) {
            throw new ConflictException("A payer with code '" + code + "' already exists");
        }
        boolean preauth = Boolean.TRUE.equals(request.requiresPreauth());
        boolean direct = Boolean.TRUE.equals(request.settlesDirectly());
        if (preauth && !direct) {
            // The CHECK constraint again, with a message that explains rather than names it.
            throw new BadRequestException(
                    "A payer that demands pre-authorisation but does not settle directly is a "
                            + "contradiction: pre-authorisation exists so the hospital can bill the "
                            + "payer rather than the patient.");
        }
        Payer saved = payers.save(new Payer(code, request.name().trim(), preauth,
                !Boolean.FALSE.equals(request.allowsCopay()), direct,
                Boolean.TRUE.equals(request.taxExempt())));
        audit.record("PAYER_ADDED", "Payer", saved.getId(), code);
        return toResponse(saved);
    }

    @Transactional
    public BillingDtos.PayerResponse setTariff(String payerCode,
                                               BillingDtos.SetTariffRequest request) {
        Payer payer = requirePayer(payerCode);
        ChargeItem item = require(request.chargeItemCode());
        PayerTariff.Key key = new PayerTariff.Key(payer.getCode(), item.getCode());
        PayerTariff tariff = tariffs.findById(key)
                .orElseGet(() -> new PayerTariff(payer.getCode(), item.getCode(), request.price()));
        tariff.setPrice(request.price());
        tariffs.save(tariff);
        audit.record("PAYER_TARIFF_SET", "Payer", payer.getId(),
                "%s %s at %s".formatted(payer.getCode(), item.getCode(), request.price()));
        return toResponse(payer);
    }

    /** The payer's agreed price for an item, if they have one. */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> tariffFor(String payerCode, String chargeItemCode) {
        if (payerCode == null || payerCode.isBlank()) {
            return Optional.empty();
        }
        return tariffs.findById(new PayerTariff.Key(payerCode, chargeItemCode))
                .map(PayerTariff::getPrice);
    }

    private BillingDtos.ChargeItemResponse toResponse(ChargeItem item, LocalDate on) {
        return new BillingDtos.ChargeItemResponse(item.getId(), item.getCode(), item.getName(),
                item.getDepartmentCode(), item.getUnitPrice(), item.isTaxable(),
                item.getTaxRateCode(),
                item.isTaxable() ? percentOn(item.getTaxRateCode(), on).orElse(BigDecimal.ZERO)
                        : BigDecimal.ZERO,
                item.isActive());
    }

    private BillingDtos.PayerResponse toResponse(Payer payer) {
        List<BillingDtos.TariffResponse> rows =
                tariffs.findByIdPayerCodeOrderByIdChargeItemCodeAsc(payer.getCode()).stream()
                        .map(tariff -> {
                            ChargeItem item = chargeItems.findByCode(tariff.getChargeItemCode())
                                    .orElse(null);
                            return new BillingDtos.TariffResponse(tariff.getChargeItemCode(),
                                    item == null ? tariff.getChargeItemCode() : item.getName(),
                                    item == null ? null : item.getUnitPrice(), tariff.getPrice());
                        })
                        .toList();
        return new BillingDtos.PayerResponse(payer.getId(), payer.getCode(), payer.getName(),
                payer.isRequiresPreauth(), payer.isAllowsCopay(), payer.isSettlesDirectly(),
                payer.isTaxExempt(), payer.isActive(), rows);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
