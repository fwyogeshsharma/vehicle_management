package com.vehiclemanagement.service;

import com.vehiclemanagement.domain.City;
import com.vehiclemanagement.domain.Consignor;
import com.vehiclemanagement.domain.GoodsType;
import com.vehiclemanagement.domain.LoadRequest;
import com.vehiclemanagement.domain.Vehicle;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.ConstraintErrors;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.BodyTypeRepository;
import com.vehiclemanagement.repo.LoadRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The day's load requests: what customers have asked us to move.
 *
 * <p><b>Four things are required</b>: which customer, and the route (from and to). Everything
 * else is optional, because the ask arrives on a phone call and a form that demands a weight
 * gets an invented one. The customer is picked from the master by id and the name is taken from
 * there; places and goods resolve against their masters when they can and stay text when they
 * cannot, as on a lane.
 *
 * <p>There is no delete. A request that fell through is {@code CANCELLED}, which keeps the fact
 * that the customer asked.
 */
@Service
public class LoadRequestService {

    private static final Logger log = LoggerFactory.getLogger(LoadRequestService.class);

    private final LoadRequestRepository requests;
    private final ConsignorService customers;
    private final GeoService geo;
    private final GoodsTypeService goodsTypes;
    private final BodyTypeRepository bodyTypes;
    private final VehicleService vehicles;

    public LoadRequestService(LoadRequestRepository requests, ConsignorService customers,
                              GeoService geo, GoodsTypeService goodsTypes,
                              BodyTypeRepository bodyTypes, VehicleService vehicles) {
        this.requests = requests;
        this.customers = customers;
        this.geo = geo;
        this.goodsTypes = goodsTypes;
        this.bodyTypes = bodyTypes;
        this.vehicles = vehicles;
    }

    /** Everything a caller may say about a request. */
    public record Request(Long consignorId, LocalDate requestDate,
                          Long fromCityId, String fromPlace, Long toCityId, String toPlace,
                          Long goodsTypeId, String goods,
                          BigDecimal weightTons, Integer trucksRequired, Long bodyTypeId,
                          LocalDate loadingDate, BigDecimal offeredPrice,
                          String contactName, String contactMobile, String notes,
                          String status) {
    }

    @Transactional(readOnly = true)
    public Page<LoadRequest> list(String q, String status, Long consignorId,
                                  LocalDate from, LocalDate to, Pageable pageable) {
        String like = Normalizer.clean(q) == null ? null : "%" + q.trim() + "%";
        String wanted = Normalizer.clean(status) == null ? null : status(status).name();
        return requests.search(like, wanted, consignorId, from, to, pageable);
    }

    @Transactional(readOnly = true)
    public LoadRequest get(long id) {
        return requests.findById(id).orElseThrow(
                () -> new ApiException.NotFound("Load request " + id + " not found."));
    }

    @Transactional
    public LoadRequest create(Request req, String actor) {
        LoadRequest request = new LoadRequest();
        apply(request, req);
        request.setRecordedBy(actor);
        LoadRequest saved = ConstraintErrors.translating(() -> requests.saveAndFlush(request));
        log.info("load request {} recorded by {}: {} {} -> {}", saved.getId(), actor,
                saved.getCompanyName(), saved.getFromPlace(), saved.getToPlace());
        return saved;
    }

    @Transactional
    public LoadRequest update(long id, Request req) {
        LoadRequest request = get(id);
        apply(request, req);
        return ConstraintErrors.translating(() -> requests.saveAndFlush(request));
    }

    /**
     * The vehicles that run where a request wants to go.
     *
     * @param fromKnown false when the pickup place is not on the city list, so nothing can be
     *                  matched against it
     * @param toKnown   the same for the drop place
     */
    public record Match(boolean fromKnown, boolean toKnown, List<Vehicle> vehicles) {
    }

    /**
     * Active vehicles that serve the request's pickup and drop cities, by the same rule the
     * Vehicles tab's "serving city" filter uses: a vehicle (or its company) names the city, or
     * names the whole state it is in.
     *
     * <p>{@code either} false (the default question) wants a vehicle that serves <b>both</b>
     * places; true widens it to <b>either</b>. A place that is not a city on the list can match
     * nothing, so for "both" it empties the answer, and for "either" it is simply left out; the
     * flags say which happened so the screen can explain rather than show a bare empty list.
     */
    @Transactional(readOnly = true)
    public Match matchingVehicles(long id, boolean either) {
        LoadRequest r = get(id);
        boolean fromKnown = r.getFromCityId() != null;
        boolean toKnown = r.getToCityId() != null;
        List<Vehicle> atFrom = fromKnown ? vehicles.servingCity(r.getFromCityId()) : List.of();
        List<Vehicle> atTo = toKnown ? vehicles.servingCity(r.getToCityId()) : List.of();

        Map<Long, Vehicle> found = new LinkedHashMap<>();
        if (either) {
            atFrom.forEach(v -> found.put(v.getId(), v));
            atTo.forEach(v -> found.putIfAbsent(v.getId(), v));
        } else if (fromKnown && toKnown) {
            java.util.Set<Long> alsoAtTo = new java.util.HashSet<>();
            atTo.forEach(v -> alsoAtTo.add(v.getId()));
            atFrom.stream().filter(v -> alsoAtTo.contains(v.getId()))
                    .forEach(v -> found.put(v.getId(), v));
        }
        List<Vehicle> ordered = found.values().stream()
                .sorted(Comparator.comparing(Vehicle::getRegistrationNumber,
                        Comparator.nullsLast(String::compareTo)))
                .toList();
        return new Match(fromKnown, toKnown, ordered);
    }

