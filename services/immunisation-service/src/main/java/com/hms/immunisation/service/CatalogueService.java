package com.hms.immunisation.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.immunisation.domain.Antigen;
import com.hms.immunisation.domain.VaccineProduct;
import com.hms.immunisation.repo.AntigenRepository;
import com.hms.immunisation.repo.VaccineProductRepository;
import com.hms.immunisation.web.dto.ImmunisationDtos;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The vaccine catalogue: what exists, and what each product contains.
 *
 * <p>Configuration rather than code — a department that starts giving a new vaccine adds rows, and
 * nothing here has to change. What is <em>not</em> configuration is the contents of a product once
 * it exists: see {@link VaccineProduct} for why that list has no setter.
 */
@Service
public class CatalogueService {

    private final AntigenRepository antigens;
    private final VaccineProductRepository products;
    private final AuditService audit;

    public CatalogueService(AntigenRepository antigens, VaccineProductRepository products,
                            AuditService audit) {
        this.antigens = antigens;
        this.products = products;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ImmunisationDtos.AntigenResponse> antigens() {
        return antigens.findByActiveTrueOrderByCodeAsc().stream()
                .map(a -> new ImmunisationDtos.AntigenResponse(
                        a.getCode(), a.getName(), a.getProtectsAgainst(), a.isActive()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ImmunisationDtos.ProductResponse> products() {
        return products.findByActiveTrueOrderByNameAsc().stream().map(CatalogueService::toResponse).toList();
    }

    /**
     * Every product's contents, keyed by product code, in one read.
     *
     * <p>What the due list expands a register into. A schedule is written in antigens and the
     * register records products, so somewhere the two have to meet; doing it here, once per due
     * list, is the alternative to {@code requireProduct} per recorded dose — which for a cohort of
     * a few thousand children is a few thousand round trips to answer one screen.
     *
     * <p>Retired products are included, deliberately. A product withdrawn last year still has doses
     * recorded against it, and leaving it out would stop those doses counting toward anything —
     * which would read as a district losing its coverage the day a brand changed.
     */
    @Transactional(readOnly = true)
    public Map<String, Set<String>> antigensByProduct() {
        Map<String, Set<String>> byProduct = new HashMap<>();
        for (VaccineProduct product : products.findAll()) {
            byProduct.put(product.getCode(), Set.copyOf(product.getAntigenCodes()));
        }
        return byProduct;
    }

    @Transactional(readOnly = true)
    public VaccineProduct requireProduct(String code) {
        return products.findByCode(code).orElseThrow(
                () -> new NotFoundException("No vaccine product '%s' in the catalogue".formatted(code)));
    }

    @Transactional
    public ImmunisationDtos.AntigenResponse addAntigen(ImmunisationDtos.CreateAntigenRequest request) {
        antigens.findByCode(request.code()).ifPresent(existing -> {
            throw new ConflictException("Antigen '%s' already exists".formatted(request.code()));
        });
        Antigen saved = antigens.save(
                new Antigen(request.code(), request.name(), request.protectsAgainst()));
        audit.record("ANTIGEN_ADDED", "Antigen", saved.getId(), saved.getCode());
        return new ImmunisationDtos.AntigenResponse(
                saved.getCode(), saved.getName(), saved.getProtectsAgainst(), saved.isActive());
    }

    /**
     * Adds a product and the antigens it contains.
     *
     * <p>Every named antigen must already exist. A product listing an antigen nothing else knows
     * about would be a product invisible to every coverage question — the dose would be recorded
     * and would count toward nothing, silently, which is the worst way for a catalogue to be wrong.
     */
    @Transactional
    public ImmunisationDtos.ProductResponse addProduct(ImmunisationDtos.CreateProductRequest request) {
        products.findByCode(request.code()).ifPresent(existing -> {
            throw new ConflictException("Vaccine product '%s' already exists".formatted(request.code()));
        });
        for (String antigenCode : request.antigenCodes()) {
            antigens.findByCode(antigenCode).orElseThrow(() -> new ConflictException(
                    ("No antigen '%s'. A product may only contain antigens the catalogue knows, "
                            + "or the doses given from it would count toward nothing.")
                            .formatted(antigenCode)));
        }
        VaccineProduct saved = products.save(new VaccineProduct(request.code(), request.name(),
                request.manufacturer(), request.route(), request.dosesPerVial(),
                Set.copyOf(request.antigenCodes())));
        audit.record("VACCINE_PRODUCT_ADDED", "VaccineProduct", saved.getId(),
                "%s contains %s".formatted(saved.getCode(), String.join(",", saved.getAntigenCodes())));
        return toResponse(saved);
    }

    /** Retires a product, or brings one back. Never a delete: doses reference it forever. */
    @Transactional
    public ImmunisationDtos.ProductResponse setProductActive(String code, boolean active) {
        VaccineProduct product = requireProduct(code);
        product.setActive(active);
        audit.record("VACCINE_PRODUCT_UPDATED", "VaccineProduct", product.getId(),
                "%s, active=%s".formatted(code, active));
        return toResponse(product);
    }

    static ImmunisationDtos.ProductResponse toResponse(VaccineProduct product) {
        return new ImmunisationDtos.ProductResponse(product.getCode(), product.getName(),
                product.getManufacturer(), product.getRoute(), product.getDosesPerVial(),
                product.isActive(), product.getAntigenCodes().stream().sorted().toList());
    }
}
