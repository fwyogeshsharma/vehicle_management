package com.vehiclemanagement.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A field report: photos from the road, on their way to becoming a vehicle.
 *
 * <p>Modelled on FreightDesk's {@code trucks} row and deliberately <b>not</b> a state on
 * {@link Vehicle}. A vehicle needs a canonical, unique registration number, a body type and
 * exactly one owner; at upload time there is none of those, and OCR may never supply them.
 * Putting a "pending" flag on the register would mean relaxing the constraints the register
 * exists for. See 005-vehicle-intake.sql.
 *
 * <p><b>Three sources of truth sit side by side here and are never merged:</b> what the field
 * executive typed ({@code reported*}), what OCR read ({@code ocr*}), and — once the CSR has rung
 * the driver — the {@link Vehicle} itself. Keeping them apart is what makes a wrong read
 * explainable afterwards instead of arguable.
 *
 * <p><b>Two writers.</b> This is the one place in the codebase where that is true, and it is
 * worth being blunt about it. The {@code ocr_*} columns and {@link ProcessingStatus} belong to
 * the Python worker, which polls this table over its own connection; everything else belongs to
 * this service. The entity has no {@code recordSuccess} method for that reason — the machine
 * half of the lifecycle is not written from Java, and a method here that pretended otherwise
 * would be dead code that reads like a contract.
 */
@Entity
@Table(name = "vehicle_intake")
public class VehicleIntake {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Storage keys, not paths and not URLs.
     *
     * <p>Nothing anywhere parses one. A photo is fetched by the key recorded here, never by
     * rebuilding one from a pattern — which is what lets the object layout change without a
     * migration, and what lets the same key resolve against local disk or a GCS bucket.
     *
     * <p>Populated before the row is inserted, never after: a polling worker must not be able
     * to see a QUEUED row whose photos are not in the store yet.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_keys", nullable = false)
    private List<String> imageKeys = new ArrayList<>();

    // ── What the field executive sent. A claim, not a fact. ──────────────────────────────

    @Column(name = "reported_plate", length = 32)
    private String reportedPlate;

    /**
     * Digits as typed. Ten and canonical when it parsed as an Indian mobile, verbatim when it
     * did not -- see changeset 021. <b>Not necessarily dialable</b>; check the length.
     */
    @Column(name = "reported_mobile", length = 16)
    private String reportedMobile;

    @Column(name = "reported_company", length = 160)
    private String reportedCompany;

    @Column(name = "reported_by", length = 128)
    private String reportedBy;

    /** The reporter's own number, not the truck's. */
    @Column(name = "reporter_mobile", length = 10)
    private String reporterMobile;

    // The six the mobile app has always sent. Free text, every one of them: the app owns its
    // pick lists and we do not get to reject a photo because its spelling of a body type
    // disagrees with ours. The CSR turns these into the typed `edited_` fields on the call.

    @Column(name = "reported_driver_name", length = 128)
    private String reportedDriverName;

    /** 'loaded' | 'unloaded' as the app spells it. */
    @Column(name = "reported_loaded_status", length = 16)
    private String reportedLoadedStatus;

    @Column(name = "reported_body_type", length = 64)
    private String reportedBodyType;

    /** The master row the reported body type matched at upload; editedBodyTypeId wins over it. */
    @Column(name = "matched_body_type_id")
    private Long matchedBodyTypeId;

    /** Capacity as the app spells it, free text. The CSR confirms it into editedCapacity. */
    @Column(name = "reported_capacity", length = 32)
    private String reportedCapacity;

    /** What it carries -- 'Steel', 'Cement'. Cargo-fit context for the call. */
    @Column(name = "reported_material_type", length = 64)
    private String reportedMaterialType;

    @Column(name = "reported_no_of_wheels")
    private Short reportedNoOfWheels;

    @Column(name = "reported_axle_type", length = 32)
    private String reportedAxleType;

    @Column(name = "captured_at")
    private OffsetDateTime capturedAt;