    private void apply(LoadRequest r, Request req) {
        Consignor customer = customer(req.consignorId(), r.getConsignorId());
        r.setConsignorId(customer.getId());
        r.setCompanyName(customer.getName());

        r.setRequestDate(req.requestDate() != null ? req.requestDate() : LocalDate.now());

        City from = resolveCity(req.fromCityId(), req.fromPlace(), "from_place", "From");
        r.setFromCityId(from == null ? null : from.getId());
        r.setFromPlace(from == null ? Normalizer.clean(req.fromPlace()) : from.getName());

        City to = resolveCity(req.toCityId(), req.toPlace(), "to_place", "To");
        r.setToCityId(to == null ? null : to.getId());
        r.setToPlace(to == null ? Normalizer.clean(req.toPlace()) : to.getName());

        GoodsType type = req.goodsTypeId() != null
                ? goodsTypes.get(req.goodsTypeId())
                : goodsTypes.ensure(req.goods());
        r.setGoodsTypeId(type == null ? null : type.getId());
        String goods = Normalizer.clean(req.goods());
        r.setGoods(goods != null ? goods : type == null ? null : type.getName());

        BigDecimal weight = req.weightTons();
        if (weight != null && weight.signum() <= 0) {
            throw new FieldValidationException("weight_tons", "Weight must be more than zero.");
        }
        r.setWeightTons(weight == null ? null : weight.setScale(2, RoundingMode.HALF_UP));

        int trucks = req.trucksRequired() == null ? 1 : req.trucksRequired();
        if (trucks < 1 || trucks > 500) {
            throw new FieldValidationException("trucks_required",
                    "Trucks required is a number from 1 to 500.");
        }
        r.setTrucksRequired((short) trucks);

        if (req.bodyTypeId() != null && !bodyTypes.existsById(req.bodyTypeId())) {
            throw new FieldValidationException("body_type_id", "That truck type is not on the list.");
        }
        r.setBodyTypeId(req.bodyTypeId());

        r.setLoadingDate(req.loadingDate());

        BigDecimal price = req.offeredPrice();
        if (price != null && price.signum() < 0) {
            throw new FieldValidationException("offered_price", "This cannot be negative.");
        }
        r.setOfferedPrice(price == null ? null : price.setScale(2, RoundingMode.HALF_UP));

        r.setContactName(Normalizer.clean(req.contactName()));
        r.setContactMobile(Normalizer.optionalMobile(req.contactMobile(), "contact_mobile"));
        r.setNotes(Normalizer.clean(req.notes()));
        // Absent on a create means OPEN; absent on an edit keeps what the request already was.
        if (Normalizer.clean(req.status()) != null) {
            r.setStatus(status(req.status()));
        }
    }

    private static LoadRequest.Status status(String raw) {
        try {
            return LoadRequest.Status.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException("status",
                    "Status is one of OPEN, FULFILLED or CANCELLED.");
        }
    }

    /**
     * The customer a request is for: required, on the master list, and named from there. A
     * retired customer cannot be newly chosen, but a request already pointing at one can still
     * be corrected.
     */
    private Consignor customer(Long id, Long current) {
        if (id == null) {
            throw new FieldValidationException("consignor_id",
                    "Pick the company from the customer list.");
        }
        Consignor customer;
        try {
            customer = customers.get(id);
        } catch (ApiException.NotFound e) {
            throw new FieldValidationException("consignor_id", "That customer is not on the list.");
        }
        if (!customer.isActive() && !customer.getId().equals(current)) {
            throw new FieldValidationException("consignor_id",
                    customer.getName() + " has been retired. Pick an active customer.");
        }
        return customer;
    }

    /** A place, matched against the city list but not restricted to it. */
    private City resolveCity(Long cityId, String name, String field, String label) {
        if (cityId != null) {
            return geo.requireCity(cityId);
        }
        if (Normalizer.clean(name) == null) {
            throw new FieldValidationException(field, label + " is required.");
        }
        try {
            return geo.resolveCityByName(name, field);
        } catch (RuntimeException notACity) {
            return null;
        }
    }
}
