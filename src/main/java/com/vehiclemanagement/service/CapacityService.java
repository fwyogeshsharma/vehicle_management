package com.vehiclemanagement.service;

import com.vehiclemanagement.domain.Capacity;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.ConstraintErrors;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.CapacityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * The capacity pick list.
 *
 * <p><b>Still a pick list, not a foreign key.</b> {@code vehicles.capacity} remains free text
 * and nothing here points at it — changeset 017 sets out why, and adding a row through this
 * service does not change that. What this exists for is the dropdown: so a new rung can be
 * added from the masters screen rather than by hand in a migration.
 *
 * <p>{@code tons} is what makes the list sort sensibly; it is not what
 * {@code vehicles.capacity_tons} is derived from, which is still the text.
 */
@Service
public class CapacityService {

    private final CapacityRepository capacities;

    public CapacityService(CapacityRepository capacities) {
        this.capacities = capacities;
    }

    public List<Capacity> list(boolean includeInactive) {
        return includeInactive ? capacities.findAllByOrderByTonsAsc()
                               : capacities.findByActiveTrueOrderByTonsAsc();
    }

    public Capacity get(long id) {
        return capacities.findById(id).orElseThrow(
                () -> new ApiException.NotFound("Capacity " + id + " not found."));
    }

    @Transactional
    public Capacity create(String label, BigDecimal tons) {
        String clean = Normalizer.clean(label);
        if (clean == null) {
            throw new FieldValidationException("label", "A capacity needs a label.");
        }
        if (tons == null || tons.signum() <= 0) {
            throw new FieldValidationException("tons", "Tonnage must be more than zero.");
        }
        capacities.findByLabelIgnoreCase(clean).ifPresent(existing -> {
            throw new ApiException.Conflict("A capacity called " + existing.getLabel()
                    + " already exists"
                    + (existing.isActive() ? "." : ", retired. Restore it instead."));
        });
        return ConstraintErrors.translating(
                () -> capacities.saveAndFlush(new Capacity(clean, tons)));
    }

    /**
     * Retire, not delete. Nothing references a capacity by id, so a delete would technically
     * succeed — and would silently remove a rung that existing vehicles still describe
     * themselves with, leaving their text matching nothing in the list.
     */
    @Transactional
    public Capacity retire(long id) {
        Capacity c = get(id);
        c.setActive(false);
        return c;
    }

    @Transactional
    public Capacity restore(long id) {
        Capacity c = get(id);
        c.setActive(true);
        return c;
    }
}
