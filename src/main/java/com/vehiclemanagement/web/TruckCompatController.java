package com.vehiclemanagement.web;

import com.vehiclemanagement.domain.BodyType;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.VehicleIntake;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.repo.BodyTypeRepository;
import com.vehiclemanagement.repo.UserRepository;
import com.vehiclemanagement.security.Principal;
import com.vehiclemanagement.service.IntakeService;
import com.vehiclemanagement.web.dto.TruckDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The mobile app's endpoints, at the paths and in the words it already uses.
 *
 * <p>The field app was built against FreightDesk's {@code /api/trucks/report}. FreightDesk is
 * being switched off and the app is moving here by changing its base URL and nothing else — so
 * these three routes reproduce that contract rather than expressing this system's own
 * vocabulary. {@link IntakeController} is where the vocabulary lives, and this class is a thin
 * translation in front of the same {@link IntakeService}.
 *
 * <p><b>Three things here look wrong and are deliberate:</b>
 *
 * <ol>
 *   <li>{@code GET /trucks/{id}/image/{idx}} has no {@code /api} prefix. FreightDesk's did not
 *       either, and the app has that path compiled in.</li>
 *   <li>The upload accepts {@code number_of_wheels} but the record reads back {@code num_wheels}.
 *       That asymmetry is FreightDesk's; the app depends on both halves of it.</li>
 *   <li>These routes are <b>unauthenticated</b>. See {@link #report} — it is the only way the
 *       app keeps working, and the reasoning is written out there rather than here so nobody
 *       relaxes it by accident.</li>
 * </ol>
 *
 * <p>Nothing else in this service should call these. New clients get {@code /api/intake/*}.
 */
@RestController
@Tag(name = "Mobile app (FreightDesk-compatible)",
        description = "Frozen contract for the existing field app. Do not extend; new callers "
                + "use /api/intake.")
public class TruckCompatController {

    private final IntakeService intake;
    private final UserRepository users;
    private final BodyTypeRepository bodyTypes;

    public TruckCompatController(IntakeService intake, UserRepository users,
                                 BodyTypeRepository bodyTypes) {
        this.intake = intake;
        this.users = users;
        this.bodyTypes = bodyTypes;
    }

    /**
     * A field report: 1–5 photos of one truck, plus whatever the executive managed to type.
     *
     * <p><b>This requires a signed-in caller.</b> It did not, originally — the app carried a
     * FreightDesk token that means nothing here, so anonymous uploads were accepted to keep
     * installed copies working. That stopped being defensible once the field staff became
     * users of this system: with anonymous accepted, deactivating or removing somebody did
     * not stop them uploading, because they only had to drop the header. Removing an account
     * has to remove the access, and it cannot if there is no account on the request.
     *
     * <p>Deactivation bites through the ordinary path: the authentication converter re-reads
     * the user row on every request and refuses a deactivated one, so it takes effect on the
     * next upload rather than whenever the token happens to expire.
     *
     * <p><b>The report is attributed to whoever sent it.</b> A free-text {@code reported_by}
     * from the body is ignored in favour of the account — the point of signing in is that the
     * name on the row is one somebody can be asked about, not one they typed.
     *
     * <p><b>Only the photos are required.</b> Every typed field is optional and a malformed one
     * is dropped rather than rejected: a misread digit must not cost us the photograph, which
     * is the part OCR and the CSR can both still work from.
     */
    @Operation(summary = "Submit a field report (mobile app)",
            description = "Multipart, 1–5 photos of ONE truck. Returns 202 immediately with an "
                    + "id; OCR runs in the background. Poll status_url until processing_status "
                    + "is DONE or FAILED. **Requires a bearer token** from "
                    + "POST /api/auth/login; the report is attributed to that account, and a "
                    + "deactivated account is refused on its next request.")
    @PostMapping(value = "/api/trucks/report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TruckDtos.Accepted> report(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart(name = "images", required = false) List<MultipartFile> images,
            @RequestParam(name = "phone_number", required = false) String phoneNumber,
            @RequestParam(name = "vehicle_number", required = false) String vehicleNumber,
            @RequestParam(name = "loaded_status", required = false) String loadedStatus,
            @RequestParam(name = "body_type_id", required = false) Long bodyTypeId,
            @RequestParam(name = "material_type", required = false) String materialType,
            @RequestParam(name = "driver_name", required = false) String driverName,
            @RequestParam(name = "number_of_wheels", required = false) Integer numberOfWheels,
            @RequestParam(name = "axle_type", required = false) String axleType,
            @RequestParam(name = "capacity_id", required = false) Long capacityId,
            @RequestParam(name = "location", required = false) String location,
            @RequestParam(name = "latitude", required = false) Double latitude,
            @RequestParam(name = "longitude", required = false) Double longitude,
            @RequestParam(name = "captured_at", required = false) String capturedAt,
            @RequestParam(name = "reported_by", required = false) String reportedBy,
            @RequestParam(name = "company_name", required = false) String companyName,
            @RequestParam(name = "company_mobile", required = false) String companyMobile,
            @RequestParam(name = "places", required = false) String places) {

        // 400, not this service's usual 422 for a field error. FreightDesk's contract names
        // 400 for "more than 5 images, or no image was decodable", and the app's upload screen
        // branches on the code. Checked here rather than in IntakeService so the CSR-facing
        // endpoint keeps the validation semantics the rest of the API uses.
        if (images != null && images.size() > intake.maxImages()) {
            throw new ApiException(400, "At most " + intake.maxImages() + " photos per truck");
        }

        // Who actually sent it beats what the body claims. `reported_by` survives only as a
        // fallback for a caller that somehow has no row, which the filter chain makes
        // impossible -- it is there so a null cannot reach a NOT NULL-ish display.
        User sender = users.findById(Principal.userId(jwt)).orElse(null);
        String attributedTo = sender == null ? reportedBy : sender.getName();
        String senderMobile = sender == null ? null : sender.getMobile();

        VehicleIntake saved = intake.upload(images, new IntakeService.Report(
                vehicleNumber, phoneNumber, companyName, attributedTo,
                senderMobile, parseCapturedAt(capturedAt), location, latitude, longitude,
                driverName, loadedStatus, bodyTypeId, materialType,
                wheels(numberOfWheels), axleType, capacityId, companyMobile, null)
                .withPlaces(places));

        return ResponseEntity.accepted().body(TruckDtos.Accepted.of(saved));
    }

    /**
     * The record the app polls after a 202, in FreightDesk's shape.
     *
     * <p>Authenticated like the upload. The app polls this immediately after submitting with
     * the same token it uploaded with, so there is nothing to relax.
     */
    @Operation(summary = "Poll one field report (mobile app)",
            description = "Watch processing_status: QUEUED/PROCESSING means keep polling, DONE "
                    + "or FAILED means stop.")
    @GetMapping("/api/trucks/{id}")
    public TruckDtos.Truck truck(@PathVariable long id) {
        VehicleIntake row = intake.get(id);
        String bodyType = row.getBodyTypeId() == null ? null
                : bodyTypes.findById(row.getBodyTypeId()).map(BodyType::getName).orElse(null);
        return TruckDtos.Truck.of(row, bodyType);
    }

    /**
     * One uploaded photo back.
     *
     * <p>No {@code /api} prefix — FreightDesk's path, which the app has compiled in.
     *
     * <p>FreightDesk restricted this to the account that submitted the report, or a reviewer.
     * This requires a signed-in caller but does not yet check WHICH one — every account here
     * is staff, and a CSR working the worklist legitimately needs to see photos somebody else
     * uploaded. If field staff are ever outside contributors, that per-owner check comes
     * back.
     */
    @Operation(summary = "Fetch an uploaded photo (mobile app)",
            description = "0-based index, up to images_accepted - 1. 404 once the object is "
                    + "gone — treat it as permanent, not worth retrying.")
    @GetMapping("/trucks/{id}/image/{index}")
    public ResponseEntity<byte[]> image(@PathVariable long id, @PathVariable int index) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(intake.photo(id, index));
    }

    /**
     * A bad timestamp falls back to now rather than failing the upload — the photos are the
     * point, and a wrong clock on a field device is not worth losing them over.
     */
    private static OffsetDateTime parseCapturedAt(String value) {
        if (value == null || value.isBlank()) {
            return OffsetDateTime.now();
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException e) {
            return OffsetDateTime.now();
        }
    }

    /**
     * The app sends a free integer; the column is a SMALLINT with a sanity CHECK. Anything
     * outside it is a client bug, and a client bug must not reject the photographs.
     */
    private static Short wheels(Integer value) {
        if (value == null || value < 2 || value > 32) {
            return null;
        }
        return value.shortValue();
    }
}
