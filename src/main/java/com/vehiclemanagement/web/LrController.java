package com.vehiclemanagement.web;

import com.vehiclemanagement.domain.LorryReceipt;
import com.vehiclemanagement.domain.LrStatus;
import com.vehiclemanagement.security.Principal;
import com.vehiclemanagement.security.Roles;
import com.vehiclemanagement.service.LrPdf;
import com.vehiclemanagement.service.LrService;
import com.vehiclemanagement.web.dto.LrDtos;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

import java.time.LocalDate;

/**
 * Lorry receipts: the consignment notes issued against loads.
 *
 * <p><b>There is no DELETE.</b> tts has one; a receipt here is cancelled instead, because the
 * customer holds a copy and the number series is meant to be continuous. See
 * {@code LrService.cancel}.
 */
@RestController
@RequestMapping("/api/lr")
// Every route, not route by route: a receipt reachable by a plain CSR through one endpoint
// that somebody forgot to annotate is the same leak as not guarding it at all. The download
// is included -- a PDF of a consignment is the consignment.
@PreAuthorize(Roles.LORRY_RECEIPTS)
public class LrController {

    /** Sort keys become raw SQL column names, so nothing outside this list may reach the query. */
    private static final Sorts.Builder SORTS = Sorts
            .allowing("lr_date", "lr_date")
            .and("seq_no", "seq_no")
            .and("balance", "balance")
            .and("total_charges", "total_charges")
            .and("created_at", "created_at")
            .and("id", "id");

    private final LrService lrs;
    private final LrPdf pdf;

    public LrController(LrService lrs, LrPdf pdf) {
        this.lrs = lrs;
        this.pdf = pdf;
    }

    @Operation(summary = "The register",
            description = "`q` matches the number and the printed text — consignor, consignee, "
                    + "truck, driver, places — not the master rows, so a receipt still turns up "
                    + "under the name it was issued in after the customer is renamed.")
    @GetMapping
    public PageResponse<LrDtos.Detail> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) LrStatus status,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "vehicle_id", required = false) Long vehicleId,
            @RequestParam(name = "driver_id", required = false) Long driverId,
            @RequestParam(name = "party_id", required = false) Long partyId,
            @RequestParam(name = "unpaid", required = false) Boolean unpaid,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "sort", required = false) String sort) {
        Sort order = sort == null ? Sort.by(Sort.Direction.DESC, "lr_date")
                                          .and(Sort.by(Sort.Direction.DESC, "seq_no"))
                                  : SORTS.resolve(sort);
        return PageResponse.of(
                lrs.list(q, status, from, to, vehicleId, driverId, partyId, unpaid,
                        PageParams.of(page, pageSize, order)),
                LrDtos.Detail::from);
    }

    @GetMapping("/totals")
    public LrDtos.Totals totals() {
        return LrDtos.Totals.from(lrs.totals());
    }

    @Operation(summary = "What the next receipt will be numbered",
            description = "A preview, not a reservation. Two clerks opening the form together "
                    + "see the same number; the one who saves first gets it.")
    @GetMapping("/next-number")
    public LrDtos.NextNumber nextNumber() {
        return new LrDtos.NextNumber(lrs.nextNumber());
    }

    @GetMapping("/{id}")
    public LrDtos.Detail get(@PathVariable long id) {
        return LrDtos.Detail.from(lrs.get(id));
    }

    @Operation(summary = "Write a receipt",
            description = "Each master may be given as an id picked from a list or as a typed "
                    + "name. A name that is not on file creates the customer or goods type; a "
                    + "place that matches no city is kept as free text, because loads are "
                    + "picked up at factory gates that no city list contains.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LrDtos.Detail create(@AuthenticationPrincipal Jwt jwt,
                                @Valid @RequestBody LrDtos.SaveRequest request) {
        return LrDtos.Detail.from(lrs.create(request.toRequest(), Principal.username(jwt)));
    }

    @Operation(summary = "Download a receipt as PDF",
            description = "Two copies on one A4 sheet, consignor and transporter, drawn from "
                    + "the receipt's own stored text rather than from the masters it links to "
                    + "— so a reprint of an old receipt says what the customer's copy says.")
    // No `produces` here, deliberately. Pinning the mapping to application/pdf pins content
    // negotiation with it, so when this route REFUSES -- a 403 from the class guard, or a 404
    // for an unknown id -- the exception handler's JSON body cannot be written and the client
    // gets a 500 instead. A guarded endpoint that reports 500 looks broken rather than
    // guarded, which is the same failure GlobalExceptionHandler exists to avoid. The success
    // path still sets the content type explicitly below.
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        LorryReceipt receipt = lrs.get(id);
        byte[] bytes = pdf.render(receipt);
        // `attachment` is what makes it a download rather than a tab, and the filename is the
        // LR number so a folder of these is sorted and searchable without opening them.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(receipt.getLrNumber() + ".pdf")
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    @Operation(summary = "Correct a receipt",
            description = "Allowed in any state — a wrong weight is usually found when the "
                    + "paperwork is reconciled, which is after delivery. What an edit may NOT "
                    + "do is move the lifecycle backwards; that is still a 409.")
    @PutMapping("/{id}")
    public LrDtos.Detail update(@PathVariable long id,
                                @Valid @RequestBody LrDtos.SaveRequest request) {
        return LrDtos.Detail.from(lrs.update(id, request.toRequest()));
    }

    @Operation(summary = "Move it along",
            description = "booked → in transit → delivered, and either open state → cancelled. "
                    + "Delivered and cancelled are terminal; going back is a 409.")
    @PostMapping("/{id}/status")
    public LrDtos.Detail changeStatus(@PathVariable long id,
                                      @Valid @RequestBody LrDtos.StatusRequest request) {
        return LrDtos.Detail.from(lrs.changeStatus(id, request.status(), request.reason()));
    }

    @Operation(summary = "Call it off",
            description = "The row is kept and the number stays used — the series has to be "
                    + "continuous and the customer holds a copy.")
    @PostMapping("/{id}/cancel")
    public LrDtos.Detail cancel(@PathVariable long id,
                                @RequestBody(required = false) LrDtos.CancelRequest request) {
        return LrDtos.Detail.from(lrs.cancel(id, request == null ? null : request.reason()));
    }
}
