package com.vehiclemanagement.web;

import com.vehiclemanagement.domain.Vehicle;
import com.vehiclemanagement.service.VehicleService;
import com.vehiclemanagement.web.dto.VehicleDtos;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * The fleet.
 *
 * <p>Three separate facts, three separate endpoints, on purpose: what the truck <em>is</em>
 * ({@code PUT /{id}}), who <em>owns</em> it ({@code PUT /{id}/owner}), and who <em>drives</em>
 * it ({@code /{id}/drivers}). Folding them into one body would let a typo in the capacity field
 * sell the vehicle.
 */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    /** Sort keys become raw SQL column names, so nothing outside this list may reach the query. */
    private static final Sorts.Builder SORTS = Sorts
            .allowing("registration_number", "registration_number")
            .and("capacity_tons", "capacity_tons")
            .and("created_at", "created_at")
            .and("id", "id");

    private final VehicleService vehicles;

    public VehicleController(VehicleService vehicles) {
        this.vehicles = vehicles;
    }

    @Operation(summary = "List vehicles",
            description = "`serving_city_id` answers \"which trucks run to this city?\" — a "
                    + "whole-state preference matches every city in that state. It is a filter "
                    + "here rather than its own endpoint so it pages like everything else. "
                    + "The filters are independent: `serving_city_id` does **not** imply "
                    + "`active=true`. To ask what can actually be dispatched, send both.")
    @GetMapping
    public PageResponse<VehicleDtos.Summary> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "company_id", required = false) Long companyId,
            @RequestParam(name = "owner_user_id", required = false) Long ownerUserId,
            @RequestParam(name = "body_type_id", required = false) Long bodyTypeId,
            @RequestParam(name = "min_tons", required = false) BigDecimal minTons,
            @RequestParam(name = "contact", required = false) String contact,
            @RequestParam(name = "owned", required = false) String owned,
            @RequestParam(name = "serving_city_id", required = false) Long servingCityId,
            @RequestParam(name = "serving_state_id", required = false) Long servingStateId,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "sort", required = false) String sort) {
        Sort order = SORTS.resolve(sort);
        var found = vehicles.list(q, contact, owned, companyId, ownerUserId, bodyTypeId,
                minTons, servingCityId, servingStateId, active,
                PageParams.of(page, pageSize, order));
        // Two extra queries for the whole page, not two per row.
        var ids = found.getContent().stream().map(Vehicle::getId).toList();
        var contacts = vehicles.contactsFor(ids);
        var places = vehicles.locationsFor(ids);
        return PageResponse.of(found, v -> VehicleDtos.Summary.from(
                v, contacts.get(v.getId()), places.get(v.getId())));
    }

    @GetMapping("/{id}")
    public VehicleDtos.Detail get(@PathVariable long id) {
        return VehicleDtos.Detail.from(vehicles.get(id));
    }

    @Operation(summary = "Register a vehicle against existing records",
            description = "Exactly one of `owner_company_id` / `owner_user_id`, both of which "
                    + "must already exist. Set `owner_also_drives` for an owner-operator — "
                    + "someone who owns the truck and drives it — which records both facts. "
                    + "To register one against a driver or company that is NOT yet on file, use "
                    + "POST /api/vehicles/intake.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleDtos.Detail create(@Valid @RequestBody VehicleDtos.CreateRequest request) {
        return VehicleDtos.Detail.from(vehicles.create(
                request.registrationNumber(), request.bodyTypeId(),
                request.ownerCompanyId(), request.ownerUserId(),
                request.noOfAxles(), request.noOfWheels(), request.capacity(),
                request.lengthFt(), request.ownerAlsoDrives(), places(request.places())));
    }

    @Operation(summary = "Register a vehicle, creating its driver and company as needed",
            description = "For the desk: a plate, a driver's name and mobile, and a company "
                    + "name. The driver is matched on the mobile and the company on its name, "
                    + "and either is created if new — then the driver is put on the company's "
                    + "books and assigned to the truck, in that order, because the database "
                    + "refuses the assignment otherwise. All of it in one transaction. "
                    + "Omit `company_name` and the driver owns the vehicle: the owner-operator. "
                    + "This is the one way a non-administrator creates a user record. "
                    + "`places` says where the truck runs. With a company it sets the "
                    + "**company's** locations, which all its trucks inherit; without one it "
                    + "sets the vehicle's own.")
    @PostMapping("/intake")
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleDtos.Detail intake(@Valid @RequestBody VehicleDtos.IntakeRequest request) {
        return VehicleDtos.Detail.from(vehicles.intake(
                request.registrationNumber(), request.bodyTypeId(),
                new VehicleService.Contacts(request.driverName(), request.driverMobile(),
                        request.driverAltMobile(), request.companyName(),
                        request.companyMobile()),
                request.noOfAxles(), request.noOfWheels(), request.capacity(),
                request.lengthFt(), places(request.places())));
    }

    /** A null list and an empty one mean the same thing here: no locations given. */
    private static List<VehicleService.CityRef> places(List<VehicleDtos.Place> places) {
        return places == null ? List.of() : places.stream().map(VehicleDtos.Place::toRef).toList();
    }

    @Operation(summary = "Edit a vehicle's own attributes",
            description = "Not its owner and not its drivers. `capacity_tons` is derived by the "
                    + "database from `capacity` and cannot be set.")
    @PutMapping("/{id}")
    public VehicleDtos.Detail update(@PathVariable long id,
                                     @Valid @RequestBody VehicleDtos.UpdateRequest request) {
        return VehicleDtos.Detail.from(vehicles.update(id, request.bodyTypeId(),
                request.noOfAxles(), request.noOfWheels(), request.capacity(),
                request.lengthFt(), request.notes()));
    }

    @Operation(summary = "Take a vehicle off the road",
            description = "**Deactivates rather than deletes** — the row and its history stay. "
                    + "Filter with `active=false` to find retired vehicles, or restore one with "
                    + "POST /{id}/restore.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable long id) {
        vehicles.deactivate(id);
    }

    @PostMapping("/{id}/restore")
    public VehicleDtos.Detail restore(@PathVariable long id) {
        return VehicleDtos.Detail.from(vehicles.activate(id));
    }

    @Operation(summary = "Sell a vehicle",
            description = "**Removes every driver assignment**, and the count comes back as "
                    + "`removed_drivers`. A truck sold by one company must not keep listing that "
                    + "company's drivers. Its own preferred locations go too when a company "
                    + "takes over, because the company's locations then apply.")
    @PutMapping("/{id}/owner")
    public VehicleDtos.OwnerChanged setOwner(@PathVariable long id,
                                             @Valid @RequestBody VehicleDtos.OwnerRequest request) {
        return VehicleDtos.OwnerChanged.from(vehicles.changeOwner(
                id, request.companyId(), request.userId(), request.ownerAlsoDrives()));
    }

    @GetMapping("/{id}/drivers")
    public List<VehicleDtos.Driver> drivers(@PathVariable long id) {
        return vehicles.driversOf(id).stream().map(VehicleDtos.Driver::from).toList();
    }

    @Operation(summary = "Assign a driver",
            description = "For a company-owned vehicle the person **must already be on that "
                    + "company's books**, or this is a 409. An owner-operator's truck has no "
                    + "such rule. Idempotent: assigning someone already assigned updates their "
                    + "row rather than conflicting.")
    @PostMapping("/{id}/drivers")
    @ResponseStatus(HttpStatus.CREATED)
    public List<VehicleDtos.Driver> addDriver(
            @PathVariable long id, @Valid @RequestBody VehicleDtos.DriverRequest request) {
        vehicles.addDriver(id, request.userId(), request.primary());
        return drivers(id);
    }

    @PutMapping("/{id}/drivers/{userId}/primary")
    public List<VehicleDtos.Driver> setPrimaryDriver(@PathVariable long id,
                                                     @PathVariable long userId) {
        vehicles.setPrimaryDriver(id, userId);
        return drivers(id);
    }

    @DeleteMapping("/{id}/drivers/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDriver(@PathVariable long id, @PathVariable long userId) {
        vehicles.removeDriver(id, userId);
    }

    @Operation(summary = "Where this vehicle runs",
            description = "The **effective** locations: its company's if a company owns it, "
                    + "otherwise its own. `source` says which. A company with no locations means "
                    + "the vehicle serves nowhere — it does not fall back.")
    @GetMapping("/{id}/locations")
    public List<VehicleDtos.Location> locations(@PathVariable long id) {
        return vehicles.effectiveLocations(id).stream().map(VehicleDtos.Location::from).toList();
    }

    @Operation(summary = "Set a vehicle's own preferred locations",
            description = "Replaces the whole set. **409 for a company-owned vehicle** — its "
                    + "company's locations apply, and a row here would be a second answer to one "
                    + "question. Set them on the company instead.")
    @PutMapping("/{id}/locations")
    public List<VehicleDtos.Location> setLocations(
            @PathVariable long id, @Valid @RequestBody VehicleDtos.LocationsRequest request) {
        vehicles.setPreferredLocations(id,
                request.places().stream().map(VehicleDtos.Place::toRef).toList());
        return locations(id);
    }
}
