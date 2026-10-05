package com.vehiclemanagement.service;

import com.vehiclemanagement.domain.City;
import com.vehiclemanagement.domain.State;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.ConstraintErrors;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.CityRepository;
import com.vehiclemanagement.repo.StateRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** States and cities: the one vocabulary of places. Preferred locations point straight at these. */
@Service
public class GeoService {

    private final StateRepository states;
    private final CityRepository cities;

    public GeoService(StateRepository states, CityRepository cities) {
        this.states = states;
        this.cities = cities;
    }

    public List<State> listStates() {
        return states.findAllByOrderByNameAsc();
    }

    /** Type-ahead over the city master. A blank {@code q} means no filter, not no results. */
    public Page<City> searchCities(String q, Long stateId, Pageable pageable) {
        String clean = Normalizer.clean(q);
        return cities.search(clean == null ? null : "%" + clean + "%", stateId, pageable);
    }

    /** Every active city, all states — one query, grouped by the caller. */
    public List<City> listActiveCities() {
        return cities.findByActiveTrueOrderByNameAsc();
    }

    public List<City> listCities(long stateId) {
        return cities.findByStateIdOrderByNameAsc(stateId);
    }

    public State requireState(long stateId) {
        return states.findById(stateId).orElseThrow(
                () -> new ApiException.NotFound("State " + stateId + " not found."));
    }

    public City requireCity(long cityId) {
        return cities.findById(cityId).orElseThrow(
                () -> new ApiException.NotFound("City " + cityId + " not found."));
    }

    /**
     * Resolve a place typed as free text — and refuse to guess.
     *
     * <p>Several states have a Sagar and two have a Hyderabad. Picking one would silently reroute
     * a consignment, so an ambiguous name is an error the caller has to resolve, not a coin flip.
     */
    public City resolveCityByName(String name, String field) {
        String clean = Normalizer.clean(name);
        if (clean == null) {
            throw new FieldValidationException(field, "Place is required.");
        }
        List<City> hits = cities.findByNameKey(clean.toLowerCase());
        if (hits.isEmpty()) {
            throw new FieldValidationException(field,
                    "\"" + clean + "\" is not in the city list. Pick one from the list or add it.");
        }
        if (hits.size() > 1) {
            throw new FieldValidationException(field,
                    "\"" + clean + "\" exists in more than one state. Pick the city from the list.");
        }
        return hits.get(0);
    }

    /**
     * Add a city to a state, refusing a duplicate.
     *
     * <p>Distinct from {@link #ensureCity}, which is find-or-create for the seeder. Over an API
     * a create that quietly returned the existing row would let a typo look like a success —
     * the same split {@code BodyTypeService} makes, for the same reason.
     *
     * <p>The seed covers 3,285 places and still will not contain every industrial township
     * somebody dispatches from. This is how one gets added without a migration.
     */
    @Transactional
    public City createCity(long stateId, String name) {
        State state = requireState(stateId);
        String clean = Normalizer.clean(name);
        if (clean == null) {
            throw new FieldValidationException("name", "A city needs a name.");
        }
        cities.findByStateIdAndNameKey(state.getId(), clean.toLowerCase()).ifPresent(existing -> {
            throw new ApiException.Conflict(existing.getName() + " is already in "
                    + state.getName()
                    + (existing.isActive() ? "." : ", retired. Restore it instead."));
        });
        return ConstraintErrors.translating(
                () -> cities.saveAndFlush(new City(state.getId(), clean)));
    }

    /**
     * Retire a city. Never a delete.
     *
     * <p>{@code company_x_location} and {@code vehicle_x_location} reference it, and lorry
     * receipts point at it ON DELETE SET NULL — so a delete would either be refused by the
     * database or silently strip the link off historic receipts.
     */
    @Transactional
    public City retireCity(long cityId) {
        City c = requireCity(cityId);
        c.setActive(false);
        return c;
    }

    @Transactional
    public City restoreCity(long cityId) {
        City c = requireCity(cityId);
        c.setActive(true);
        return c;
    }

    /** Find-or-create, idempotent on (state, name_key). Used by the seeder. */
    @Transactional
    public State ensureState(String code, String name) {
        return states.findByCode(code).orElseGet(() -> states.save(new State(code, name)));
    }

    @Transactional
    public City ensureCity(State state, String name) {
        String clean = Normalizer.clean(name);
        if (clean == null) {
            return null;
        }
        return cities.findByStateIdAndNameKey(state.getId(), clean.toLowerCase())
                .orElseGet(() -> cities.save(new City(state.getId(), clean)));
    }
}
