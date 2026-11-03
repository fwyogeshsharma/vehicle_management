package com.vehiclemanagement.web;

import com.vehiclemanagement.security.Principal;
import com.vehiclemanagement.security.Roles;
import com.vehiclemanagement.domain.Vehicle;
import com.vehiclemanagement.service.LoadRequestService;
import com.vehiclemanagement.service.VehicleService;
import com.vehiclemanagement.web.dto.VehicleDtos;
import com.vehiclemanagement.web.dto.LoadRequestDtos;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * The day's load requests: cargo customers have asked us to move.
 *
 * <p>Guarded like lorry receipts and contracts, on the whole class: these are the operating
 * firm's own customer business, and a plain CSR reaching them through one endpoint somebody
 * forgot to annotate is the same leak as not guarding them at all.
 *
 * <p>There is no DELETE. A request that fell through is {@code CANCELLED}, which keeps the fact
 * that the customer asked.
 */
@RestController
@RequestMapping("/api/load-requests")
@PreAuthorize(Roles.LORRY_RECEIPTS)
public class LoadRequestController {

    /** Sort keys become raw SQL column names, so nothing outside this list may reach the query. */
    private static final Sorts.Builder SORTS = Sorts
            .allowing("request_date", "request_date")
            .and("created_at", "created_at")
            .and("company_name", "company_name")
            .and("from_place", "from_place")
            .and("to_place", "to_place")
            .and("loading_date", "loading_date")
            .and("id", "id");

    private final LoadRequestService requests;
    private final VehicleService vehicles;

    public LoadRequestController(LoadRequestService requests, VehicleService vehicles) {
        this.requests = requests;
        this.vehicles = vehicles;
    }

    @Operation(summary = "The load request board",
            description = "`date_from` / `date_to` are inclusive (`YYYY-MM-DD`); the same day in "
                    + "both is one day's board. `q` matches the company, places, goods and "
                    + "contact as recorded. `status` is OPEN, FULFILLED or CANCELLED.")
    @GetMapping
    public PageResponse<LoadRequestDtos.Detail> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "consignor_id", required = false) Long consignorId,
            @RequestParam(name = "date_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(name = "date_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "sort", required = false) String sort) {
        Sort order = sort == null
                ? Sort.by(Sort.Direction.DESC, "request_date").and(
                        Sort.by(Sort.Direction.DESC, "created_at"))
                : SORTS.resolve(sort);
        return PageResponse.of(
                requests.list(q, status, consignorId, dateFrom, dateTo,
                        PageParams.of(page, pageSize, order)),
                LoadRequestDtos.Detail::from);
    }

    @Operation(summary = "Vehicles that run where this request wants to go",
            description = "Active vehicles serving the request's pickup and drop cities, by the "
                    + "Vehicles tab's own rule (a vehicle, or its company, names the city or the "
                    + "whole state). `match=both` (default) wants a vehicle serving both places; "
                    + "`match=either` widens it. A place that is not on the city list cannot be "
                    + "matched; `from_matchable` / `to_matchable` say so.")
    @GetMapping("/{id}/vehicles")
    public LoadRequestDtos.VehicleMatch vehicles(
            @PathVariable long id,
            @RequestParam(name = "match", required = false, defaultValue = "both") String match) {
        boolean either = "either".equalsIgnoreCase(match);
        var found = requests.matchingVehicles(id, either);
        var ids = found.vehicles().stream().map(Vehicle::getId).toList();
        var contacts = vehicles.contactsFor(ids);
        var places = vehicles.locationsFor(ids);
        return new LoadRequestDtos.VehicleMatch(either ? "either" : "both",
                found.fromKnown(), found.toKnown(),
                found.vehicles().stream()
                        .map(v -> VehicleDtos.Summary.from(
                                v, contacts.get(v.getId()), places.get(v.getId())))
                        .toList());
    }

    @GetMapping("/{id}")
    public LoadRequestDtos.Detail get(@PathVariable long id) {
        return LoadRequestDtos.Detail.from(requests.get(id));
    }

    @Operation(summary = "Record a load request",
            description = "Required: the customer (`consignor_id`, from the master) and the "
                    + "route (`from_place`, `to_place`). The date defaults to today and the "
                    + "status to OPEN. Everything else is optional.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LoadRequestDtos.Detail create(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody LoadRequestDtos.SaveRequest request) {
        return LoadRequestDtos.Detail.from(
                requests.create(request.toRequest(), Principal.username(jwt)));
    }

    @Operation(summary = "Correct a load request, or change its status",
            description = "A full replacement of the fields; a status left out keeps the "
                    + "current one.")
    @PutMapping("/{id}")
    public LoadRequestDtos.Detail update(@PathVariable long id,
                                         @Valid @RequestBody LoadRequestDtos.SaveRequest request) {
        return LoadRequestDtos.Detail.from(requests.update(id, request.toRequest()));
    }
}
