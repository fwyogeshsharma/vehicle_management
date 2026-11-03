package com.vehiclemanagement.web;

import com.vehiclemanagement.security.Principal;
import com.vehiclemanagement.security.Roles;
import com.vehiclemanagement.service.LoadProviderContractService;
import com.vehiclemanagement.web.dto.LoadProviderContractDtos;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * The terms agreed with each load provider.
 *
 * <p>Guarded like lorry receipts, on the whole class: these are the operating firm's own
 * commercial terms, and a plain CSR reaching them through one endpoint somebody forgot to
 * annotate is the same leak as not guarding them at all.
 *
 * <p>There is no DELETE. Superseded terms are retired, because what was agreed before is the
 * record the next negotiation starts from.
 */
@RestController
@RequestMapping("/api/load-provider-contracts")
@PreAuthorize(Roles.LORRY_RECEIPTS)
public class LoadProviderContractController {

    /** Sort keys become raw SQL column names, so nothing outside this list may reach the query. */
    private static final Sorts.Builder SORTS = Sorts
            .allowing("created_at", "created_at")
            .and("company_name", "company_name")
            .and("location", "location")
            .and("price_approved", "price_approved")
            .and("final_price_for_truck", "final_price_for_truck")
            .and("per_tonne_price", "per_tonne_price")
            .and("id", "id");

    private final LoadProviderContractService contracts;

    public LoadProviderContractController(LoadProviderContractService contracts) {
        this.contracts = contracts;
    }

    @Operation(summary = "The contract register",
            description = "`q` matches the company and the location as recorded.")
    @GetMapping
    public PageResponse<LoadProviderContractDtos.Detail> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "sort", required = false) String sort) {
        Sort order = sort == null ? Sort.by(Sort.Direction.DESC, "created_at")
                                  : SORTS.resolve(sort);
        return PageResponse.of(
                contracts.list(q, active, PageParams.of(page, pageSize, order)),
                LoadProviderContractDtos.Detail::from);
    }

    @GetMapping("/{id}")
    public LoadProviderContractDtos.Detail get(@PathVariable long id) {
        return LoadProviderContractDtos.Detail.from(contracts.get(id));
    }

    @Operation(summary = "Record a contract",
            description = "Only the customer is required. Every figure is stored as entered; "
                    + "none is computed from the others.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LoadProviderContractDtos.Detail create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody LoadProviderContractDtos.SaveRequest request) {
        return LoadProviderContractDtos.Detail.from(
                contracts.create(request.toRequest(), Principal.username(jwt)));
    }

    @PutMapping("/{id}")
    public LoadProviderContractDtos.Detail update(
            @PathVariable long id,
            @Valid @RequestBody LoadProviderContractDtos.SaveRequest request) {
        return LoadProviderContractDtos.Detail.from(contracts.update(id, request.toRequest()));
    }

    @Operation(summary = "Retire a contract", description = "Kept, not deleted.")
    @PostMapping("/{id}/retire")
    public LoadProviderContractDtos.Detail retire(@PathVariable long id) {
        return LoadProviderContractDtos.Detail.from(contracts.retire(id));
    }

    @PostMapping("/{id}/restore")
    public LoadProviderContractDtos.Detail restore(@PathVariable long id) {
        return LoadProviderContractDtos.Detail.from(contracts.restore(id));
    }
}
