package com.vehiclemanagement.service;

import com.vehiclemanagement.domain.City;
import com.vehiclemanagement.domain.Consignor;
import com.vehiclemanagement.domain.LoadProviderContract;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.ConstraintErrors;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.LoadProviderContractRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The terms agreed with each load provider: what they approved, what comes off it, and what the
 * truck is finally paid.
 *
 * <p><b>Only the company is required, and no figure is computed.</b> The numbers are typed by
 * whoever agreed them; deriving the truck's price here would apply one formula to providers who
 * each use their own, and the day it disagreed with the signed paper this table would be the
 * one that was wrong. What <i>is</i> checked is that an amount is not negative and a percentage
 * is between 0 and 100 -- a typo that no provider's terms can contain.
 *
 * <p>The location resolves against the city list when it can and stays free text when it
 * cannot, as on a lorry receipt or a lane.
 */
@Service
public class LoadProviderContractService {

    private static final Logger log = LoggerFactory.getLogger(LoadProviderContractService.class);

    private final LoadProviderContractRepository contracts;
    private final GeoService geo;
    private final ConsignorService customers;

    public LoadProviderContractService(LoadProviderContractRepository contracts, GeoService geo,
                                       ConsignorService customers) {
        this.contracts = contracts;
        this.geo = geo;
        this.customers = customers;
    }

    /** Everything a caller may say about a contract. Only the customer matters. */
    public record Request(Long consignorId, Long cityId, String location,
                          BigDecimal priceApproved, BigDecimal loadingCharges,
                          BigDecimal unloadingCharges, BigDecimal commissionPercentage,
                          BigDecimal munshiana, BigDecimal dala,
                          BigDecimal finalPriceForTruck, BigDecimal perTonnePrice,
                          String notes) {
    }

    @Transactional(readOnly = true)
    public Page<LoadProviderContract> list(String q, Boolean active, Pageable pageable) {
        String like = Normalizer.clean(q) == null ? null : "%" + q.trim() + "%";
        return contracts.search(like, active, pageable);
    }

    @Transactional(readOnly = true)
    public LoadProviderContract get(long id) {
        return contracts.findById(id).orElseThrow(
                () -> new ApiException.NotFound("Contract " + id + " not found."));
    }

    @Transactional
    public LoadProviderContract create(Request req, String actor) {
        LoadProviderContract contract = new LoadProviderContract();
        apply(contract, req);
        contract.setRecordedBy(actor);
        LoadProviderContract saved = ConstraintErrors.translating(
                () -> contracts.saveAndFlush(contract));
        log.info("contract {} recorded by {}: {} at {}", saved.getId(), actor,
                saved.getCompanyName(), saved.getLocation());
        return saved;
    }

    @Transactional
    public LoadProviderContract update(long id, Request req) {
        LoadProviderContract contract = get(id);
        apply(contract, req);
        return ConstraintErrors.translating(() -> contracts.saveAndFlush(contract));
    }

    /** Retire, never delete: superseded terms are the record of what was agreed before. */
    @Transactional
    public LoadProviderContract retire(long id) {
        LoadProviderContract contract = get(id);
        contract.setActive(false);
        return contract;
    }

    @Transactional
    public LoadProviderContract restore(long id) {
        LoadProviderContract contract = get(id);
        contract.setActive(true);
        return contract;
    }

    private void apply(LoadProviderContract c, Request req) {
        Consignor customer = customer(req.consignorId(), c.getConsignorId());
        c.setConsignorId(customer.getId());
        c.setCompanyName(customer.getName());

        City city = resolveCity(req.cityId(), req.location());
        c.setCityId(city == null ? null : city.getId());
        c.setLocation(city == null ? Normalizer.clean(req.location()) : city.getName());

        c.setPriceApproved(amount(req.priceApproved(), "price_approved"));
        c.setLoadingCharges(amount(req.loadingCharges(), "loading_charges"));
        c.setUnloadingCharges(amount(req.unloadingCharges(), "unloading_charges"));
        c.setMunshiana(amount(req.munshiana(), "munshiana"));
        c.setDala(amount(req.dala(), "dala"));
        c.setFinalPriceForTruck(amount(req.finalPriceForTruck(), "final_price_for_truck"));
        c.setPerTonnePrice(amount(req.perTonnePrice(), "per_tonne_price"));

        BigDecimal pct = req.commissionPercentage();
        if (pct != null && (pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new FieldValidationException("commission_percentage",
                    "Commission is a percentage between 0 and 100.");
        }
        c.setCommissionPercentage(pct == null ? null : pct.setScale(2, RoundingMode.HALF_UP));
        c.setNotes(Normalizer.clean(req.notes()));
    }

    /**
     * The customer a contract is for. Required, and it must be on the master list: the name is
     * taken from there, never from the request.
     *
     * <p>A retired customer cannot be newly chosen, but a contract already pointing at one may
     * be corrected without being forced to move.
     */
    private Consignor customer(Long id, Long current) {
        if (id == null) {
            throw new FieldValidationException("consignor_id",
                    "Pick the load provider company from the customer list.");
        }
        Consignor customer;
        try {
            customer = customers.get(id);
        } catch (ApiException.NotFound e) {
            throw new FieldValidationException("consignor_id",
                    "That customer is not on the list.");
        }
        if (!customer.isActive() && !customer.getId().equals(current)) {
            throw new FieldValidationException("consignor_id",
                    customer.getName() + " has been retired. Pick an active customer.");
        }
        return customer;
    }

    /** A money figure: blank is allowed, negative is not, and it is held to paise. */
    private static BigDecimal amount(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw new FieldValidationException(field, "This cannot be negative.");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** A place matched against the city list, but not restricted to it. */
    private City resolveCity(Long cityId, String name) {
        if (cityId != null) {
            return geo.requireCity(cityId);
        }
        if (Normalizer.clean(name) == null) {
            return null;
        }
        try {
            return geo.resolveCityByName(name, "location");
        } catch (RuntimeException notACity) {
            return null;
        }
    }
}
