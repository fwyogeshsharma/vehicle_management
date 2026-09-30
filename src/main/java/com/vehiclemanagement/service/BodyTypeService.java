package com.vehiclemanagement.service;

import com.vehiclemanagement.domain.BodyType;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.ConstraintErrors;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.BodyTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** The body-type master: the one vehicle attribute that is a lookup rather than free text. */
@Service
public class BodyTypeService {

    private final BodyTypeRepository bodyTypes;

    public BodyTypeService(BodyTypeRepository bodyTypes) {
        this.bodyTypes = bodyTypes;
    }

    public List<BodyType> listActive() {
        return bodyTypes.findByActiveTrueOrderByNameAsc();
    }

    /** Retired types included, for an administrator deciding what to bring back. */
    public List<BodyType> list(boolean includeInactive) {
        return includeInactive ? bodyTypes.findAllByOrderByNameAsc()
                               : bodyTypes.findByActiveTrueOrderByNameAsc();
    }

    /**
     * Add a body type, refusing a duplicate.
     *
     * <p>Distinct from {@link #ensure}, which is find-or-create and exists for the seeder. Over
     * an API "create" that silently returned an existing row would let a typo look like a
     * success, so this one conflicts instead.
     */
    @Transactional
    public BodyType create(String name) {
        String clean = Normalizer.clean(name);
        if (clean == null) {
            throw new FieldValidationException("name", "A body type needs a name.");
        }
        bodyTypes.findByNameKey(clean.toLowerCase()).ifPresent(existing -> {
            throw new ApiException.Conflict("A body type called " + existing.getName()
                    + " already exists"
                    + (existing.isActive() ? "." : ", retired. Restore it instead."));
        });
        return ConstraintErrors.translating(() -> bodyTypes.saveAndFlush(new BodyType(clean)));
    }

    /** Find-or-create on the lower-cased name. Idempotent, so the seeder can call it freely. */
    @Transactional
    public BodyType ensure(String name) {
        String clean = Normalizer.clean(name);
        if (clean == null) {
            return null;
        }
        return bodyTypes.findByNameKey(clean.toLowerCase())
                .orElseGet(() -> bodyTypes.save(new BodyType(clean)));
    }

    /**
     * Retire a body type. Not a delete: vehicles reference it ON DELETE RESTRICT, so deleting one
     * under a live fleet is refused by the database anyway.
     */
    @Transactional
    public BodyType retire(long id) {
        BodyType bt = bodyTypes.findById(id).orElseThrow(
                () -> new ApiException.NotFound("Body type " + id + " not found."));
        bt.setActive(false);
        return bt;
    }

    /** Bring a retired body type back into use. */
    @Transactional
    public BodyType restore(long id) {
        BodyType bt = bodyTypes.findById(id).orElseThrow(
                () -> new ApiException.NotFound("Body type " + id + " not found."));
        bt.setActive(true);
        return bt;
    }
}
