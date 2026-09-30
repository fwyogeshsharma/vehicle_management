package com.vehiclemanagement.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A consignment note: what was carried, from where to where, for whom, by which truck.
 *
 * <p><b>Every master link here is paired with a text snapshot, and the snapshot is the
 * document.</b> {@code vehicleId} beside {@code vehicleNumber}, {@code driverUserId} beside
 * {@code driverName}. The link exists so the UI can offer "open this vehicle"; the text is what
 * was agreed on the day and what is printed. When a truck is re-registered or a driver leaves,
 * the link goes to null (ON DELETE SET NULL) and the LR still reads correctly. This is the one
 * idea worth taking from tts unchanged, and reversing it — deriving the printed values from the
 * masters at read time — would mean last year's receipts silently rewriting themselves.
 *
 * <p>Consequently <b>nothing here re-reads a master</b>. The setters take values, the service
 * decides where they came from, and this class never joins.
 *
 * <p>{@code totalCharges} and {@code balance} are generated columns, not Java arithmetic: the
 * list sorts and filters on "who owes us money", and a total computed in Java cannot appear in
 * an ORDER BY. Hibernate reads them and never writes them.
 */
@Entity
@Table(name = "lorry_receipts")
public class LorryReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lr_number", nullable = false, length = 30, unique = true)
    private String lrNumber;

    /**
     * Which numbering run this belongs to. One value today ({@code DEFAULT}); the column is
     * here so a financial-year reset is a new row in {@code lr_counters}, not a migration.
     */
    @Column(nullable = false, length = 16)
    private String series = "DEFAULT";

    @Column(name = "seq_no", nullable = false)
    private int seqNo;

    @Column(name = "lr_date", nullable = false)
    private LocalDate lrDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private LrStatus status = LrStatus.BOOKED;

    // ── the parties ─────────────────────────────────────────────────────────────────────

    @Column(name = "consignor_id")
    private Long consignorId;

    @Column(name = "consignor_name", nullable = false, length = 160)
    private String consignorName;

    @Column(name = "consignor_mobile", length = 10)
    private String consignorMobile;

    @Column(name = "consignee_id")
    private Long consigneeId;

    @Column(name = "consignee_name", nullable = false, length = 160)
    private String consigneeName;

    @Column(name = "consignee_mobile", length = 10)
    private String consigneeMobile;

    // ── where it goes ───────────────────────────────────────────────────────────────────

    @Column(name = "from_city_id")
    private Long fromCityId;

    @Column(name = "from_place", nullable = false, length = 120)
    private String fromPlace;

    @Column(name = "to_city_id")
    private Long toCityId;

    @Column(name = "to_place", nullable = false, length = 120)
    private String toPlace;

    // ── what it carries ─────────────────────────────────────────────────────────────────

    @Column(name = "goods_type_id")
    private Long goodsTypeId;

    @Column(name = "goods_description", length = 500)
    private String goodsDescription;

    @Column(name = "weight_kg", precision = 10, scale = 2)
    private BigDecimal weightKg;

    @Column
    private Integer packages;

    // ── which truck, and who drove it ───────────────────────────────────────────────────

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "vehicle_number", length = 20)
    private String vehicleNumber;

    @Column(name = "body_type_id")
    private Long bodyTypeId;

    @Column(name = "vehicle_type", length = 80)
    private String vehicleType;

    @Column(name = "driver_user_id")
    private Long driverUserId;

    @Column(name = "driver_name", length = 128)
    private String driverName;

    @Column(name = "driver_mobile", length = 10)
    private String driverMobile;

    // ── money ───────────────────────────────────────────────────────────────────────────

    @Column(name = "freight_charges", nullable = false, precision = 12, scale = 2)
    private BigDecimal freightCharges = BigDecimal.ZERO;

    @Column(name = "loading_charges", nullable = false, precision = 12, scale = 2)
    private BigDecimal loadingCharges = BigDecimal.ZERO;

    @Column(name = "unloading_charges", nullable = false, precision = 12, scale = 2)
    private BigDecimal unloadingCharges = BigDecimal.ZERO;

    @Column(name = "other_charges", nullable = false, precision = 12, scale = 2)
    private BigDecimal otherCharges = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal advance = BigDecimal.ZERO;

    /**
     * Generated by PostgreSQL. Read here, never written.
     *
     * <p>{@code @Generated} is what makes Hibernate re-SELECT the row after a write. Without it
     * the column is simply absent from the INSERT and never read back, so the value returned to
     * the caller is null on create and stale on update — the response says zero while the
     * database says fourteen thousand. {@code Vehicle.capacityTons} carries it for this reason.
     */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "total_charges", insertable = false, updatable = false,
            precision = 12, scale = 2)
    private BigDecimal totalCharges;

    /** Generated by PostgreSQL: total less advance. What the customer still owes. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(insertable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal balance;

    @Column(name = "special_instructions", length = 500)
    private String specialInstructions;

    @Column(length = 2000)
    private String notes;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected LorryReceipt() {
    }

    public LorryReceipt(String lrNumber, String series, int seqNo, LocalDate lrDate) {
        this.lrNumber = lrNumber;
        this.series = series;
        this.seqNo = seqNo;
        this.lrDate = lrDate;
    }

    /**
     * Call it off, keeping the row.
     *
     * <p>tts deletes an LR outright. A consignment note is a document a customer holds a copy
     * of and an auditor may ask about; the answer to "what happened to LR-041" has to be
     * something better than its absence.
     */
    public void cancel(String reason) {
        this.status = LrStatus.CANCELLED;
        this.cancelledAt = OffsetDateTime.now();
        this.cancelReason = reason;
    }

    public Long getId() { return id; }
    public String getLrNumber() { return lrNumber; }
    public void setLrNumber(String v) { this.lrNumber = v; }
    public String getSeries() { return series; }
    public int getSeqNo() { return seqNo; }
    public LocalDate getLrDate() { return lrDate; }
    public void setLrDate(LocalDate v) { this.lrDate = v; }
    public LrStatus getStatus() { return status; }
    public void setStatus(LrStatus v) { this.status = v; }

    public Long getConsignorId() { return consignorId; }
    public void setConsignorId(Long v) { this.consignorId = v; }
    public String getConsignorName() { return consignorName; }
    public void setConsignorName(String v) { this.consignorName = v; }
    public String getConsignorMobile() { return consignorMobile; }
    public void setConsignorMobile(String v) { this.consignorMobile = v; }
    public Long getConsigneeId() { return consigneeId; }
    public void setConsigneeId(Long v) { this.consigneeId = v; }
    public String getConsigneeName() { return consigneeName; }
    public void setConsigneeName(String v) { this.consigneeName = v; }
    public String getConsigneeMobile() { return consigneeMobile; }
    public void setConsigneeMobile(String v) { this.consigneeMobile = v; }

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
    public String getGoodsDescription() { return goodsDescription; }
    public void setGoodsDescription(String v) { this.goodsDescription = v; }
    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal v) { this.weightKg = v; }
    public Integer getPackages() { return packages; }
    public void setPackages(Integer v) { this.packages = v; }

    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long v) { this.vehicleId = v; }
    public String getVehicleNumber() { return vehicleNumber; }
    public void setVehicleNumber(String v) { this.vehicleNumber = v; }
    public Long getBodyTypeId() { return bodyTypeId; }
    public void setBodyTypeId(Long v) { this.bodyTypeId = v; }
    public String getVehicleType() { return vehicleType; }
    public void setVehicleType(String v) { this.vehicleType = v; }
    public Long getDriverUserId() { return driverUserId; }
    public void setDriverUserId(Long v) { this.driverUserId = v; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String v) { this.driverName = v; }
    public String getDriverMobile() { return driverMobile; }
    public void setDriverMobile(String v) { this.driverMobile = v; }

    public BigDecimal getFreightCharges() { return freightCharges; }
    public void setFreightCharges(BigDecimal v) { this.freightCharges = v; }
    public BigDecimal getLoadingCharges() { return loadingCharges; }
    public void setLoadingCharges(BigDecimal v) { this.loadingCharges = v; }
    public BigDecimal getUnloadingCharges() { return unloadingCharges; }
    public void setUnloadingCharges(BigDecimal v) { this.unloadingCharges = v; }
    public BigDecimal getOtherCharges() { return otherCharges; }
    public void setOtherCharges(BigDecimal v) { this.otherCharges = v; }
    public BigDecimal getAdvance() { return advance; }
    public void setAdvance(BigDecimal v) { this.advance = v; }
    public BigDecimal getTotalCharges() { return totalCharges; }
    public BigDecimal getBalance() { return balance; }

    public String getSpecialInstructions() { return specialInstructions; }
    public void setSpecialInstructions(String v) { this.specialInstructions = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public OffsetDateTime getCancelledAt() { return cancelledAt; }
    public String getCancelReason() { return cancelReason; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
