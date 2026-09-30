package com.vehiclemanagement.service;

import com.vehiclemanagement.domain.City;
import com.vehiclemanagement.domain.Consignor;
import com.vehiclemanagement.domain.GoodsType;
import com.vehiclemanagement.domain.LorryReceipt;
import com.vehiclemanagement.domain.LrStatus;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserType;
import com.vehiclemanagement.domain.Vehicle;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.ConstraintErrors;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.BodyTypeRepository;
import com.vehiclemanagement.repo.LorryReceiptRepository;
import com.vehiclemanagement.repo.UserRepository;
import com.vehiclemanagement.repo.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Lorry receipts: writing them, numbering them, and moving them along.
 *
 * <p><b>The rule this whole class is organised around:</b> every master reference is resolved
 * once, at write time, and both the id and the resolved text are stored. Nothing here reads a
 * master to render an existing receipt. That is what makes an LR a document rather than a view
 * — reprint one from 2024 and it says what it said in 2024, even though the truck has been
 * sold and the customer renamed.
 *
 * <p>Ported from tts's {@code LrService} with four deliberate departures, each marked where it
 * happens: numbering is gap-free under concurrency, status transitions are enforced, a receipt
 * is cancelled rather than deleted, and a driver must actually be a driver.
 */
@Service
public class LrService {

    private static final Logger log = LoggerFactory.getLogger(LrService.class);

    /** One numbering run today. The column exists so a year-end reset is a row, not a migration. */
    private static final String DEFAULT_SERIES = "DEFAULT";

    private final LorryReceiptRepository receipts;
    private final ConsignorService consignors;
    private final GoodsTypeService goodsTypes;
    private final GeoService geo;
    private final VehicleRepository vehicles;
    private final UserRepository users;
    private final BodyTypeRepository bodyTypes;

    public LrService(LorryReceiptRepository receipts, ConsignorService consignors,
                     GoodsTypeService goodsTypes, GeoService geo, VehicleRepository vehicles,
                     UserRepository users, BodyTypeRepository bodyTypes) {
        this.receipts = receipts;
        this.consignors = consignors;
        this.goodsTypes = goodsTypes;
        this.geo = geo;
        this.vehicles = vehicles;
        this.users = users;
        this.bodyTypes = bodyTypes;
    }

    /** Everything a caller may ask for on one receipt. Null means "not given". */
    public record Request(String lrNumber, LocalDate lrDate, LrStatus status,
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
                          BigDecimal advance,
                          String specialInstructions, String notes) {
    }

