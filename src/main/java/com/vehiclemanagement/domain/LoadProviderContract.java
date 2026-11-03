package com.vehiclemanagement.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The terms agreed with a load provider (the company that gives us cargo) at one location.
 *
 * <p><b>Every figure is as entered.</b> Nothing is derived from anything else: the final price
 * for the truck is whatever was agreed, not a formula this class applies, because the formula
 * differs between providers and the signed paper is the authority.
 *
 * <p>The company is a customer from the master list ({@code consignors}), chosen rather than
 * typed, so recording terms can never create one. {@code companyName} is a snapshot of its name.
 */
@Entity
@Table(name = "load_provider_contracts")
public class LoadProviderContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The customer from the master list. Null only on contracts recorded before the link. */
    @Column(name = "consignor_id")
    private Long consignorId;

    /** The customer's name when the contract was saved, so the list reads without a join. */
    @Column(name = "company_name", nullable = false, length = 160)
    private String companyName;

    @Column(name = "city_id")
    private Long cityId;

    @Column(length = 160)
    private String location;

    @Column(name = "price_approved", precision = 12, scale = 2)
    private BigDecimal priceApproved;

    @Column(name = "loading_charges", precision = 12, scale = 2)
    private BigDecimal loadingCharges;

    @Column(name = "unloading_charges", precision = 12, scale = 2)
    private BigDecimal unloadingCharges;

    @Column(name = "commission_percentage", precision = 5, scale = 2)
    private BigDecimal commissionPercentage;

    @Column(precision = 12, scale = 2)
    private BigDecimal munshiana;

    @Column(precision = 12, scale = 2)
    private BigDecimal dala;

    @Column(name = "final_price_for_truck", precision = 12, scale = 2)
    private BigDecimal finalPriceForTruck;

    @Column(name = "per_tonne_price", precision = 12, scale = 2)
    private BigDecimal perTonnePrice;

    @Column(length = 2000)
    private String notes;

    /** Retired, not deleted: superseded terms are the record of what was agreed before. */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "recorded_by", length = 128)
    private String recordedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    public LoadProviderContract() {
    }

    public Long getId() { return id; }
    public Long getConsignorId() { return consignorId; }
    public void setConsignorId(Long v) { this.consignorId = v; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String v) { this.companyName = v; }
    public Long getCityId() { return cityId; }
    public void setCityId(Long v) { this.cityId = v; }
    public String getLocation() { return location; }
    public void setLocation(String v) { this.location = v; }
    public BigDecimal getPriceApproved() { return priceApproved; }
    public void setPriceApproved(BigDecimal v) { this.priceApproved = v; }
    public BigDecimal getLoadingCharges() { return loadingCharges; }
    public void setLoadingCharges(BigDecimal v) { this.loadingCharges = v; }
    public BigDecimal getUnloadingCharges() { return unloadingCharges; }
    public void setUnloadingCharges(BigDecimal v) { this.unloadingCharges = v; }
    public BigDecimal getCommissionPercentage() { return commissionPercentage; }
    public void setCommissionPercentage(BigDecimal v) { this.commissionPercentage = v; }
    public BigDecimal getMunshiana() { return munshiana; }
    public void setMunshiana(BigDecimal v) { this.munshiana = v; }
    public BigDecimal getDala() { return dala; }
    public void setDala(BigDecimal v) { this.dala = v; }
    public BigDecimal getFinalPriceForTruck() { return finalPriceForTruck; }
    public void setFinalPriceForTruck(BigDecimal v) { this.finalPriceForTruck = v; }
    public BigDecimal getPerTonnePrice() { return perTonnePrice; }
    public void setPerTonnePrice(BigDecimal v) { this.perTonnePrice = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String v) { this.recordedBy = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