    @Column(length = 255)
    private String location;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    // ── Dimension 1: the machine. Written by the Python worker, not from here. ───────────

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 16)
    private ProcessingStatus processingStatus = ProcessingStatus.QUEUED;

    @Column(name = "processing_error", length = 500)
    private String processingError;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    /** When a worker took it. Null unless PROCESSING; a stale one is reclaimed by the worker. */
    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    /**
     * How many times anyone has tried to read these photos.
     *
     * <p><b>Not an automatic retry counter.</b> One failed attempt marks the row FAILED and no
     * worker looks at it again; this counts the times a human pressed Read again, so a row that
     * has been fought over is visible as such.
     */
    @Column(nullable = false)
    private short attempts;

    @Column(name = "ocr_plate", length = 32)
    private String ocrPlate;

    /**
     * Every number OCR read off the truck, best-read first.
     *
     * <p>Not one number. A truck routinely carries several painted in a row — the owner's, the
     * driver's, the transport office's — and a telecaller who cannot reach the first needs the
     * second, which was legible in the same photo all along.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ocr_mobiles", nullable = false)
    private List<String> ocrMobiles = new ArrayList<>();

    @Column(name = "ocr_company", length = 160)
    private String ocrCompany;

    /** HIGH / LOW / NONE — how many consistent reads backed the plate, not a probability. */
    @Column(name = "ocr_confidence", length = 8)
    private String ocrConfidence;

    /** Every candidate and body text the engine returned, kept so a bad read can be explained. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ocr_raw")
    private Map<String, Object> ocrRaw;

    // ── What the CSR says it actually is. Wins over the machine, never overwrites it. ───

    /**
     * The CSR's corrections, kept apart from {@code ocr_*} on purpose.
     *
     * <p>OCR once returned {@code WC32KN7996} for a truck painted {@code UP32 KN 7996} — digits
     * right, state code wrong, and the result a plausible registration belonging to nobody. That
     * is only caught by someone comparing the read against the picture, and only <i>studied</i>
     * if the original survives the correction. Null means uncorrected, which is not the same as
     * a CSR deliberately blanking a bad read.
     */
    @Column(name = "edited_plate", length = 32)
    private String editedPlate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edited_mobiles")
    private List<String> editedMobiles;

    @Column(name = "edited_company", length = 160)
    private String editedCompany;

    /**
     * The driver's name, as the CSR took it down.
     *
     * <p>The one field with no {@code ocr_} or {@code reported_} counterpart — a name is not
     * painted on a truck and the field app does not ask for one. It exists so a name learned on
     * a call survives until the CSR is ready to complete, which previously it did not.
     */
    @Column(name = "edited_driver_name", length = 128)
    private String editedDriverName;

    /** Chosen from the body_types master. RESTRICT, as on vehicles: retiring one must not blank it. */
    @Column(name = "edited_body_type_id")
    private Long editedBodyTypeId;

    /** The label from the capacities pick list, stored verbatim — see that table's comment. */
    @Column(name = "edited_capacity", length = 32)
    private String editedCapacity;

    /**
     * Where the driver says the truck runs: {@code [{state_id, city_id|null}]}, a null city
     * meaning the whole state.
     *
     * <p><b>Notes, not locations.</b> The real rows live in {@code company_x_location} or
     * {@code vehicle_x_location} and are written by {@link VehicleIntake}'s completion path,
     * which decides WHICH of the two based on who ends up owning the truck. Holding them here
     * in the same shape would invite someone to copy them across and skip that rule.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edited_places")
    private List<Map<String, Object>> editedPlaces;

    @Column(name = "edited_by", length = 128)
    private String editedBy;

    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    // ── Dimension 2: the human. This service's half. ─────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 16)
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    @Column(name = "reviewed_by", length = 128)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    /** Set only on COMPLETED, and required there — ck_intake_vehicle enforces both directions. */
    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Trigger-maintained, and read back after every write — see the note on Vehicle. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected VehicleIntake() {
    }

    public VehicleIntake(List<String> imageKeys) {
        this.imageKeys = new ArrayList<>(imageKeys);
    }

    public Long getId() { return id; }
    public List<String> getImageKeys() { return imageKeys; }

    public String getReportedPlate() { return reportedPlate; }
    public void setReportedPlate(String v) { this.reportedPlate = v; }
    public String getReportedMobile() { return reportedMobile; }
    public void setReportedMobile(String v) { this.reportedMobile = v; }
    public String getReportedCompany() { return reportedCompany; }
    public void setReportedCompany(String v) { this.reportedCompany = v; }
    public String getReportedBy() { return reportedBy; }
    public void setReportedBy(String v) { this.reportedBy = v; }
    public String getReporterMobile() { return reporterMobile; }
    public void setReporterMobile(String v) { this.reporterMobile = v; }
    public String getReportedDriverName() { return reportedDriverName; }
    public void setReportedDriverName(String v) { this.reportedDriverName = v; }
    public String getReportedLoadedStatus() { return reportedLoadedStatus; }
    public void setReportedLoadedStatus(String v) { this.reportedLoadedStatus = v; }
    public String getReportedBodyType() { return reportedBodyType; }
    public void setReportedBodyType(String v) { this.reportedBodyType = v; }
    public Long getMatchedBodyTypeId() { return matchedBodyTypeId; }
    public void setMatchedBodyTypeId(Long v) { this.matchedBodyTypeId = v; }
    public String getReportedCapacity() { return reportedCapacity; }
    public void setReportedCapacity(String v) { this.reportedCapacity = v; }
    public String getReportedMaterialType() { return reportedMaterialType; }
    public void setReportedMaterialType(String v) { this.reportedMaterialType = v; }
    public Short getReportedNoOfWheels() { return reportedNoOfWheels; }
    public void setReportedNoOfWheels(Short v) { this.reportedNoOfWheels = v; }
    public String getReportedAxleType() { return reportedAxleType; }
    public void setReportedAxleType(String v) { this.reportedAxleType = v; }
    public OffsetDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(OffsetDateTime v) { this.capturedAt = v; }
    public String getLocation() { return location; }
    public void setLocation(String v) { this.location = v; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double v) { this.latitude = v; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double v) { this.longitude = v; }

    public ProcessingStatus getProcessingStatus() { return processingStatus; }
    public String getProcessingError() { return processingError; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
    public OffsetDateTime getClaimedAt() { return claimedAt; }
    public short getAttempts() { return attempts; }
    public String getOcrPlate() { return ocrPlate; }
    public List<String> getOcrMobiles() { return ocrMobiles; }
    public String getOcrCompany() { return ocrCompany; }
    public String getOcrConfidence() { return ocrConfidence; }
    public Map<String, Object> getOcrRaw() { return ocrRaw; }

    public String getEditedPlate() { return editedPlate; }
    public List<String> getEditedMobiles() { return editedMobiles; }
    public String getEditedCompany() { return editedCompany; }
    public String getEditedDriverName() { return editedDriverName; }
    public Long getEditedBodyTypeId() { return editedBodyTypeId; }
    public String getEditedCapacity() { return editedCapacity; }
    public List<Map<String, Object>> getEditedPlaces() { return editedPlaces; }
    public String getEditedBy() { return editedBy; }
    public OffsetDateTime getEditedAt() { return editedAt; }

    /**
     * What this intake actually says, correction first.
     *
     * <p>Everything downstream — the worklist, the completion form's prefill — asks these rather
     * than reaching for a column, so that "the CSR's answer wins" is decided once here instead of
     * in every caller that happens to remember.
     */
    public String plate() {
        return firstPresent(editedPlate, ocrPlate, reportedPlate);
    }

    public List<String> mobiles() {
        if (editedMobiles != null) {
            return editedMobiles;
        }
        if (!ocrMobiles.isEmpty()) {
            return ocrMobiles;
        }
        return reportedMobile == null ? List.of() : List.of(reportedMobile);
    }

    public String company() {
        return firstPresent(editedCompany, ocrCompany, reportedCompany);
    }

    /** What to show for body type: the CSR's pick, else the master row the app's text matched. */
    public Long bodyTypeId() {
        return editedBodyTypeId != null ? editedBodyTypeId : matchedBodyTypeId;
    }

    /** What to show for capacity: the CSR's value, else what the app reported. */
    public String capacity() {
        return editedCapacity != null && !editedCapacity.isBlank() ? editedCapacity
                : reportedCapacity == null || reportedCapacity.isBlank() ? null : reportedCapacity;
    }

    /**
     * The CSR's name for the driver, else what the uploader typed. OCR cannot read a name.
     * Unlike plate or company, a blank edit does NOT win: the form sends "" for an untouched
     * name field, and "no driver" is not something a CSR can mean.
     */
    public String driverName() {
        if (editedDriverName != null && !editedDriverName.isBlank()) {
            return editedDriverName;
        }
        return reportedDriverName == null || reportedDriverName.isBlank() ? null : reportedDriverName;
    }

    /** Blank counts as absent for the fallback, but an explicit blank correction still wins. */
    private static String firstPresent(String edited, String... rest) {
        if (edited != null) {
            return edited.isBlank() ? null : edited;
        }
        for (String candidate : rest) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Record a correction.
     *
     * <p>A null argument means "not mentioned in this request" and leaves that field as it was;
     * the caller turns an explicitly cleared box into an empty string or an empty list. Without
     * that distinction a form that only edits the company would silently wipe the plate.
     */
    public void applyCorrection(String plate, List<String> mobiles, String company,
                                String driverName, Long bodyTypeId, String capacity,
                                List<Map<String, Object>> places, String by) {
        if (bodyTypeId != null) {
            // 0 is how a cleared dropdown arrives; anything else is a real choice.
            this.editedBodyTypeId = bodyTypeId == 0 ? null : bodyTypeId;
        }
        if (capacity != null) {
            this.editedCapacity = capacity.isBlank() ? null : capacity;
        }
        if (places != null) {
            this.editedPlaces = List.copyOf(places);
        }
        if (plate != null) {
            this.editedPlate = plate;
        }
        if (mobiles != null) {
            this.editedMobiles = new ArrayList<>(mobiles);
        }
        if (company != null) {
            this.editedCompany = company;
        }
        if (driverName != null) {
            this.editedDriverName = driverName;
        }
        this.editedBy = by;
        this.editedAt = OffsetDateTime.now();
    }

    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public String getReviewedBy() { return reviewedBy; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public String getReviewNote() { return reviewNote; }
    public Long getVehicleId() { return vehicleId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    /** A CSR turned it into a vehicle. Both halves of ck_intake_vehicle are satisfied here. */
    public void complete(long vehicleId, String by) {
        this.vehicleId = vehicleId;
        this.reviewStatus = ReviewStatus.COMPLETED;
        this.reviewedBy = by;
        this.reviewedAt = OffsetDateTime.now();
    }

    public void discard(String by, String reason) {
        this.reviewStatus = ReviewStatus.DISCARDED;
        this.reviewedBy = by;
        this.reviewedAt = OffsetDateTime.now();
        this.reviewNote = truncate(reason);
    }

    /**
     * Put a FAILED row back in the queue.
     *
     * <p>The only place this service writes the machine dimension, and <b>the only way a failed
     * photo is ever read again</b> — the worker retries nothing on its own. FreightDesk cannot
     * do even this: its FAILED is terminal and not re-run by a restart either, so an object
     * store that was briefly unreachable loses that photo's reading permanently.
     *
     * <p>{@code attempts} is deliberately <b>not</b> reset. It counts how many times a human has
     * asked, and a row on its fourth attempt is worth someone noticing rather than quietly
     * starting over at one.
     */
    public void requeue() {
        this.processingStatus = ProcessingStatus.QUEUED;
        this.processingError = null;
        this.claimedAt = null;
        this.processedAt = null;
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 500));
    }
}
