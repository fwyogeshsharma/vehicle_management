package com.vehiclemanagement.web;

import com.vehiclemanagement.security.Principal;
import com.vehiclemanagement.service.LaneService;
import com.vehiclemanagement.web.dto.LaneDtos;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lane intelligence: what moves where, as reported to a CSR.
 *
 * <p><b>Not consignments.</b> Nothing here creates an obligation, names a customer or owes
 * money. {@code /api/lr} is the register of business won; this is the register of business
 * that exists and is currently going to somebody else.
 *
 * <p>There is no DELETE. A lane that turned out to be wrong is retired, because the next
 * person to suggest it should be able to see that it was tried.
 */
@RestController
@RequestMapping("/api/lanes")
public class LaneController {

    /** Sort keys become raw SQL column names, so nothing outside this list may reach the query. */
    private static final Sorts.Builder SORTS = Sorts
            .allowing("created_at", "created_at")
            .and("from_place", "from_place")
            .and("to_place", "to_place")
            .and("goods", "goods")
            .and("id", "id");

    private final LaneService lanes;

    public LaneController(LaneService lanes) {
        this.lanes = lanes;
    }

    @Operation(summary = "The lane register",
            description = "`q` matches the route, the commodity, the season and who told us — "
                    + "the text as recorded, so a lane still turns up under a place that was "
                    + "never in the city list.")
    @GetMapping
    public PageResponse<LaneDtos.Detail> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "from_city_id", required = false) Long fromCityId,
            @RequestParam(name = "to_city_id", required = false) Long toCityId,
            @RequestParam(name = "goods_type_id", required = false) Long goodsTypeId,
            @RequestParam(name = "seasonal", required = false) Boolean seasonal,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "sort", required = false) String sort) {
        Sort order = sort == null ? Sort.by(Sort.Direction.DESC, "created_at")
                                  : SORTS.resolve(sort);
        return PageResponse.of(
                lanes.list(q, active, fromCityId, toCityId, goodsTypeId, seasonal,
                        PageParams.of(page, pageSize, order)),
                LaneDtos.Detail::from);
    }

    @GetMapping("/{id}")
    public LaneDtos.Detail get(@PathVariable long id) {
        return LaneDtos.Detail.from(lanes.get(id));
    }

    @Operation(summary = "Record a lane",
            description = "Only from, to and what are required. Everything else is optional "
                    + "because this is written down during a call about something else, and a "
                    + "form that demands a tonnage gets an invented one.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LaneDtos.Detail create(@AuthenticationPrincipal Jwt jwt,
                                  @Valid @RequestBody LaneDtos.SaveRequest request) {
        return LaneDtos.Detail.from(lanes.create(request.toRequest(), Principal.username(jwt)));
    }

    @PutMapping("/{id}")
    public LaneDtos.Detail update(@PathVariable long id,
                                  @Valid @RequestBody LaneDtos.SaveRequest request) {
        return LaneDtos.Detail.from(lanes.update(id, request.toRequest()));
    }

    @Operation(summary = "Retire a lane",
            description = "It dried up, or it was wrong. Kept rather than deleted so the next "
                    + "person to suggest it can see it was tried.")
    @PostMapping("/{id}/retire")
    public LaneDtos.Detail retire(@PathVariable long id) {
        return LaneDtos.Detail.from(lanes.retire(id));
    }

    @PostMapping("/{id}/restore")
    public LaneDtos.Detail restore(@PathVariable long id) {
        return LaneDtos.Detail.from(lanes.restore(id));
    }
}
