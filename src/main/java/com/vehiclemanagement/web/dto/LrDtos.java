package com.vehiclemanagement.web.dto;

import com.vehiclemanagement.domain.Consignor;
import com.vehiclemanagement.domain.GoodsType;
import com.vehiclemanagement.domain.LorryReceipt;
import com.vehiclemanagement.domain.LrStatus;
import com.vehiclemanagement.repo.LorryReceiptRepository;
import com.vehiclemanagement.service.LrService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Lorry receipts on the wire. JSON is snake_case, configured globally.
 *
 * <p><b>The response carries both the link and the snapshot for every master</b> —
 * {@code consignor_id} <i>and</i> {@code consignor_name}, {@code vehicle_id} <i>and</i>
 * {@code vehicle_number}. That is not redundancy to be tidied away: the id is what the UI
 * makes clickable, the text is what the document says, and on an old receipt they legitimately
 * disagree. A client that shows the id's current value instead of the text is showing
 * something the customer's copy does not.
 */
public final class LrDtos {

    private LrDtos() {
    }

    /**
     * Creating or editing a receipt.
     *
     * <p>Each master may be given either way: an id picked from a list, or a name typed in.
     * Only the date is required, because a clerk writing a receipt at a loading bay has the
     * truck in front of them and the paperwork catching up.
     */
    public record SaveRequest(
            /** Leave blank to take the next number in the series; set it to override. */
            @Size(max = 30) String lrNumber,
            @NotNull(message = "A receipt needs a date.") LocalDate lrDate,
            LrStatus status,

            Long consignorId,
            @Size(max = 160) String consignorName,
            String consignorMobile,
            Long consigneeId,
            @Size(max = 160) String consigneeName,
            String consigneeMobile,

            Long fromCityId,
            @Size(max = 120) String fromPlace,
            Long toCityId,
            @Size(max = 120) String toPlace,

            Long goodsTypeId,
            @Size(max = 500) String goodsDescription,
            BigDecimal weightKg,
            Integer packages,

            Long vehicleId,
            @Size(max = 20) String vehicleNumber,
            Long bodyTypeId,
            @Size(max = 80) String vehicleType,
            Long driverUserId,
            @Size(max = 128) String driverName,
            String driverMobile,

            BigDecimal freightCharges,
            BigDecimal loadingCharges,
            BigDecimal unloadingCharges,
            BigDecimal otherCharges,
            BigDecimal advance,

            @Size(max = 500) String specialInstructions,
            @Size(max = 2000) String notes) {

        public LrService.Request toRequest() {
            return new LrService.Request(lrNumber, lrDate, status,
                    consignorId, consignorName, consignorMobile,
                    consigneeId, consigneeName, consigneeMobile,
                    fromCityId, fromPlace, toCityId, toPlace,
                    goodsTypeId, goodsDescription, weightKg, packages,
                    vehicleId, vehicleNumber, bodyTypeId, vehicleType,
                    driverUserId, driverName, driverMobile,
                    freightCharges, loadingCharges, unloadingCharges, otherCharges, advance,
                    specialInstructions, notes);
        }
    }

    /** Moving a receipt along, or calling it off. */
    public record StatusRequest(@NotNull(message = "Which status?") LrStatus status,
                                @Size(max = 500) String reason) {
    }

    public record CancelRequest(@Size(max = 500) String reason) {
    }

    /**
     * One receipt, whole.
     *
     * <p>There is no separate summary shape. A receipt is about forty fields and the list needs
     * most of them — number, parties, route, truck, money, status — so a trimmed variant would
     * save little and guarantee that the list and the detail view drift apart.
     */
    public record Detail(Long id, String lrNumber, String series, int seqNo,
                         LocalDate lrDate, LrStatus status,

                         Long consignorId, String consignorName, String consignorMobile,
                         Long consigneeId, String consigneeName, String consigneeMobile,

                         Long fromCityId, String fromPlace,
                         Long toCityId, String toPlace,

                         Long goodsTypeId, String goodsDescription,
                         BigDecimal weightKg, Integer packages,

                         Long vehicleId, String vehicleNumber,
                         Long bodyTypeId, String vehicleType,
                         Long driverUserId, String driverName, String driverMobile,

                         BigDecimal freightCharges, BigDecimal loadingCharges,
                         BigDecimal unloadingCharges, BigDecimal otherCharges,
                         BigDecimal totalCharges, BigDecimal advance, BigDecimal balance,

                         String specialInstructions, String notes,
                         OffsetDateTime cancelledAt, String cancelReason,
                         String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        public static Detail from(LorryReceipt l) {
            return new Detail(l.getId(), l.getLrNumber(), l.getSeries(), l.getSeqNo(),
                    l.getLrDate(), l.getStatus(),
                    l.getConsignorId(), l.getConsignorName(), l.getConsignorMobile(),
                    l.getConsigneeId(), l.getConsigneeName(), l.getConsigneeMobile(),
                    l.getFromCityId(), l.getFromPlace(),
                    l.getToCityId(), l.getToPlace(),
                    l.getGoodsTypeId(), l.getGoodsDescription(),
                    l.getWeightKg(), l.getPackages(),
                    l.getVehicleId(), l.getVehicleNumber(),
                    l.getBodyTypeId(), l.getVehicleType(),
                    l.getDriverUserId(), l.getDriverName(), l.getDriverMobile(),
                    l.getFreightCharges(), l.getLoadingCharges(),
                    l.getUnloadingCharges(), l.getOtherCharges(),
                    l.getTotalCharges(), l.getAdvance(), l.getBalance(),
                    l.getSpecialInstructions(), l.getNotes(),
                    l.getCancelledAt(), l.getCancelReason(),
                    l.getCreatedBy(), l.getCreatedAt(), l.getUpdatedAt());
        }
    }

    /** The register's header: how many are where, and what is still owed. */
    public record Totals(long total, long booked, long inTransit, long delivered,
                         long cancelled, BigDecimal outstanding) {

        public static Totals from(LorryReceiptRepository.Totals t) {
            return new Totals(t.getTotal(), t.getBooked(), t.getInTransit(),
                    t.getDelivered(), t.getCancelled(), t.getOutstanding());
        }
    }

    public record NextNumber(String lrNumber) {
    }

    // ── the two new masters ─────────────────────────────────────────────────────

    public record CustomerRequest(@NotNull(message = "A customer needs a name.")
                                  @Size(max = 160) String name,
                                  String mobile,
                                  @Size(max = 300) String address,
                                  @Size(max = 15) String gstin) {
    }

    public record Customer(Long id, String name, String mobile, String address, String gstin,
                           boolean active, OffsetDateTime createdAt) {

        public static Customer from(Consignor c) {
            return new Customer(c.getId(), c.getName(), c.getMobile(), c.getAddress(),
                    c.getGstin(), c.isActive(), c.getCreatedAt());
        }
    }

    public record Goods(Long id, String name, boolean active) {

        public static Goods from(GoodsType g) {
            return new Goods(g.getId(), g.getName(), g.isActive());
        }
    }

    public record NameRequest(@NotNull(message = "A name is required.")
                              @Size(max = 160) String name) {
    }
}
