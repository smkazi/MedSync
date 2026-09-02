package com.hms.billing.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Something the hospital charges for.
 *
 * <p>The price here is the list price and is <em>not</em> what necessarily reaches an invoice: a
 * payer with a tariff pays the tariff. Whichever wins, the number is copied onto the line, because
 * an invoice is a record of what was charged and not a view over what is charged today.
 */
@Entity
@Table(name = "charge_items")
public class ChargeItem extends BaseEntity {

    @Column(name = "code", nullable = false, length = 32, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "department_code", length = 32)
    private String departmentCode;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "taxable", nullable = false)
    private boolean taxable;

    @Column(name = "tax_rate_code", length = 24)
    private String taxRateCode;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected ChargeItem() {
    }

    public ChargeItem(String code, String name, String departmentCode, BigDecimal unitPrice,
                      boolean taxable, String taxRateCode) {
        this.code = code;
        this.name = name;
        this.departmentCode = departmentCode;
        this.unitPrice = unitPrice;
        this.taxable = taxable;
        this.taxRateCode = taxRateCode;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public boolean isTaxable() {
        return taxable;
    }

    public String getTaxRateCode() {
        return taxRateCode;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
