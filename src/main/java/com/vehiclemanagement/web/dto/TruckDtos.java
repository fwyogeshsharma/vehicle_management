package com.vehiclemanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vehiclemanagement.domain.ProcessingStatus;
import com.vehiclemanagement.domain.ReviewStatus;
import com.vehiclemanagement.domain.VehicleIntake;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The wire shape the mobile app already speaks.
 *
 * <p>These records exist to serve <b>one caller we cannot change</b>: the field app, built
 * against FreightDesk's {@code POST /api/trucks/report}. FreightDesk is being retired and the
 * app is being repointed at this service by swapping its base URL — so every name, every
 * status word and every nesting decision here is copied from that contract rather than chosen.
 *
 * <p><b>Do not tidy these names.</b> {@code num_wheels} on the way out and
 * {@code number_of_wheels} on the way in is not a mistake — FreightDesk was inconsistent and
 * the app was written against the inconsistency. Likewise {@code license_plate} rather than
 * {@code registration_number}, and {@code phone_number} rather than {@code mobile}. This file
 * is a translation layer; the domain vocabulary lives in {@link IntakeDtos} and stays there.
 *
 * <p>Fields FreightDesk had and this system does not (frame counters, stream offsets, a
 * website) are serialised as {@code null} rather than dropped. An absent key and a null key
 * are the same thing to most JSON clients, but not to all of them, and the cost of keeping
 * them is one line each.
 *
 * <p>Nothing here is used by the UI. The CSR worklist reads {@code /api/intake/*}, whose shape
 * is free to change.
 */
public final class TruckDtos {

    private TruckDtos() {
    }

    /**
     * The 202 body, field for field.
     *
     * <p>{@code status_url} is what the app polls; it is built from the id rather than stored.
     */
    public record Accepted(Long id,
                           @JsonProperty("processing_status") ProcessingStatus processingStatus,
                           @JsonProperty("review_status") String reviewStatus,
                           @JsonProperty("images_accepted") int imagesAccepted,
                           @JsonProperty("status_url") String statusUrl,
                           String message) {

        public static Accepted of(VehicleIntake i) {
            return new Accepted(i.getId(), i.getProcessingStatus(),
                    review(i.getReviewStatus()), i.getImageKeys().size(),
                    "/api/trucks/" + i.getId(),
                    "Report accepted; OCR is running in the background.");
        }
    }

    /**
     * One report, as the app expects to read it back while polling.
     *
     * <p>Explicitly annotated rather than relying on the global snake_case strategy: this shape
     * is a contract with software we do not build, and a future change to Jackson's naming
     * configuration must not be able to rename these keys underneath it.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Truck(Long id,
                        @JsonProperty("detected_at") OffsetDateTime detectedAt,
                        String source,
                        @JsonProperty("source_ref") String sourceRef,
                        @JsonProperty("license_plate") String licensePlate,
                        @JsonProperty("plate_confidence") Double plateConfidence,
                        @JsonProperty("company_name") String companyName,
                        @JsonProperty("phone_number") String phoneNumber,
                        String website,
                        @JsonProperty("vehicle_type") String vehicleType,
                        String city,
                        @JsonProperty("other_text") String otherText,
                        Integer frames,
                        @JsonProperty("first_seen_sec") Double firstSeenSec,
                        @JsonProperty("last_seen_sec") Double lastSeenSec,
                        @JsonProperty("loaded_status") String loadedStatus,
                        @JsonProperty("body_type") String bodyType,
                        @JsonProperty("material_type") String materialType,
                        @JsonProperty("driver_name") String driverName,
                        String location,
                        Double latitude,
                        Double longitude,
                        /** Out as num_wheels, in as number_of_wheels. FreightDesk's asymmetry. */
                        @JsonProperty("num_wheels") Short numWheels,
                        @JsonProperty("axle_type") String axleType,
                        @JsonProperty("phone_reported") String phoneReported,
                        @JsonProperty("phone_ocr") String phoneOcr,
                        @JsonProperty("reported_by") String reportedBy,
                        @JsonProperty("reported_by_user_id") Long reportedByUserId,
                        @JsonProperty("reporter_phone") String reporterPhone,
                        @JsonProperty("verification_status") String verificationStatus,
                        @JsonProperty("review_status") String reviewStatus,
                        @JsonProperty("reviewed_by") String reviewedBy,
                        @JsonProperty("reviewed_by_user_id") Long reviewedByUserId,
                        @JsonProperty("reviewed_at") OffsetDateTime reviewedAt,
                        @JsonProperty("review_note") String reviewNote,
                        @JsonProperty("processing_status") ProcessingStatus processingStatus,
                        @JsonProperty("processing_error") String processingError,
                        @JsonProperty("processed_at") OffsetDateTime processedAt,
                        @JsonProperty("image_keys") List<String> imageKeys,
                        @JsonProperty("plate_candidates") Object plateCandidates,
                        @JsonProperty("body_texts") Object bodyTexts,
                        @JsonProperty("image_path") String imagePath,
                        @JsonProperty("created_at") OffsetDateTime createdAt,
                        /**
                         * Not FreightDesk's. Added because the app's result screen otherwise has
                         * no way to reach the photos it just uploaded, and an extra key is
                         * ignored by any client that is not looking for it.
                         */
                        @JsonProperty("image_urls") List<String> imageUrls) {

        public static Truck of(VehicleIntake i) {
            Map<String, Object> raw = i.getOcrRaw() == null ? Map.of() : i.getOcrRaw();
            List<String> urls = new ArrayList<>();
            for (int idx = 0; idx < i.getImageKeys().size(); idx++) {
                urls.add("/trucks/" + i.getId() + "/image/" + idx);
            }
            return new Truck(i.getId(),
                    i.getCapturedAt(),
                    // FreightDesk's SourceType for a phone submission. Constant here: this
                    // service has no video or stream ingest to distinguish it from.
                    "image_api",
                    i.getReportedBy() == null ? "mobile_report" : i.getReportedBy(),
                    plate(i),
                    null,               // plate_confidence: our worker records a band, not a number
                    i.company(),
                    firstMobile(i),
                    null,               // website: never extracted here
                    "TRUCK",
                    // city: FreightDesk extracted a city NAME from the plate's state code. This
                    // system does not, and putting the reported address here instead would make
                    // the app display "NH-48, Jaipur" under a City heading. Null is the honest
                    // answer; the address is in `location`, where the app already looks for it.
                    null,
                    null,               // other_text: superseded by ocr_raw
                    null, null, null,   // frames / first_seen_sec / last_seen_sec: video-only
                    i.getReportedLoadedStatus(),
                    i.getReportedBodyType(),
                    i.getReportedMaterialType(),
                    driverName(i),
                    i.getLocation(),
                    i.getLatitude(),
                    i.getLongitude(),
                    i.getReportedNoOfWheels(),
                    i.getReportedAxleType(),
                    i.getReportedMobile(),
                    i.getOcrMobiles().isEmpty() ? null : i.getOcrMobiles().get(0),
                    i.getReportedBy(),
                    null,               // reported_by_user_id: no contributor accounts here
                    i.getReporterMobile(),
                    verification(i),
                    review(i.getReviewStatus()),
                    i.getReviewedBy(),
                    null,
                    i.getReviewedAt(),
                    i.getReviewNote(),
                    i.getProcessingStatus(),
                    i.getProcessingError(),
                    i.getProcessedAt(),
                    i.getImageKeys(),
                    raw.get("plate_candidates"),
                    raw.get("body_texts"),
                    null,               // image_path: keys only, never a server path
                    i.getCreatedAt(),
                    urls);
        }

        /** The CSR's correction wins, then the reporter's claim, then what OCR read. */
        private static String plate(VehicleIntake i) {
            if (i.getEditedPlate() != null && !i.getEditedPlate().isBlank()) {
                return i.getEditedPlate();
            }
            if (i.getReportedPlate() != null && !i.getReportedPlate().isBlank()) {
                return i.getReportedPlate();
            }
            return i.getOcrPlate();
        }

        private static String driverName(VehicleIntake i) {
            return i.driverName();
        }

        private static String firstMobile(VehicleIntake i) {
            List<String> all = i.mobiles();
            return all.isEmpty() ? null : all.get(0);
        }

        /**
         * FreightDesk's trust gate, reproduced: VERIFIED only when the photos independently
         * confirm the plate the reporter typed.
         *
         * <p>Everything else is UNVERIFIED — including a perfectly good report where OCR read
         * nothing. That is what the word means here: "not machine-confirmed", not "wrong". A
         * CSR completing the row by hand is the other way a report becomes real, and it does
         * not run through this check.
         *
         * <p>Null while OCR is still running, because no verdict exists yet.
         */
        private static String verification(VehicleIntake i) {
            if (i.getProcessingStatus() != ProcessingStatus.DONE) {
                return null;
            }
            String reported = canonical(i.getReportedPlate());
            String read = canonical(i.getOcrPlate());
            return reported != null && reported.equals(read) ? "VERIFIED" : "UNVERIFIED";
        }

        private static String canonical(String plate) {
            if (plate == null) {
                return null;
            }
            String value = plate.toUpperCase().replaceAll("[^A-Z0-9]", "");
            return value.isEmpty() ? null : value;
        }
    }

    /**
     * Our review vocabulary in the app's words.
     *
     * <p>The two systems mean the same three things by different names, and the app switches on
     * the strings. COMPLETED is the CSR having finished the call and created a vehicle, which
     * is exactly what FreightDesk called PASSED.
     */
    static String review(ReviewStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case PENDING -> "PENDING";
            case COMPLETED -> "PASSED";
            case DISCARDED -> "REJECTED";
        };
    }
}
