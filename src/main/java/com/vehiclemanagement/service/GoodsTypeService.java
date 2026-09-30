package com.vehiclemanagement.service;

import com.vehiclemanagement.domain.GoodsType;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.ConstraintErrors;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.GoodsTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The goods master: what the dropdown offers.
 *
 * <p>Modelled on {@link BodyTypeService} down to the create/ensure split — {@code create} is
 * the API verb and refuses a duplicate so a typo cannot look like a success; {@code ensure} is
 * find-or-create, used when writing an LR for something nobody has carried before.
 */
@Service
public class GoodsTypeService {

    private final GoodsTypeRepository goodsTypes;

    public GoodsTypeService(GoodsTypeRepository goodsTypes) {
        this.goodsTypes = goodsTypes;
    }

    public List<GoodsType> list(boolean includeInactive) {
        return includeInactive ? goodsTypes.findAllByOrderByNameAsc()
                               : goodsTypes.findByActiveTrueOrderByNameAsc();
    }

    public GoodsType get(long id) {
        return goodsTypes.findById(id).orElseThrow(
                () -> new ApiException.NotFound("Goods type " + id + " not found."));
    }

    @Transactional
    public GoodsType create(String name) {
        String clean = Normalizer.clean(name);
        if (clean == null) {
            throw new FieldValidationException("name", "A goods type needs a name.");
        }
        goodsTypes.findByNameKey(clean.toLowerCase()).ifPresent(existing -> {
            throw new ApiException.Conflict("A goods type called " + existing.getName()
                    + " already exists"
                    + (existing.isActive() ? "." : ", retired. Restore it instead."));
        });
        return ConstraintErrors.translating(() -> goodsTypes.saveAndFlush(new GoodsType(clean)));
    }

    /**
     * Find-or-create on the lower-cased name.
     *
     * <p>Called while saving an LR, which is why it does not refuse a duplicate: the first load
     * of something new must not be blocked waiting for an administrator to add a master row.
     */
    @Transactional
    public GoodsType ensure(String name) {
        String clean = Normalizer.clean(name);
        if (clean == null) {
            return null;
        }
        return goodsTypes.findByNameKey(clean.toLowerCase())
                .orElseGet(() -> goodsTypes.save(new GoodsType(clean)));
    }

    @Transactional
    public GoodsType retire(long id) {
        GoodsType g = get(id);
        g.setActive(false);
        return g;
    }

    @Transactional
    public GoodsType restore(long id) {
        GoodsType g = get(id);
        g.setActive(true);
        return g;
    }
}