    // ── reading ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<LorryReceipt> list(String q, LrStatus status, LocalDate from, LocalDate to,
                                   Long vehicleId, Long driverId, Long partyId, Boolean unpaid,
                                   Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new FieldValidationException("from", "The start date is after the end date.");
        }
        String like = Normalizer.clean(q) == null ? null : "%" + q.trim() + "%";
        return receipts.search(like, status == null ? null : status.name(), from, to,
                vehicleId, driverId, partyId, unpaid, pageable);
    }

    @Transactional(readOnly = true)
    public LorryReceipt get(long id) {
        return receipts.findById(id).orElseThrow(
                () -> new ApiException.NotFound("Lorry receipt " + id + " not found."));
    }

    @Transactional(readOnly = true)
    public LorryReceiptRepository.Totals totals() {
        return receipts.totals();
    }

    /**
     * What the next receipt will be numbered.
     *
     * <p>A preview, and explicitly not a reservation — two clerks opening the form together
     * both see the same number and only the one who saves first gets it. Reserving instead
     * would burn a number every time somebody opened the form and changed their mind, which is
     * the hole in the series this design exists to avoid.
     */
    @Transactional(readOnly = true)
    public String nextNumber() {
        Integer next = receipts.peekNextNumber(DEFAULT_SERIES);
        return format(next == null ? 1 : next);
    }

    // ── writing ─────────────────────────────────────────────────────────────────

    @Transactional
    public LorryReceipt create(Request req, String actor) {
        // Locks the counter row for the rest of this transaction; a second create waits here.
        Integer seq = receipts.lockNextNumber(DEFAULT_SERIES);
        if (seq == null) {
            throw new ApiException(500, "Numbering series " + DEFAULT_SERIES + " is missing.");
        }
        receipts.bumpCounter(DEFAULT_SERIES);

        LocalDate date = req.lrDate() == null ? LocalDate.now() : req.lrDate();
        LorryReceipt lr = new LorryReceipt(
                resolveNumber(req.lrNumber(), seq, null), DEFAULT_SERIES, seq, date);
        lr.setCreatedBy(actor);
        lr.setStatus(req.status() == null ? LrStatus.BOOKED : req.status());
        if (lr.getStatus() == LrStatus.CANCELLED) {
            throw new FieldValidationException("status",
                    "A new receipt cannot start cancelled.");
        }
        apply(lr, req);

        LorryReceipt saved = ConstraintErrors.translating(() -> receipts.saveAndFlush(lr));
        log.info("LR {} created by {} ({} -> {})", saved.getLrNumber(), actor,
                saved.getFromPlace(), saved.getToPlace());
        return saved;
    }

    /**
     * Correct a receipt, in any state.
     *
     * <p>An earlier version refused this once a receipt was delivered or cancelled, on the
     * argument that a closed document should not move. That was wrong about the work: a
     * misspelled consignee or a wrong weight is <b>discovered</b> when the paperwork is
     * reconciled, which is after delivery, and a register you cannot correct is one that
     * accumulates known-wrong rows with a sticky note beside them.
     *
     * <p><b>What is still refused is the lifecycle</b>, which is a different thing: an edit may
     * not walk a delivered receipt back to booked or revive a cancelled one. Correcting what a
     * receipt says and changing what has happened to it are separate acts, and only the second
     * is irreversible.
     */
    @Transactional
    public LorryReceipt update(long id, Request req) {
        LorryReceipt lr = get(id);

        if (req.status() != null && req.status() != lr.getStatus()) {
            moveTo(lr, req.status(), null);
        }
        lr.setLrNumber(resolveNumber(req.lrNumber(), lr.getSeqNo(), id));
        if (req.lrDate() != null) {
            lr.setLrDate(req.lrDate());
        }
        apply(lr, req);
        return ConstraintErrors.translating(() -> receipts.saveAndFlush(lr));
    }

    /**
     * Move a receipt along its lifecycle.
     *
     * <p>Separate from {@link #update} because it is a different act: a clerk fixing a
     * misspelled consignee is not the same event as a driver reporting delivery, and they do
     * not want the same confirmation, the same audit line, or the same permissions if those
     * ever diverge.
     */
    @Transactional
    public LorryReceipt changeStatus(long id, LrStatus next, String reason) {
        LorryReceipt lr = get(id);
        moveTo(lr, next, reason);
        return receipts.saveAndFlush(lr);
    }

    /**
     * Call a receipt off, keeping the row.
     *
     * <p><b>tts deletes.</b> A consignment note is a document the customer holds a copy of and
     * an auditor may ask about, and the series is supposed to be continuous — so the answer to
     * "what happened to LR-041" has to be better than its absence. The row stays, the status
     * says CANCELLED, and {@code ck_lr_cancelled} makes a cancellation without a date
     * unrepresentable.
     */
    @Transactional
    public LorryReceipt cancel(long id, String reason) {
        return changeStatus(id, LrStatus.CANCELLED, reason);
    }

    // ── internals ───────────────────────────────────────────────────────────────

    private void moveTo(LorryReceipt lr, LrStatus next, String reason) {
        if (!lr.getStatus().canMoveTo(next)) {
            throw new ApiException.Conflict("A " + lr.getStatus().name().toLowerCase()
                    + " receipt cannot become " + next.name().toLowerCase() + ".");
        }
        if (next == LrStatus.CANCELLED) {
            lr.cancel(Normalizer.clean(reason));
        } else {
            lr.setStatus(next);
        }
    }

    private static String format(int seq) {
        return "LR-" + String.format("%03d", seq);
    }

    private String resolveNumber(String requested, int seq, Long excludeId) {
        String number = Normalizer.clean(requested);
        number = number == null ? format(seq) : number.toUpperCase();
        boolean taken = excludeId == null
                ? receipts.existsByLrNumber(number)
                : receipts.existsByLrNumberAndIdNot(number, excludeId);
        if (taken) {
            throw new ApiException.Conflict("LR number " + number + " is already used.");
        }
        return number;
    }

    /** Resolve every master reference and write both the link and the printed text. */
    private void apply(LorryReceipt lr, Request req) {
        applyParties(lr, req);
        applyPlaces(lr, req);
        applyGoods(lr, req);
        applyVehicle(lr, req);
        applyCharges(lr, req);
        lr.setSpecialInstructions(Normalizer.clean(req.specialInstructions()));
        lr.setNotes(Normalizer.clean(req.notes()));
    }

    private void applyParties(LorryReceipt lr, Request req) {
        String consignorMobile = Normalizer.optionalMobile(
                req.consignorMobile(), "consignor_mobile");
        Consignor consignor = resolveParty(req.consignorId(), req.consignorName(),
                consignorMobile, "consignor_name", "A consignor");
        lr.setConsignorId(consignor.getId());
        lr.setConsignorName(consignor.getName());
        // Typed on the receipt wins over the customer's default: it is who to ring about THIS
        // load, which may be a site foreman rather than the office.
        lr.setConsignorMobile(consignorMobile != null ? consignorMobile : consignor.getMobile());

        String consigneeMobile = Normalizer.optionalMobile(
                req.consigneeMobile(), "consignee_mobile");
        Consignor consignee = resolveParty(req.consigneeId(), req.consigneeName(),
                consigneeMobile, "consignee_name", "A consignee");
        lr.setConsigneeId(consignee.getId());
        lr.setConsigneeName(consignee.getName());
        lr.setConsigneeMobile(consigneeMobile != null ? consigneeMobile : consignee.getMobile());
    }

    private Consignor resolveParty(Long id, String name, String mobile,
                                   String field, String label) {
        if (id != null) {
            Consignor picked = consignors.get(id);
            if (!picked.isActive()) {
                throw new FieldValidationException(field,
                        picked.getName() + " is retired. Restore it or pick another customer.");
            }
            return picked;
        }
        if (Normalizer.clean(name) == null) {
            throw new FieldValidationException(field,
                    label + " is required. Pick one from the list or type a new name.");
        }
        return consignors.ensure(name, mobile);
    }

    private void applyPlaces(LorryReceipt lr, Request req) {
        City from = resolveCity(req.fromCityId(), req.fromPlace(), "from_place", "From");
        lr.setFromCityId(from == null ? null : from.getId());
        lr.setFromPlace(from == null ? Normalizer.clean(req.fromPlace()) : from.getName());

        City to = resolveCity(req.toCityId(), req.toPlace(), "to_place", "To");
        lr.setToCityId(to == null ? null : to.getId());
        lr.setToPlace(to == null ? Normalizer.clean(req.toPlace()) : to.getName());
    }

    /**
     * A place is a city from the master when we can identify one, and free text otherwise.
     *
     * <p>tts <b>requires</b> a city match and rejects the receipt without one. That is wrong
     * for this business: loads are picked up at a factory gate on a highway, and "NH-48, near
     * Bagru" is a real answer that no city list contains. The link is a bonus that makes
     * "everything we carried out of Jaipur" work; losing it must not lose the receipt.
     */
    private City resolveCity(Long cityId, String name, String field, String label) {
        if (cityId != null) {
            return geo.requireCity(cityId);
        }
        if (Normalizer.clean(name) == null) {
            throw new FieldValidationException(field, label + " place is required.");
        }
        try {
            return geo.resolveCityByName(name, field);
        } catch (RuntimeException notACity) {
            return null;
        }
    }

    private void applyGoods(LorryReceipt lr, Request req) {
        GoodsType type = req.goodsTypeId() != null
                ? goodsTypes.get(req.goodsTypeId())
                : goodsTypes.ensure(req.goodsDescription());
        lr.setGoodsTypeId(type == null ? null : type.getId());
        String description = Normalizer.clean(req.goodsDescription());
        lr.setGoodsDescription(description != null ? description
                : type == null ? null : type.getName());
        lr.setWeightKg(scaled(req.weightKg()));
        lr.setPackages(req.packages());
    }

    /**
     * Link the truck and driver when known, and fill the printed fields from them when the
     * caller left those blank.
     *
     * <p>Both links are optional on purpose: an LR may name a hired truck that is not in our
     * register and a driver who is not on our books. What is written on the paper matters more
     * than whether we happen to have a row for it.
     */
    private void applyVehicle(LorryReceipt lr, Request req) {
        Vehicle vehicle = null;
        if (req.vehicleId() != null) {
            vehicle = vehicles.findById(req.vehicleId()).orElseThrow(
                    () -> new ApiException.NotFound("Vehicle " + req.vehicleId() + " not found."));
        }
        lr.setVehicleId(vehicle == null ? null : vehicle.getId());

        String typed = Normalizer.clean(req.vehicleNumber());
        lr.setVehicleNumber(typed != null
                ? Normalizer.registration(typed, "vehicle_number")
                : vehicle == null ? null : vehicle.getRegistrationNumber());

        Long bodyTypeId = req.bodyTypeId() != null ? req.bodyTypeId()
                : vehicle == null ? null : vehicle.getBodyTypeId();
        lr.setBodyTypeId(bodyTypeId);
        String vehicleType = Normalizer.clean(req.vehicleType());
        lr.setVehicleType(vehicleType != null ? vehicleType
                : bodyTypeId == null ? null
                : bodyTypes.findById(bodyTypeId).map(bt -> bt.getName()).orElse(null));

        lr.setDriverUserId(null);
        User driver = null;
        if (req.driverUserId() != null) {
            driver = users.findById(req.driverUserId()).orElseThrow(
                    () -> new ApiException.NotFound(
                            "Person " + req.driverUserId() + " not found."));
            // tts checks this too, and it is worth keeping: assigning an owner or an office
            // clerk as the driver of a load is a mis-click, and the LR is what a checkpoint
            // reads.
            if (driver.getUserType() != UserType.DRIVER && driver.getUserType() != UserType.BOTH) {
                throw new FieldValidationException("driver_user_id",
                        driver.getName() + " is not a driver.");
            }
            lr.setDriverUserId(driver.getId());
        }

        String driverName = Normalizer.clean(req.driverName());
        lr.setDriverName(driverName != null ? driverName
                : driver == null ? null : driver.getName());
        String driverMobile = Normalizer.optionalMobile(req.driverMobile(), "driver_mobile");
        lr.setDriverMobile(driverMobile != null ? driverMobile
                : driver == null ? null : driver.getMobile());
    }

    private void applyCharges(LorryReceipt lr, Request req) {
        lr.setFreightCharges(money(req.freightCharges()));
        lr.setLoadingCharges(money(req.loadingCharges()));
        lr.setUnloadingCharges(money(req.unloadingCharges()));
        lr.setOtherCharges(money(req.otherCharges()));
        lr.setAdvance(money(req.advance()));

        BigDecimal total = lr.getFreightCharges().add(lr.getLoadingCharges())
                .add(lr.getUnloadingCharges()).add(lr.getOtherCharges());
        // ck_lr_advance enforces this too. Checked here as well so the caller gets a message
        // naming the field rather than a translated constraint violation.
        if (lr.getAdvance().compareTo(total) > 0) {
            throw new FieldValidationException("advance",
                    "The advance is more than the total charges.");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        if (v.signum() < 0) {
            throw new FieldValidationException("charges", "Charges cannot be negative.");
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaled(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw new FieldValidationException("weight_kg", "Weight cannot be negative.");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
