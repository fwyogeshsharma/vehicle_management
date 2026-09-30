package com.vehiclemanagement.web;

import com.vehiclemanagement.service.BodyTypeService;
import com.vehiclemanagement.service.GeoService;
import com.vehiclemanagement.service.CapacityService;
import com.vehiclemanagement.web.dto.GeoDtos;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reference data: the states, cities and body types the forms are built out of.
 *
 * <p>States and cities are seeded at start-up from {@code data/india_states_cities.txt} and are
 * read-only here — extending that file and restarting is how new ones arrive, so there is no
 * ledger to repair.
 */
@RestController
@RequestMapping("/api")
public class GeoController {

    private static final Sorts.Builder CITY_SORTS = Sorts
            .allowing("name", "name")
            .and("id", "id");

    private final GeoService geo;
    private final BodyTypeService bodyTypes;

    private final CapacityService capacities;

    public GeoController(GeoService geo, BodyTypeService bodyTypes, CapacityService capacities) {
        this.geo = geo;
        this.bodyTypes = bodyTypes;
        this.capacities = capacities;
    }

    @GetMapping("/states")
    public List<GeoDtos.StateDto> states() {
        return geo.listStates().stream().map(GeoDtos.StateDto::from).toList();
    }

    @GetMapping("/states/{id}/cities")
    public List<GeoDtos.CityDto> citiesOfState(@PathVariable long id) {
        geo.requireState(id);
        return geo.listCities(id).stream().map(GeoDtos.CityDto::from).toList();
    }

    @Operation(summary = "Search cities",
            description = "Type-ahead across all states, or one. Several states have a city of "
                    + "the same name, so results carry their state and the caller must not "
                    + "assume a name is unique.")
    @GetMapping("/cities")
    public PageResponse<GeoDtos.CityDto> cities(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "state_id", required = false) Long stateId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "sort", required = false) String sort) {
        Sort order = CITY_SORTS.resolve(sort);
        return PageResponse.of(geo.searchCities(q, stateId, PageParams.of(page, pageSize, order)),
                GeoDtos.CityDto::from);
    }

    @Operation(summary = "The capacity pick list",
            description = "What a capacity dropdown should offer, smallest first. This is a "
                    + "PICK LIST and not a foreign key: `vehicles.capacity` is free text, so an "
                    + "import may still carry a value that is not on this list.")
    @GetMapping("/capacities")
    public List<GeoDtos.CapacityDto> capacities(
            @RequestParam(name = "include_inactive", defaultValue = "false")
            boolean includeInactive) {
        return capacities.list(includeInactive).stream()
                .map(GeoDtos.CapacityDto::from).toList();
    }

    @Operation(summary = "Add a capacity to the pick list",
            description = "`tons` is what makes the list sort sensibly — alphabetically "
                    + "\"9 Ton\" falls between 16 and 25. It is NOT what vehicles.capacity_tons "
                    + "is derived from; that is still the free text on the vehicle.")
    @PostMapping("/capacities")
    @ResponseStatus(HttpStatus.CREATED)
    public GeoDtos.CapacityDto createCapacity(
            @Valid @RequestBody GeoDtos.CapacityRequest request) {
        return GeoDtos.CapacityDto.from(capacities.create(request.label(), request.tons()));
    }

    @DeleteMapping("/capacities/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retireCapacity(@PathVariable long id) {
        capacities.retire(id);
    }

    @PostMapping("/capacities/{id}/restore")
    public GeoDtos.CapacityDto restoreCapacity(@PathVariable long id) {
        return GeoDtos.CapacityDto.from(capacities.restore(id));
    }

    @Operation(summary = "Add a city to a state",
            description = "The seed covers 3,285 places and still will not contain every "
                    + "industrial township somebody dispatches from. States themselves are not "
                    + "editable — there are 36 and a new one is a constitutional event.")
    @PostMapping("/states/{stateId}/cities")
    @ResponseStatus(HttpStatus.CREATED)
    public GeoDtos.CityDto createCity(@PathVariable long stateId,
                                      @Valid @RequestBody GeoDtos.CityRequest request) {
        return GeoDtos.CityDto.from(geo.createCity(stateId, request.name()));
    }

    @Operation(summary = "Retire a city",
            description = "**Retires rather than deletes** — locations reference it, and lorry "
                    + "receipts point at it ON DELETE SET NULL, so a delete would strip the "
                    + "link off historic receipts.")
    @DeleteMapping("/cities/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retireCity(@PathVariable long id) {
        geo.retireCity(id);
    }

    @PostMapping("/cities/{id}/restore")
    public GeoDtos.CityDto restoreCity(@PathVariable long id) {
        return GeoDtos.CityDto.from(geo.restoreCity(id));
    }

    @GetMapping("/body-types")
    public List<GeoDtos.BodyTypeDto> bodyTypes(
            @RequestParam(name = "include_inactive", defaultValue = "false")
            boolean includeInactive) {
        return bodyTypes.list(includeInactive).stream().map(GeoDtos.BodyTypeDto::from).toList();
    }

    @PostMapping("/body-types")
    @ResponseStatus(HttpStatus.CREATED)
    public GeoDtos.BodyTypeDto createBodyType(
            @Valid @RequestBody GeoDtos.BodyTypeRequest request) {
        return GeoDtos.BodyTypeDto.from(bodyTypes.create(request.name()));
    }

    @Operation(summary = "Retire a body type",
            description = "**Retires rather than deletes** — vehicles reference it ON DELETE "
                    + "RESTRICT. Existing vehicles keep it; no new vehicle may be given one.")
    @DeleteMapping("/body-types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retireBodyType(@PathVariable long id) {
        bodyTypes.retire(id);
    }

    @PostMapping("/body-types/{id}/restore")
    public GeoDtos.BodyTypeDto restoreBodyType(@PathVariable long id) {
        return GeoDtos.BodyTypeDto.from(bodyTypes.restore(id));
    }
}
