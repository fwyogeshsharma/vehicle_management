package com.vehiclemanagement.web;

import com.vehiclemanagement.service.CompanyService;
import com.vehiclemanagement.web.dto.CompanyDtos;
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

import java.util.List;

/** Transport companies: who they employ, what they own, and where they run. */
@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    /** Sort keys become raw SQL column names, so nothing outside this list may reach the query. */
    private static final Sorts.Builder SORTS = Sorts
            .allowing("name", "name")
            .and("created_at", "created_at")
            .and("id", "id");

    private final CompanyService companies;

    public CompanyController(CompanyService companies) {
        this.companies = companies;
    }

    @GetMapping
    public PageResponse<CompanyDtos.Summary> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "sort", required = false) String sort) {
        Sort order = SORTS.resolve(sort);
        var found = companies.list(q, active, PageParams.of(page, pageSize, order));
        // One extra query for the whole page, not one per row.
        var places = companies.locationsFor(
                found.getContent().stream().map(com.vehiclemanagement.domain.Company::getId).toList());
        return PageResponse.of(found, c -> CompanyDtos.Summary.from(c, places.get(c.getId())));
    }

    @GetMapping("/{id}")
    public CompanyDtos.Detail get(@PathVariable long id) {
        return CompanyDtos.Detail.from(companies.get(id));
    }

    @Operation(summary = "Add a company",
            description = "`places` is optional and says where it operates. Every vehicle it "
                    + "owns inherits these, so a company created without any serves nowhere "
                    + "until they are set.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyDtos.Detail create(@Valid @RequestBody CompanyDtos.CreateRequest request) {
        return CompanyDtos.Detail.from(companies.create(request.name(),
                request.places() == null
                        ? List.of()
                        : request.places().stream().map(VehicleDtos.Place::toRef).toList()));
    }

    @PutMapping("/{id}")
    public CompanyDtos.Detail update(@PathVariable long id,
                                     @Valid @RequestBody CompanyDtos.UpdateRequest request) {
        return CompanyDtos.Detail.from(companies.update(id, request.name(), request.mobile(),
                request.email(), request.gstin(), request.address(),
                request.headOfficeCityId()));
    }

    @Operation(summary = "Retire a company",
            description = "**Deactivates rather than deletes.** Its vehicles reference it ON "
                    + "DELETE RESTRICT, so a hard delete of a company that still owns a fleet is "
                    + "refused by the database — a fleet does not become ownerless because "
                    + "someone tidied the directory.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable long id) {
        companies.deactivate(id);
    }

    @PostMapping("/{id}/restore")
    public CompanyDtos.Detail restore(@PathVariable long id) {
        return CompanyDtos.Detail.from(companies.activate(id));
    }

    @Operation(summary = "Where this company operates",
            description = "Its vehicles inherit these. A null `city_id` means the whole state.")
    @GetMapping("/{id}/locations")
    public List<CompanyDtos.Location> locations(@PathVariable long id) {
        return companies.locationsOf(id).stream().map(CompanyDtos.Location::from).toList();
    }

    @Operation(summary = "Set where this company operates",
            description = "Replaces the whole set. An empty list means it serves **nowhere** — "
                    + "its vehicles then match no city, and do not fall back to preferences of "
                    + "their own.")
    @PutMapping("/{id}/locations")
    public List<CompanyDtos.Location> setLocations(
            @PathVariable long id, @Valid @RequestBody CompanyDtos.LocationsRequest request) {
        return companies.setLocations(id,
                        request.places().stream().map(VehicleDtos.Place::toRef).toList())
                .stream().map(CompanyDtos.Location::from).toList();
    }

    @Operation(summary = "Who is on this company's books",
            description = "And therefore who may be assigned to its trucks.")
    @GetMapping("/{id}/members")
    public List<CompanyDtos.Member> members(@PathVariable long id) {
        return companies.members(id).stream().map(CompanyDtos.Member::from).toList();
    }

    @GetMapping("/{id}/vehicles")
    public List<VehicleDtos.Summary> vehicles(@PathVariable long id) {
        return companies.vehiclesOf(id).stream().map(VehicleDtos.Summary::from).toList();
    }
}
