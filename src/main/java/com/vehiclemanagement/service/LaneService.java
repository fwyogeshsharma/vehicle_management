package com.vehiclemanagement.service;

import com.vehiclemanagement.domain.City;
import com.vehiclemanagement.domain.FreightLane;
import com.vehiclemanagement.domain.GoodsType;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.ConstraintErrors;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.FreightLaneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * Lane intelligence: what a CSR hears is moving, and where.
 *
 * <p><b>Almost nothing is required.</b> Three fields — from, to, and what — and everything else
 * is optional, because this is written down during a call about something else. A form that
 * demanded a tonnage and a rate would be filled in with invented ones, and an invented number
 * outranks an honest blank in every report that follows.
 *
 * <p>Places and goods resolve against the masters when they can and stay free text when they
 * cannot, exactly as on a lorry receipt: loads leave factory gates that no city list contains,
 * and the first mention of a commodity nobody has carried must not be blocked on an
 * administrator.
 */
@Service
public class LaneService {

    private static final Logger log = LoggerFactory.getLogger(LaneService.class);

    private final FreightLaneRepository lanes;
    private final GeoService geo;
    private final GoodsTypeService goodsTypes;

    public LaneService(FreightLaneRepository lanes, GeoService geo, GoodsTypeService goodsTypes) {
        this.lanes = lanes;
        this.geo = geo;
        this.goodsTypes = goodsTypes;
    }

    /** Everything a caller may say about a lane. Only the first three matter. */
    public record Request(Long fromCityId, String fromPlace,
                          Long toCityId, String toPlace,
                          Long goodsTypeId, String goods,
                          String companyName, Boolean seasonal, String season, Long bodyTypeId,
                          String source, String sourceMobile, String notes) {
    }

    @Transactional(readOnly = true)
    public Page<FreightLane> list(String q, Boolean active, Long fromCityId, Long toCityId,
                                  Long goodsTypeId, Boolean seasonal, Pageable pageable) {
        String like = Normalizer.clean(q) == null ? null : "%" + q.trim() + "%";
        return lanes.search(like, active, fromCityId, toCityId, goodsTypeId, seasonal, pageable);
    }

    @Transactional(readOnly = true)
    public FreightLane get(long id) {
        return lanes.findById(id).orElseThrow(
                () -> new ApiException.NotFound("Lane " + id + " not found."));
    }

    @Transactional
    public FreightLane create(Request req, String actor) {
        FreightLane lane = new FreightLane(null, null, null);
        apply(lane, req);
        lane.setRecordedBy(actor);
        FreightLane saved = ConstraintErrors.translating(() -> lanes.saveAndFlush(lane));
        log.info("lane {} recorded by {}: {} -> {} ({})", saved.getId(), actor,
                saved.getFromPlace(), saved.getToPlace(), saved.getGoods());
        return saved;
    }

    @Transactional
    public FreightLane update(long id, Request req) {
        FreightLane lane = get(id);
        apply(lane, req);
        return ConstraintErrors.translating(() -> lanes.saveAndFlush(lane));
    }

    /**
     * Retire a lane. Never a delete.
     *
     * <p>A lane that turned out to be wrong, or dried up, is worth keeping: the next person to
     * suggest it should be able to see that it was tried. Deleting it guarantees the same
     * conversation happens again in a year.
     */
    @Transactional
    public FreightLane retire(long id) {
        FreightLane lane = get(id);
        lane.setActive(false);
        return lane;
    }

    @Transactional
    public FreightLane restore(long id) {
        FreightLane lane = get(id);
        lane.setActive(true);
        return lane;
    }

    private void apply(FreightLane lane, Request req) {
        City from = resolveCity(req.fromCityId(), req.fromPlace(), "from_place", "From");
        lane.setFromCityId(from == null ? null : from.getId());
        lane.setFromPlace(from == null ? Normalizer.clean(req.fromPlace()) : from.getName());

        City to = resolveCity(req.toCityId(), req.toPlace(), "to_place", "To");
        lane.setToCityId(to == null ? null : to.getId());
        lane.setToPlace(to == null ? Normalizer.clean(req.toPlace()) : to.getName());

        GoodsType type = req.goodsTypeId() != null
                ? goodsTypes.get(req.goodsTypeId())
                : goodsTypes.ensure(req.goods());
        if (type == null && Normalizer.clean(req.goods()) == null) {
            throw new FieldValidationException("goods",
                    "What moves on this lane? Pick from the list or type it.");
        }
        lane.setGoodsTypeId(type == null ? null : type.getId());
        String goods = Normalizer.clean(req.goods());
        lane.setGoods(goods != null ? goods : type.getName());

        lane.setCompanyName(Normalizer.clean(req.companyName()));
        lane.setSeasonal(Boolean.TRUE.equals(req.seasonal()));
        // The months are only meaningful when the flag is set. Clearing the text with the flag
        // keeps "runs all year, Apr-Jul" -- which is nonsense somebody would then act on --
        // out of the table.
        lane.setSeason(lane.isSeasonal() ? Normalizer.clean(req.season()) : null);
        lane.setBodyTypeId(req.bodyTypeId());
        lane.setSource(Normalizer.clean(req.source()));
        lane.setSourceMobile(Normalizer.optionalMobile(req.sourceMobile(), "source_mobile"));
        lane.setNotes(Normalizer.clean(req.notes()));
    }

    /**
     * A place, matched against the city list but not restricted to it — the same rule a lorry
     * receipt follows. "Kashipur industrial area" is a real origin and no city list has it.
     */
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
