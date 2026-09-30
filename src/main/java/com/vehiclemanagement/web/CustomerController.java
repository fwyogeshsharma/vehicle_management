package com.vehiclemanagement.web;

import com.vehiclemanagement.service.ConsignorService;
import com.vehiclemanagement.service.GoodsTypeService;
import com.vehiclemanagement.web.dto.LrDtos;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
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

/**
 * The two masters lorry receipts introduced: customers, and what gets carried.
 *
 * <p>Both live here rather than in their own controllers because neither is a subject in its
 * own right — they exist to fill two boxes on a receipt, and splitting them into two files
 * with four routes each would be filing, not design.
 *
 * <p><b>Customers are not companies.</b> {@code /api/companies} is transport companies that own
 * vehicles and employ drivers; these are the firms that send and receive goods.
 */
@RestController
@RequestMapping("/api")
public class CustomerController {

    private static final Sorts.Builder SORTS = Sorts
            .allowing("name", "name")
            .and("created_at", "created_at")
            .and("id", "id");

    private final ConsignorService customers;
    private final GoodsTypeService goodsTypes;

    public CustomerController(ConsignorService customers, GoodsTypeService goodsTypes) {
        this.customers = customers;
        this.goodsTypes = goodsTypes;
    }

    // ── customers ───────────────────────────────────────────────────────────────

    @GetMapping("/customers")
    public PageResponse<LrDtos.Customer> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "sort", required = false) String sort) {
        return PageResponse.of(
                customers.list(q, active, PageParams.of(page, pageSize, SORTS.resolve(sort))),
                LrDtos.Customer::from);
    }

    @GetMapping("/customers/{id}")
    public LrDtos.Customer get(@PathVariable long id) {
        return LrDtos.Customer.from(customers.get(id));
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public LrDtos.Customer create(@Valid @RequestBody LrDtos.CustomerRequest request) {
        return LrDtos.Customer.from(customers.create(
                request.name(), request.mobile(), request.address(), request.gstin()));
    }

    @Operation(summary = "Correct a customer",
            description = "Renaming does NOT rewrite the receipts already issued to them. Their "
                    + "consignor_name is what was printed and agreed.")
    @PutMapping("/customers/{id}")
    public LrDtos.Customer update(@PathVariable long id,
                                  @Valid @RequestBody LrDtos.CustomerRequest request) {
        return LrDtos.Customer.from(customers.update(
                id, request.name(), request.mobile(), request.address(), request.gstin()));
    }

    @Operation(summary = "Retire a customer",
            description = "Not a delete: receipts point here ON DELETE SET NULL, so removing "
                    + "the row would quietly unlink every receipt ever issued to them.")
    @PostMapping("/customers/{id}/retire")
    public LrDtos.Customer retire(@PathVariable long id) {
        return LrDtos.Customer.from(customers.retire(id));
    }

    @PostMapping("/customers/{id}/restore")
    public LrDtos.Customer restore(@PathVariable long id) {
        return LrDtos.Customer.from(customers.restore(id));
    }

    // ── goods types ─────────────────────────────────────────────────────────────

    @GetMapping("/goods-types")
    public List<LrDtos.Goods> goods(
            @RequestParam(name = "include_inactive", required = false) Boolean includeInactive) {
        return goodsTypes.list(Boolean.TRUE.equals(includeInactive)).stream()
                .map(LrDtos.Goods::from).toList();
    }

    @PostMapping("/goods-types")
    @ResponseStatus(HttpStatus.CREATED)
    public LrDtos.Goods createGoods(@Valid @RequestBody LrDtos.NameRequest request) {
        return LrDtos.Goods.from(goodsTypes.create(request.name()));
    }

    @PostMapping("/goods-types/{id}/retire")
    public LrDtos.Goods retireGoods(@PathVariable long id) {
        return LrDtos.Goods.from(goodsTypes.retire(id));
    }

    @PostMapping("/goods-types/{id}/restore")
    public LrDtos.Goods restoreGoods(@PathVariable long id) {
        return LrDtos.Goods.from(goodsTypes.restore(id));
    }
}
