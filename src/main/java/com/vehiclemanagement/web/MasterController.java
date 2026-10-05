package com.vehiclemanagement.web;

import com.vehiclemanagement.service.BodyTypeService;
import com.vehiclemanagement.service.CapacityService;
import com.vehiclemanagement.service.GeoService;
import com.vehiclemanagement.service.GoodsTypeService;
import com.vehiclemanagement.web.dto.MasterDtos;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * All the pick lists in one call, so a form loads its dropdowns with a single request.
 *
 * <p>Read-only and active rows only. Maintenance stays on the per-list endpoints
 * ({@link GeoController}, {@link CustomerController}) where retiring and restoring live.
 */
@RestController
@RequestMapping("/api/masters")
public class MasterController {

    private final GeoService geo;
    private final CapacityService capacities;
    private final BodyTypeService bodyTypes;
    private final GoodsTypeService goodsTypes;

    public MasterController(GeoService geo, CapacityService capacities,
                            BodyTypeService bodyTypes, GoodsTypeService goodsTypes) {
        this.geo = geo;
        this.capacities = capacities;
        this.bodyTypes = bodyTypes;
        this.goodsTypes = goodsTypes;
    }

    @Operation(summary = "Every master list with ids",
            description = "States (each with its cities), capacities, body types and goods "
                    + "types. Active rows only. The ids are what the report and intake "
                    + "endpoints take as body_type_id, capacity_id and so on.")
    @GetMapping
    public MasterDtos.All all() {
        return MasterDtos.All.of(geo.listStates(), geo.listActiveCities(),
                capacities.list(false), bodyTypes.list(false), goodsTypes.list(false));
    }
}
