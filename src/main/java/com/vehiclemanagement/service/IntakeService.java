package com.vehiclemanagement.service;

import com.vehiclemanagement.config.VehicleManagementProperties;
import com.vehiclemanagement.domain.BodyType;
import com.vehiclemanagement.domain.Company;
import com.vehiclemanagement.domain.ProcessingStatus;
import com.vehiclemanagement.domain.ReviewStatus;
import com.vehiclemanagement.domain.Vehicle;
import com.vehiclemanagement.domain.VehicleIntake;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.BodyTypeRepository;
import com.vehiclemanagement.repo.CompanyRepository;
import com.vehiclemanagement.repo.UserRepository;
import com.vehiclemanagement.repo.VehicleIntakeRepository;
import com.vehiclemanagement.repo.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Photos from the field: taking them in, and turning what comes back into vehicles.
 *
 * <p><b>This service no longer hands work to the OCR worker.</b> The worker polls
 * {@code vehicle_intake} over its own PostgreSQL connection and writes the {@code ocr_*} columns
 * and {@link ProcessingStatus} itself; the claim and the staleness reclaim live in
 * {@code vehicleManagementOcr/ocr/db.py}. What is left here is the two human ends: the field
 * executive uploading, and the CSR completing.
 *
 * <p><b>That worker never retries a failure.</b> One failed attempt marks the row FAILED and no
 * poll will pick it up again, so {@link #retry} is the only route back into the queue — and a
 * person has to ask for it.
 *
 * <p>Nothing here writes to {@code vehicles} directly. Completion calls
 * {@link VehicleService#intake} — the one code path that already knows a company's truck may
 * only be driven by its own people, that the driver must be hired before being assigned, and
 * that locations land on the company or the vehicle depending on who owns it. Duplicating any
 * of that here is how the two paths drift.
 */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    /**
     * A slice of the worklist, named for what a CSR is actually asking.
     *
     * <p>Each is a pair of coordinates on the two independent status axes — which is the reason
     * the axes are separate. {@link #FAILED} is the case a single-column design could not
     * express: OCR gave up, so there is nothing machine-read to show, but the photo is still
     * there and a human can very often read the plate off it perfectly well.
     */
    public enum Tab {
        /** OCR is finished and nobody has rung the driver yet. The queue that matters. */
        TO_CALL,
        /** Still with the machine. */
        WAITING,
        /** OCR gave up. Workable by hand, and the only tab where Read again appears. */
        FAILED,
        /** A vehicle came out of it. */
        COMPLETED,
        /** Rejected by a CSR. */
        DISCARDED,
        /** Everything, newest first. */
        ALL
    }

    private final VehicleIntakeRepository intakes;
    private final VehicleRepository vehicles;
    private final VehicleService vehicleService;
    private final ImageStore images;
    private final VehicleManagementProperties.Intake config;
    // Read-only, and only for the duplicate preview. Writes to either still go through
    // VehicleService.intake, so there remains exactly one path that creates a driver or company.
    private final CompanyRepository companies;
    private final UserRepository users;
    private final BodyTypeRepository bodyTypes;

    public IntakeService(VehicleIntakeRepository intakes, VehicleRepository vehicles,
                         VehicleService vehicleService, ImageStore images,
                         VehicleManagementProperties properties,
                         CompanyRepository companies, UserRepository users,
                         BodyTypeRepository bodyTypes) {
        this.intakes = intakes;
        this.vehicles = vehicles;
        this.vehicleService = vehicleService;
        this.images = images;
        this.config = properties.getIntake();
        this.companies = companies;
        this.users = users;
        this.bodyTypes = bodyTypes;
    }

    public VehicleIntake get(long id) {
        return intakes.findById(id).orElseThrow(
                () -> new ApiException.NotFound("Intake " + id + " not found."));
    }

    public Page<VehicleIntake> list(Tab tab, Pageable pageable) {
        return switch (tab == null ? Tab.TO_CALL : tab) {
            case TO_CALL -> intakes.findByReviewStatusAndProcessingStatusInOrderByCreatedAtAsc(
                    ReviewStatus.PENDING, List.of(ProcessingStatus.DONE), pageable);
            case WAITING -> intakes.findByReviewStatusAndProcessingStatusInOrderByCreatedAtAsc(
                    ReviewStatus.PENDING,
                    List.of(ProcessingStatus.QUEUED, ProcessingStatus.PROCESSING), pageable);
            case FAILED -> intakes.findByReviewStatusAndProcessingStatusInOrderByCreatedAtAsc(
                    ReviewStatus.PENDING, List.of(ProcessingStatus.FAILED), pageable);
            case COMPLETED -> intakes.findByReviewStatusOrderByCreatedAtDesc(
                    ReviewStatus.COMPLETED, pageable);
            case DISCARDED -> intakes.findByReviewStatusOrderByCreatedAtDesc(
                    ReviewStatus.DISCARDED, pageable);
            case ALL -> intakes.findAllByOrderByCreatedAtDesc(pageable);
        };
    }

    public long count(Tab tab) {
        return switch (tab == null ? Tab.TO_CALL : tab) {
            case TO_CALL -> intakes.countByReviewStatusAndProcessingStatusIn(
                    ReviewStatus.PENDING, List.of(ProcessingStatus.DONE));
            case WAITING -> intakes.countByReviewStatusAndProcessingStatusIn(
                    ReviewStatus.PENDING,
                    List.of(ProcessingStatus.QUEUED, ProcessingStatus.PROCESSING));
            case FAILED -> intakes.countByReviewStatusAndProcessingStatusIn(
                    ReviewStatus.PENDING, List.of(ProcessingStatus.FAILED));
            case COMPLETED -> intakes.countByReviewStatus(ReviewStatus.COMPLETED);
            case DISCARDED -> intakes.countByReviewStatus(ReviewStatus.DISCARDED);
            case ALL -> intakes.count();
        };
    }

    // ── the field executive's end ───────────────────────────────────────────────

    /**
     * Accept photos of one truck.
     *
     * <p><b>The bytes are written before the row is inserted.</b> The ordering is the whole
     * reason {@code image_keys} can be NOT NULL, and it matters now that the worker polls the
     * table directly: FreightDesk inserts a QUEUED row first and attaches its keys a moment
     * later, which is harmless when an in-process queue hands the id over at the very end, but
     * here it would let a worker claim a row whose photos do not exist yet and fail it for
     * "photos no longer available".
     *
     * <p>The cost of this ordering is the mirror-image failure: a crash after the bytes land and
     * before the insert commits leaves orphan objects nobody will look for. That is the cheaper
     * of the two — an unreferenced object costs storage, a phantom queue entry costs a report.
     * The key's date prefix is what makes sweeping them a single operation later.
     */
    /**
     * Everything a field report claims, as one value.
     *
     * <p>A record rather than fifteen parameters because the mobile app's contract keeps
     * growing sideways and a positional argument list of this width is where a caller
     * eventually swaps two strings of the same type and nothing complains.
     *
     * <p>Every field is optional. <b>Only the photos are required</b>, which is the whole
     * premise: a reporter who can photograph a truck but cannot read anything off it has still
     * done something useful.
     */
    public record Report(String plate, String mobile, String company, String reportedBy,
                         String reporterMobile, OffsetDateTime capturedAt,
                         String location, Double latitude, Double longitude,
                         String driverName, String loadedStatus, String bodyType,
                         String materialType, Short noOfWheels, String axleType,
                         String capacity) {

        /** The nine fields the CSR-facing upload endpoint sends; the app-only six stay null. */
        public static Report of(String plate, String mobile, String company, String reportedBy,
                                String reporterMobile, OffsetDateTime capturedAt,
                                String location, Double latitude, Double longitude) {
            return new Report(plate, mobile, company, reportedBy, reporterMobile, capturedAt,
                    location, latitude, longitude, null, null, null, null, null, null, null);
        }
    }

    @Transactional
    public VehicleIntake upload(List<MultipartFile> files, String reportedPlate,
                                String reportedMobile, String reportedCompany, String reportedBy,
                                String reporterMobile, OffsetDateTime capturedAt,
                                String location, Double latitude, Double longitude) {
        return upload(files, Report.of(reportedPlate, reportedMobile, reportedCompany,
                reportedBy, reporterMobile, capturedAt, location, latitude, longitude));
    }

    /** How many photos one report may carry. Exposed so the app-facing controller can answer
     *  a too-large batch with the 400 that contract specifies, rather than this service's 422. */
    public int maxImages() {
        return config.getMaxImages();
    }

    @Transactional
    public VehicleIntake upload(List<MultipartFile> files, Report report) {
        String reportedPlate = report.plate();
        String reportedCompany = report.company();
        String reportedBy = report.reportedBy();
        String reporterMobile = report.reporterMobile();
        OffsetDateTime capturedAt = report.capturedAt();
        String location = report.location();
        Double latitude = report.latitude();
        Double longitude = report.longitude();
        if (files == null || files.isEmpty()) {
            throw new FieldValidationException("images", "At least one photo is required.");
        }
        if (files.size() > config.getMaxImages()) {
            throw new FieldValidationException("images",
                    "At most " + config.getMaxImages() + " photos per truck.");
        }

        // Independent of the row id, precisely so the bytes can be stored first.
        String uploadId = UUID.randomUUID().toString();
        long maxBytes = (long) config.getMaxImageMb() * 1024 * 1024;

        List<String> keys = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file.isEmpty()) {
                continue;
            }
            if (file.getSize() > maxBytes) {
                throw new FieldValidationException("images",
                        "Photo " + (i + 1) + " is larger than " + config.getMaxImageMb() + " MB.");
            }
            String key = images.keyFor(uploadId, i, extensionOf(file.getOriginalFilename()));
            try {
                images.put(key, file.getBytes(), file.getContentType());
            } catch (IOException e) {
                throw new ApiException(500, "Could not read the uploaded photo.");
            }
            keys.add(key);
        }
        if (keys.isEmpty()) {
            throw new FieldValidationException("images", "Every uploaded file was empty.");
        }

        VehicleIntake intake = new VehicleIntake(keys);
        intake.setReportedPlate(Normalizer.clean(reportedPlate));
        intake.setReportedMobile(reportedDigits(report.mobile()));
        intake.setReportedCompany(Normalizer.clean(reportedCompany));
        intake.setReportedBy(Normalizer.clean(reportedBy));
        intake.setReporterMobile(Normalizer.optionalMobile(reporterMobile, "reporter_mobile"));
        intake.setCapturedAt(capturedAt == null ? OffsetDateTime.now() : capturedAt);
        intake.setLocation(Normalizer.clean(location));
        intake.setLatitude(latitude);
        intake.setLongitude(longitude);
        intake.setReportedDriverName(Normalizer.clean(report.driverName()));
        intake.setReportedLoadedStatus(Normalizer.clean(report.loadedStatus()));
        intake.setReportedBodyType(Normalizer.clean(report.bodyType()));
        intake.setMatchedBodyTypeId(matchBodyType(intake.getReportedBodyType()));
        intake.setReportedCapacity(capacityOrNull(report.capacity()));
        intake.setReportedMaterialType(Normalizer.clean(report.materialType()));
        intake.setReportedNoOfWheels(wheelsInRange(report.noOfWheels()));
        intake.setReportedAxleType(Normalizer.clean(report.axleType()));

        VehicleIntake saved = intakes.saveAndFlush(intake);
        log.info("intake {} accepted with {} photo(s), QUEUED for OCR", saved.getId(), keys.size());
        return saved;
    }

    /**
     * The reporter's number, canonical where possible and verbatim where not.
     *
     * <p><b>This never throws.</b> {@link Normalizer#mobile} does, and using it here meant a
     * misread digit returned 400 and the photos were discarded — for the one field OCR is
     * about to read off the truck anyway. The mobile app has always been permitted to send an
     * unusable number; the report is stored either way and the CSR judges it against the photo.
     *
     * <p>Anything longer than the column is not a phone number by any reading, so it is
     * dropped rather than truncated: a truncated number looks dialable and is not.
     */
    /** The active master row whose name equals the app's text, ignoring case; else null. */
    private Long matchBodyType(String reported) {
        if (reported == null) {
            return null;
        }
        return bodyTypes.findByNameKey(reported.strip().toLowerCase())
                .filter(BodyType::isActive).map(BodyType::getId).orElse(null);
    }

    /** Dropped rather than truncated when longer than the column: a cut-off capacity reads as a different one. */
    private static String capacityOrNull(String raw) {
        String c = Normalizer.clean(raw);
        return c == null || c.length() > 32 ? null : c;
    }

    private static String reportedDigits(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Normalizer.mobile(raw, "reported_mobile");
        } catch (RuntimeException notAMobile) {
            String digits = raw.replaceAll("[^0-9]", "");
            return digits.isEmpty() || digits.length() > 16 ? null : digits;
        }
    }

    /**
     * Out-of-range wheel counts are dropped, not rejected — {@code ck_intake_reported_wheels}
     * would otherwise turn a client bug into a lost photo at INSERT time.
     */
    private static Short wheelsInRange(Short wheels) {
        return wheels == null || wheels < 2 || wheels > 32 ? null : wheels;
    }

    /** Only extensions we can plausibly decode; anything else is stored as .jpg and tried. */
    private static String extensionOf(String filename) {
        if (filename == null) {
            return ".jpg";
        }
        String lower = filename.toLowerCase();
        for (String ext : List.of(".jpg", ".jpeg", ".png", ".webp", ".bmp")) {
            if (lower.endsWith(ext)) {
                return ext;
            }
        }
        return ".jpg";
    }

    public byte[] photo(long intakeId, int index) {
        return photo(intakeId, index, 0);
    }

    /**
     * One photo, optionally shrunk to fit {@code maxWidth} pixels.
     *
     * <p>{@code maxWidth <= 0} means the original, untouched — the viewer wants every pixel,
     * because a plate painted on the side of a truck photographed from across a road is exactly
     * the detail a resize destroys. The worklist wants the opposite; see {@link Thumbnails} for
     * what the list was costing before it could ask.
     *
     * <p>A request that cannot be resized falls back to the original rather than failing. That
     * is a deliberate asymmetry with the rest of this service: everywhere else a thing that
     * cannot be done is an error, but here the caller asked for a cheaper version of something
     * it is entitled to, and the expensive version is still a correct answer.
     */
    public byte[] photo(long intakeId, int index, int maxWidth) {
        VehicleIntake intake = get(intakeId);
        List<String> keys = intake.getImageKeys();
        if (index < 0 || index >= keys.size()) {
            throw new ApiException.NotFound("No photo " + index + " on intake " + intakeId + ".");
        }
        byte[] original = images.get(keys.get(index));
        if (maxWidth <= 0) {
            return original;
        }
        byte[] scaled = Thumbnails.scaleToWidth(original, maxWidth);
        return scaled == null ? original : scaled;
    }

    /**
     * What is already on file under this company name and these numbers.
     *
     * <p><b>Uses the very lookups {@link #complete} uses</b> — {@code findByNameKey} on the
     * lower-cased name, {@code findByMobile} on the digits — rather than a search that merely
     * resembles them. A preview that answers a slightly different question than the write is
     * worse than no preview: it tells a CSR "new company" and then quietly attaches the truck to
     * an existing one, or the reverse.
     *
     * <p>That exactness is also the point. Company matching is <b>exact on the lower-cased
     * name</b>, so "Guru Nanak Road Carrier" and "Guru Nanak Roadcarrier" are two companies.
     * There is no fuzzy match and this endpoint does not invent one; what it can do is show the
     * CSR what exists so they notice before creating the second.
     */
    @Transactional(readOnly = true)
    public Lookup lookup(String companyName, List<String> mobiles) {
        Company company = null;
        String clean = Normalizer.clean(companyName);
        if (clean != null) {
            company = companies.findByNameKey(clean.toLowerCase()).orElse(null);
        }

        List<KnownPerson> people = new ArrayList<>();
        for (String raw : mobiles == null ? List.<String>of() : mobiles) {
            String digits = raw == null ? "" : raw.replaceAll("[^0-9]", "");
            if (digits.length() != 10) {
                continue;
            }
            users.findByMobile(digits).ifPresent(u -> people.add(new KnownPerson(
                    digits, u.getId(), u.getName(), u.getUserType().name(), u.isActive())));
        }
        return new Lookup(company == null ? null
                : new KnownCompany(company.getId(), company.getName(), company.getMobile()),
                people);
    }

    public record KnownCompany(Long id, String name, String mobile) {
    }

    public record KnownPerson(String mobile, Long id, String name, String type, boolean active) {
    }

    public record Lookup(KnownCompany company, List<KnownPerson> people) {
    }

    // ── the CSR's end ───────────────────────────────────────────────────────────

    /**
     * Correct what the photo says, without touching what the machine read.
     *
     * <p>Editable from the worklist itself rather than only inside the completion form, because
     * a CSR working a page of twenty spots a wrong plate while scanning and should be able to fix
     * it there — and because a correction made now survives a refresh, where one typed into a
     * form they then close does not.
     *
     * <p>Values are normalised but <b>not rejected</b>, the same rule the OCR columns follow: a
     * half-typed plate mid-correction is not an error, it is someone still typing. The hard
     * validation happens at {@link #complete}, where a vehicle is actually created.
     */
    @Transactional
    public VehicleIntake correct(long id, String actor, String plate, List<String> mobiles,
                                 String company, String driverName, Long bodyTypeId,
                                 String capacity, List<Map<String, Object>> places) {
        VehicleIntake intake = get(id);
        if (intake.getReviewStatus() != ReviewStatus.PENDING) {
            throw new ApiException.Conflict("This intake was already "
                    + intake.getReviewStatus().name().toLowerCase() + "; it cannot be edited.");
        }

        List<String> cleanMobiles = null;
        if (mobiles != null) {
            cleanMobiles = mobiles.stream()
                    .map(m -> m == null ? "" : m.replaceAll("[^0-9]", ""))
                    .filter(m -> !m.isEmpty())
                    .distinct()
                    .toList();
        }
        intake.applyCorrection(corrected(plate, true), cleanMobiles, corrected(company, false),
                corrected(driverName, false), bodyTypeId, corrected(capacity, false), places,
                actor);
        log.info("intake {} corrected by {}", id, actor);
        return intakes.saveAndFlush(intake);
    }

    /**
     * Tidy one corrected value, preserving the three-way distinction the caller depends on.
     *
     * <p>{@code null} in means the field was not part of this request and must be left alone.
     * Anything blank means the CSR deliberately emptied the box, and comes back as {@code ""} —
     * which is stored, and is how "the machine read a plate but there isn't one" is recorded.
     * Collapsing those two into null would make clearing a bad read impossible.
     */
    private static String corrected(String value, boolean upperCase) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return upperCase ? trimmed.toUpperCase() : trimmed;
    }

    /**
     * Turn a reviewed intake into a real vehicle.
     *
     * <p>Delegates wholly to {@link VehicleService#intake}, so the vehicle is created by the
     * same code that serves the manual form — including the rule that a company's truck may only
     * be driven by someone on its books, and that preferred locations land on the company when
     * one owns the truck.
     *
     * <p>Deliberately <b>does not</b> require {@link ProcessingStatus#DONE}. A CSR who can read
     * the plate off a photo the machine gave up on should not have to do anything else first
     * — and since nothing will retry it automatically, waiting would mean waiting forever.
     */
    @Transactional
    public VehicleIntake complete(long id, String actor, String registrationNumber,
                                  Long bodyTypeId, VehicleService.Contacts contacts,
                                  Short axles, Short wheels, String capacity,
                                  BigDecimal lengthFt, List<VehicleService.CityRef> places) {
        VehicleIntake intake = get(id);
        requireUnreviewed(intake, "complete");

        String canonical = Normalizer.registration(registrationNumber, "registration_number");
        vehicles.findByRegistrationNumber(canonical).ifPresent(existing -> {
            // A plain 409 from uq_vehicles_reg would be true but unhelpful. The CSR needs to
            // know the truck is already on file so they can open it rather than retype it.
            throw new ApiException.Conflict(
                    "Vehicle " + canonical + " is already registered (id " + existing.getId()
                    + "). Discard this photo, or open the existing vehicle.");
        });

        Vehicle vehicle = vehicleService.intake(canonical, bodyTypeId, contacts,
                axles, wheels, capacity, lengthFt, places == null ? List.of() : places);
        intake.complete(vehicle.getId(), actor);
        log.info("intake {} COMPLETED as vehicle {} by {}", id, vehicle.getId(), actor);
        return intakes.saveAndFlush(intake);
    }

    @Transactional
    public VehicleIntake discard(long id, String actor, String reason) {
        VehicleIntake intake = get(id);
        requireUnreviewed(intake, "discard");
        intake.discard(actor, Normalizer.clean(reason));
        return intakes.saveAndFlush(intake);
    }

    /**
     * Put a row back in the OCR queue.
     *
     * <p><b>The only way a failed photo is ever read again.</b> The worker retries nothing on
     * its own: one failed attempt marks the row FAILED and no poll will pick it up. FreightDesk
     * cannot even do this much — its FAILED is terminal and not re-run by a restart either.
     *
     * <p>The only place this service writes the machine dimension. {@code attempts} is not
     * reset: it counts how many times a human has asked.
     */
    @Transactional
    public VehicleIntake retry(long id) {
        VehicleIntake intake = get(id);
        if (intake.getReviewStatus() != ReviewStatus.PENDING) {
            throw new ApiException.Conflict("This intake was already "
                    + intake.getReviewStatus().name().toLowerCase() + " and cannot be re-read.");
        }
        if (intake.getProcessingStatus() == ProcessingStatus.PROCESSING) {
            throw new ApiException.Conflict(
                    "A worker is reading this one now. Wait for it to finish or fail.");
        }
        intake.requeue();
        log.info("intake {} re-queued for OCR", id);
        return intakes.saveAndFlush(intake);
    }

    private static void requireUnreviewed(VehicleIntake intake, String verb) {
        if (intake.getReviewStatus() == ReviewStatus.COMPLETED) {
            throw new ApiException.Conflict(
                    "This intake was already completed as vehicle " + intake.getVehicleId() + ".");
        }
        if (intake.getReviewStatus() == ReviewStatus.DISCARDED) {
            throw new ApiException.Conflict(
                    "This intake was discarded; it cannot be " + verb + "d.");
        }
    }
}
