package com.vehiclemanagement.web;

import com.vehiclemanagement.domain.VehicleIntake;
import com.vehiclemanagement.security.Principal;
import com.vehiclemanagement.service.CapacityService;
import com.vehiclemanagement.service.IntakeService;
import com.vehiclemanagement.web.dto.IntakeDtos;
import com.vehiclemanagement.web.dto.VehicleDtos;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Photos from the road: in from the field app, then on to a CSR.
 *
 * <p>Two callers, both people, both authenticated the ordinary way: the field app uploading, and
 * a CSR working the list.
 *
 * <p><b>The OCR worker is no longer one of them.</b> It used to claim work and report results
 * through two shared-secret endpoints here; it now polls {@code vehicle_intake} over its own
 * PostgreSQL connection. That removed the shared secret, the two unauthenticated routes and the
 * base64 job payload — and moved the cost elsewhere: the worker holds database credentials, and
 * the table has a writer this service cannot see.
 */
@RestController
@RequestMapping("/api/intake")
public class IntakeController {

    private final IntakeService intake;
    private final CapacityService capacities;

    public IntakeController(IntakeService intake, CapacityService capacities) {
        this.intake = intake;
        this.capacities = capacities;
    }

    // ── the field app ───────────────────────────────────────────────────────────

    @Operation(summary = "Upload photos of a truck",
            description = "For the field app. 1–5 photos of ONE truck, plus anything the "
                    + "executive managed to type. Returns immediately with an id — the photos "
                    + "go to the object store, the row is QUEUED, and an OCR worker picks it up "
                    + "from the database within seconds.")
    @PostMapping(value = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IntakeDtos.Accepted> upload(
            @RequestPart(name = "images", required = false) List<MultipartFile> images,
            @RequestParam(name = "reported_plate", required = false) String reportedPlate,
            @RequestParam(name = "reported_mobile", required = false) String reportedMobile,
            @RequestParam(name = "reported_company", required = false) String reportedCompany,
            @RequestParam(name = "reported_company_mobile", required = false) String reportedCompanyMobile,
            @RequestParam(name = "reported_by", required = false) String reportedBy,
            @RequestParam(name = "reporter_mobile", required = false) String reporterMobile,
            @RequestParam(name = "captured_at", required = false) String capturedAt,
            @RequestParam(name = "location", required = false) String location,
            @RequestParam(name = "latitude", required = false) Double latitude,
            @RequestParam(name = "longitude", required = false) Double longitude) {

        VehicleIntake saved = intake.upload(images, com.vehiclemanagement.service.IntakeService.Report
                .of(reportedPlate, reportedMobile, reportedCompany, reportedBy, reporterMobile,
                        parseCapturedAt(capturedAt), location, latitude, longitude)
                .withCompanyMobile(reportedCompanyMobile));
        // 202, not 201: the useful part of this request has not happened yet.
        return ResponseEntity.accepted().body(new IntakeDtos.Accepted(
                saved.getId(), saved.getProcessingStatus(), saved.getImageKeys().size(),
                "Photos accepted. OCR runs in the background."));
    }

    /**
     * A bad timestamp falls back to now rather than failing the upload.
     *
     * <p>The photos are the point; a malformed clock on a field device is not worth losing them
     * over. FreightDesk makes the same call for the same reason.
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

    // ── the CSR worklist ────────────────────────────────────────────────────────

    /**
     * The worklist, as one of a fixed set of tabs.
     *
     * <p>A tab rather than two status parameters because the interesting slices are <i>pairs</i>
     * of coordinates on the two axes, and three of the four combinations are not worth offering.
     * {@code TO_CALL} — machine finished, human has not started — is the only one that is a
     * queue.
     *
     * <p><b>Deliberately not sortable.</b> A queue has one right order: oldest first, so the
     * longest-waiting photo is worked next. The history tabs are newest first, because they are
     * a log. Letting a CSR re-sort would only let them work it in an order that leaves someone
     * waiting.
     *
     * <p>The ordering lives in the repository method names, so nothing is passed in the
     * {@link Pageable}: these are <b>derived</b> queries, and Spring Data resolves a
     * {@code Sort} on one against <i>entity property</i> names. {@link Sorts} yields <i>column</i>
     * names, which is correct for the native {@code search} queries elsewhere and a 500 here.
     */
    @GetMapping
    public PageResponse<IntakeDtos.Summary> list(
            @RequestParam(name = "tab", required = false) IntakeService.Tab tab,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize) {
        return PageResponse.of(
                intake.list(tab, PageParams.of(page, pageSize, Sort.unsorted())),
                IntakeDtos.Summary::from);
    }

    @GetMapping("/counts")
    public IntakeDtos.Counts counts() {
        return new IntakeDtos.Counts(
                intake.count(IntakeService.Tab.TO_CALL),
                intake.count(IntakeService.Tab.WAITING),
                intake.count(IntakeService.Tab.FAILED),
                intake.count(IntakeService.Tab.COMPLETED),
                intake.count(IntakeService.Tab.DISCARDED),
                intake.count(IntakeService.Tab.ALL));
    }

    @Operation(summary = "What is already on file under this name and these numbers",
            description = "A duplicate preview for the CSR, using the SAME lookups completion "
                    + "uses: exact match on the lower-cased company name, and exact match on a "
                    + "10-digit mobile. Answers 'will completing reuse something or create it', "
                    + "nothing more. Company matching is exact — 'Kumar Roadways' and 'Kumar "
                    + "Roadway' are two companies, and this is how a CSR notices before making "
                    + "the second.")
    @GetMapping("/lookup")
    public IntakeService.Lookup lookup(
            @RequestParam(name = "company", required = false) String company,
            @RequestParam(name = "mobile", required = false) List<String> mobiles) {
        return intake.lookup(company, mobiles);
    }

    @GetMapping("/{id}")
    public IntakeDtos.Detail get(@PathVariable long id) {
        return IntakeDtos.Detail.from(intake.get(id));
    }

    @Operation(summary = "One uploaded photo",
            description = "Streamed from the image store — local disk or the GCS bucket, "
                    + "depending on configuration. A 404 means the object is gone for good, "
                    + "not temporarily unavailable.")
    /**
     * The photo, full size by default and shrunk on request.
     *
     * <p><b>{@code w} is why the worklist is usable.</b> Without it the list rendered originals
     * into an 84px box — several megabytes each, and a full-frame bitmap decode per row on top
     * of that. Ask for {@code ?w=200} and the server sends about 8 KB. The viewer omits it and
     * gets every pixel, which is the point of the viewer.
     *
     * <p>Clamped, because the width is a free parameter on an endpoint that does real work:
     * below 32 the result is not an image of anything, and above 1600 it is not a thumbnail and
     * the caller should be asking for the original instead. Out-of-range values are clamped
     * rather than rejected — nothing about a slightly wrong number should cost a picture.
     *
     * <p>A year of {@code max-age}, and {@code immutable}. These objects are written once and
     * never rewritten; the key contains a UUID, so a re-upload is a different URL. Note that
     * caching the <i>bytes</i> was never the expensive part — see {@link IntakeService#photo}.
     */
    @GetMapping("/{id}/photo/{index}")
    public ResponseEntity<byte[]> photo(@PathVariable long id, @PathVariable int index,
                                        @RequestParam(name = "w", required = false) Integer w) {
        int maxWidth = w == null ? 0 : Math.clamp(w, 32, 1600);
        byte[] bytes = intake.photo(id, index, maxWidth);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .body(bytes);
    }

    @Operation(summary = "Complete an intake into a vehicle",
            description = "Creates the vehicle through the same path as the manual intake form, "
                    + "so the driver/company rules apply identically. A plate that is already "
                    + "registered is a 409 naming the existing vehicle, not a bare constraint "
                    + "violation.")
    @PostMapping("/{id}/complete")
    public IntakeDtos.Summary complete(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable long id,
                                       @Valid @RequestBody IntakeDtos.CompleteRequest request) {
        return IntakeDtos.Summary.from(intake.complete(
                id, Principal.username(jwt), request.registrationNumber(), request.bodyTypeId(),
                new com.vehiclemanagement.service.VehicleService.Contacts(
                        request.driverName(), request.driverMobile(), request.driverAltMobile(),
                        request.companyName(),
                        // A number saved earlier on the worklist is used when the form omits one.
                        request.companyMobile() != null && !request.companyMobile().isBlank()
                                ? request.companyMobile()
                                : intake.get(id).companyMobile()),
                request.noOfAxles(), request.noOfWheels(),
                capacities.labelFor(request.capacityId(), request.capacity()),
                request.lengthFt(),
                request.places() == null
                        ? List.of()
                        : request.places().stream().map(VehicleDtos.Place::toRef).toList()));
    }

    @Operation(summary = "Correct what the photo says",
            description = "Edits the plate, the numbers or the company from the worklist without "
                    + "opening the completion form. What OCR read is left untouched — these are "
                    + "stored as the CSR's answer and win over the machine's. Omit a field to "
                    + "leave it alone; send an empty value to clear it.")
    @PatchMapping("/{id}")
    public IntakeDtos.Summary correct(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable long id,
                                      @RequestBody IntakeDtos.CorrectRequest request) {
        return IntakeDtos.Summary.from(intake.correct(id, Principal.username(jwt),
                request.plate(), request.mobiles(), request.company(), request.driverName(),
                request.bodyTypeId(), request.capacityId(), request.places(),
                request.companyMobile()));
    }

    @Operation(summary = "Throw an intake away",
            description = "Unreadable, not a truck, or already on file. Terminal.")
    @PostMapping("/{id}/discard")
    public IntakeDtos.Summary discard(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable long id,
                                      @RequestBody(required = false) IntakeDtos.DiscardRequest body) {
        return IntakeDtos.Summary.from(
                intake.discard(id, Principal.username(jwt), body == null ? null : body.reason()));
    }

    @Operation(summary = "Read it again",
            description = "Puts a FAILED row back in the OCR queue. This is the ONLY way a "
                    + "failed photo is read again — the worker does not retry on its own. It "
                    + "will be picked up on the next poll.")
    @PostMapping("/{id}/retry")
    public IntakeDtos.Summary retry(@PathVariable long id) {
        return IntakeDtos.Summary.from(intake.retry(id));
    }
}
