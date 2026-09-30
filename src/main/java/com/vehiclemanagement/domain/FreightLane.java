package com.vehiclemanagement.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * A route and a commodity somebody told us about: "Meerut to Lucknow, mangoes".
 *
 * <p><b>Hearsay, deliberately.</b> There is no consignor, no truck, no money owed and no
 * obligation. It is the sentence a driver says in passing that would otherwise die with the
 * call, kept because the question it answers — which lanes should we be quoting for that we
 * currently are not — cannot be answered from {@link LorryReceipt}, which only records
 * business already won.
 *
 * <p>Same link-plus-snapshot discipline as a receipt: the city and goods-type links make the
 * grouping work, and the text survives a place that is not a city at all.
 */
@Entity
@Table(name = "freight_lanes")
public class FreightLane {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(nullable = false, length = 160)
    private String goods;

    /**
     * Whose cargo, as told to us. <b>Not</b> a link to {@code consignors}: recording a rumour
     * must not create a customer a receipt could then be issued against.
     */
    @Column(name = "company_name", length = 160)
    private String companyName;

    /** True when the lane only runs part of the year; {@link #season} says which part. */
    @Column(name = "is_seasonal", nullable = false)
    private boolean seasonal;

    /** "Apr-Jul", "monsoon", "after Diwali". Free text because that is how people say it. */
    @Column(length = 80)
    private String season;

    @Column(name = "body_type_id")
    private Long bodyTypeId;

    @Column(length = 160)
    private String source;

    @Column(name = "source_mobile", length = 10)
    private String sourceMobile;

    @Column(length = 2000)
    private String notes;

    /** Retired, not deleted: a lane that did not work out is worth knowing next time. */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "recorded_by", length = 128)
    private String recordedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected FreightLane() {
    }

    public FreightLane(String fromPlace, String toPlace, String goods) {
        this.fromPlace = fromPlace;
        this.toPlace = toPlace;
        this.goods = goods;
    }

    public Long getId() { return id; }
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
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String v) { this.companyName = v; }
    public boolean isSeasonal() { return seasonal; }
    public void setSeasonal(boolean v) { this.seasonal = v; }
    public String getSeason() { return season; }
    public void setSeason(String v) { this.season = v; }
    public Long getBodyTypeId() { return bodyTypeId; }
    public void setBodyTypeId(Long v) { this.bodyTypeId = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getSourceMobile() { return sourceMobile; }
    public void setSourceMobile(String v) { this.sourceMobile = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String v) { this.recordedBy = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
