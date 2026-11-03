package com.vehiclemanagement.web;

import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.VehicleIntake;
import com.vehiclemanagement.service.BodyTypeService;
import com.vehiclemanagement.service.CapacityService;
import com.vehiclemanagement.service.GeoService;
import com.vehiclemanagement.service.GoodsTypeService;
import com.vehiclemanagement.service.IntakeService;
import com.vehiclemanagement.service.UploadLinkService;
import com.vehiclemanagement.web.dto.IntakeDtos;
import com.vehiclemanagement.web.dto.MasterDtos;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Photo intake without signing in, through a personal secret link.
 *
 * <p>The only unauthenticated write in the API, so it is deliberately small:
 * <ul>
 *   <li><b>It can do one thing</b> -- add a {@code vehicle_intake} row. No reading, no listing,
 *       nothing that touches an existing record.</li>
 *   <li><b>The link names an account, and the row is that account's.</b> {@code uploaded_by},
 *       {@code reported_by} and {@code reporter_mobile} come from the user the token resolves
 *       to, never from the request, so one person's link cannot file under another's name.</li>
 *   <li><b>The account is checked on every request</b>, so deactivating the person ends the
 *       link's use immediately -- the rule the anonymous routes were removed to keep.</li>
 *   <li>An hourly allowance per person (see {@link UploadLinkService}) bounds a leaked link.</li>
 * </ul>
 *
 * <p>Reuses {@link IntakeService#upload} unchanged, so a row from here is indistinguishable from
 * one sent by the app: same validation, same storage, same OCR queue.
 */
@RestController
@RequestMapping("/api/public/upload/{token}")
public class PublicUploadController {

    /** Who the page will say it is sending as. Nothing else about the account. */
    public record Sender(String name) {
    }

    private final UploadLinkService links;
    private final IntakeService intake;
    private final GeoService geo;
    private final CapacityService capacities;
    private final BodyTypeService bodyTypes;
    private final GoodsTypeService goodsTypes;

    public PublicUploadController(UploadLinkService links, IntakeService intake, GeoService geo,
                                  CapacityService capacities, BodyTypeService bodyTypes,
                                  GoodsTypeService goodsTypes) {
        this.links = links;
        this.intake = intake;
        this.geo = geo;
        this.capacities = capacities;
        this.bodyTypes = bodyTypes;
        this.goodsTypes = goodsTypes;
    }

    @Operation(summary = "Check an upload link",
            description = "Public. 404 if the link was never issued, was replaced or revoked, or "
                    + "its owner is deactivated -- one answer for all four.")
    @GetMapping
    public Sender check(@PathVariable String token) {
        return new Sender(links.resolve(token).getName());
    }

    @Operation(summary = "The pick lists the page needs",
            description = "Public, behind the same link check. The cities for the location "
                    + "type-ahead and the capacities, in the shape of `GET /api/masters` -- "
                    + "which needs a login this page's user does not use.")
    @GetMapping("/masters")
    public MasterDtos.All masters(@PathVariable String token) {
        links.resolve(token);
        return MasterDtos.All.of(geo.listStates(), geo.listActiveCities(),
                capacities.list(false), bodyTypes.list(false), goodsTypes.list(false));
    }

    @Operation(summary = "Send photos of a truck, as the link's owner",
            description = "Public. Every field is optional, photos included. The reporter is the "
                    + "link's owner and cannot be set. `owner` is whoever owns the truck, "
                    + "`places` is JSON `[{\"state_id\":1,\"city_id\":2}]` (city_id null = the "
                    + "whole state), `capacity_id` a row of `capacities`.")
    @PostMapping(value = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IntakeDtos.Accepted> upload(
            @PathVariable String token,
            @RequestPart(name = "images", required = false) List<MultipartFile> images,
            @RequestParam(name = "reported_plate", required = false) String reportedPlate,
            @RequestParam(name = "reported_mobile", required = false) String reportedMobile,
            @RequestParam(name = "owner", required = false) String owner,
            @RequestParam(name = "places", required = false) String places,
            @RequestParam(name = "capacity_id", required = false) Long capacityId,
            @RequestParam(name = "captured_at", required = false) String capturedAt) {

        User sender = links.resolve(token);
        links.checkAllowance(sender.getId());

        VehicleIntake saved = intake.upload(images, IntakeService.Report
                // The owner is filed as the report's company: an owner is the entity the truck
                // is registered to, and that is what the CSR checks and what becomes the
                // vehicle's owner on completion.
                .of(reportedPlate, reportedMobile, owner, sender.getName(),
                        sender.getMobile(), IntakeController.parseCapturedAt(capturedAt),
                        null, null, null)
                .withCapacityId(capacityId)
                .withPlaces(places),
                sender.getId());
        links.touch(sender.getId());
        return ResponseEntity.accepted().body(new IntakeDtos.Accepted(
                saved.getId(), saved.getProcessingStatus(), saved.getImageKeys().size(),
                "Received. Thank you."));
    }
}
