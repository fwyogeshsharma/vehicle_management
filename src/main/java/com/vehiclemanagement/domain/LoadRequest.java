package com.vehiclemanagement.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A customer's ask for trucks: "Kumar Cement needs 3 trucks of 25 tons, Jaipur to Surat".
 *
 * <p>Not a lorry receipt and no obligation: it records the ask and whether it has been covered.
 * The customer is a row from the master ({@code consignors}), and {@code companyName} is a
 * snapshot of its name. Places link to the city list when they can and stay text when they
 * cannot, as on a receipt or a lane.
 */
@Entity
@Table(name = "load_requests")
public class LoadRequest {

    public enum Status { OPEN, FULFILLED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consignor_id", nullable = false)
    private Long consignorId;

    @Column(name = "company_name", nullable = false, length = 160)
    private String companyName;

    @Column(name = "request_date", nullable = false)
    private LocalDate requestDate;

    @Column(name = "from_city_id")
    private Long fromCityId;

    @Column(name = "from_place", nullable = false, length = 120)
    private String fromPlace;

    @Column(name = "to_city_id")
    private Long toCityId;

    @Column(name = "to_place", nullable = false, length = 120)
    private String toPlace;

    @Column(name = "goods_type_id")
    private Long goodsTypeId;

    @Column(length = 160)
    private String goods;

    /** For the whole request, not per truck. */
    @Column(name = "weight_tons", precision = 8, scale = 2)
    private BigDecimal weightTons;

    @Column(name = "trucks_required", nullable = false)
    private short trucksRequired = 1;

    @Column(name = "body_type_id")
    private Long bodyTypeId;

    /** When the cargo must be ready, not the day the ask arrived. */
    @Column(name = "loading_date")
    private LocalDate loadingDate;

    /** The TOTAL the customer offered for the run, as stated. Nothing is derived from it. */
    @Column(name = "offered_price", precision = 12, scale = 2)
    private BigDecimal offeredPrice;

    @Column(name = "contact_name", length = 120)
    private String contactName;

    @Column(name = "contact_mobile", length = 10)
    private String contactMobile;

    @Column(length = 2000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Column(name = "recorded_by", length = 128)
    private String recordedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    public LoadRequest() {
    }

    public Long getId() { return id; }
    public Long getConsignorId() { return consignorId; }
    public void setConsignorId(Long v) { this.consignorId = v; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String v) { this.companyName = v; }
    public LocalDate getRequestDate() { return requestDate; }
    public void setRequestDate(LocalDate v) { this.requestDate = v; }
    public Long getFromCityId() { return fromCityId; }
    public void setFromCityId(Long v) { this.fromCityId = v; }
    public String getFromPlace() { return fromPlace; }
    public void setFromPlace(String v) { this.fromPlace = v; }
    public Long getToCityId() { return toCityId; }
    public void setToCityId(Long v) { this.toCityId = v; }
    public String getToPlace() { return toPlace; }
    public void setToPlace(String v) { this.toPlace = v; }
    public Long getGoodsTypeId() { return goodsTypeId; }
    public void setGoodsTypeId(Long v) { this.goodsTypeId = v; }
    public String getGoods() { return goods; }
    public void setGoods(String v) { this.goods = v; }
    public BigDecimal getWeightTons() { return weightTons; }
    public void setWeightTons(BigDecimal v) { this.weightTons = v; }
    public short getTrucksRequired() { return trucksRequired; }
    public void setTrucksRequired(short v) { this.trucksRequired = v; }
    public Long getBodyTypeId() { return bodyTypeId; }
    public void setBodyTypeId(Long v) { this.bodyTypeId = v; }
    public LocalDate getLoadingDate() { return loadingDate; }
    public void setLoadingDate(LocalDate v) { this.loadingDate = v; }
    public BigDecimal getOfferedPrice() { return offeredPrice; }
    public void setOfferedPrice(BigDecimal v) { this.offeredPrice = v; }
    public String getContactName() { return contactName; }
    public void setContactName(String v) { this.contactName = v; }
    public String getContactMobile() { return contactMobile; }
    public void setContactMobile(String v) { this.contactMobile = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String v) { this.recordedBy = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
