package com.vehiclemanagement.web.dto;

import com.vehiclemanagement.domain.ProcessingStatus;
import com.vehiclemanagement.domain.ReviewStatus;
import com.vehiclemanagement.domain.VehicleIntake;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Everything the photo-intake endpoints send and receive.
 *
 * <p>There are no worker DTOs here any more. The OCR worker polls {@code vehicle_intake}
 * directly over PostgreSQL and writes its results the same way, so the claim/report shapes that
 * used to live in this file are now column names in
 * {@code vehicleManagementOcr/ocr/db.py} instead.
 */
public final class IntakeDtos {

    private IntakeDtos() {
    }

    /**
     * A row in the CSR's worklist.
     *
     * <p>The {@code reported*} and {@code ocr*} fields are kept apart on purpose. Merging them
     * into one "best guess" would destroy the only thing that makes a wrong value explainable:
     * knowing whether a human typed it or a machine read it.
     *
     * <p>Both status columns are exposed, and the UI needs both: "OCR failed" and "nobody has
     * rung them" are different facts, and a row can be the first without being blocked on it.
     */
    public record Summary(Long id,
                          ProcessingStatus processingStatus, ReviewStatus reviewStatus,
                          int photoCount,
                          String reportedPlate, String reportedMobile, String reportedCompany,
                          String reportedBy, String reportedDriverName,
                          String reportedCompanyMobile,
                          String location,
                          String ocrPlate, List<String> ocrMobiles, String ocrCompany,
                          String ocrConfidence,
                          /**
                           * What the CSR says it is, and what everything downstream should use.
                           * Null where nobody has corrected that field — which is not the same
                           * as a CSR deliberately clearing a bad read.
                           */
                          String editedPlate, List<String> editedMobiles, String editedCompany,
                          String editedDriverName, String editedCompanyMobile,
                          List<Map<String, Object>> editedPlaces,
                          String editedBy,
                          /** The above resolved against the OCR reads. What to display. */
                          String plate, List<String> mobiles, String company, String driverName,
                          String companyMobile,
                          /** Single columns: the app's value at upload, then the CSR's. */
                          Long bodyTypeId, Long capacityId,
                          short attempts, String processingError,
                          Long vehicleId, String reviewedBy, String reviewNote,
                          OffsetDateTime capturedAt, OffsetDateTime createdAt,
                          OffsetDateTime processedAt) {

        public static Summary from(VehicleIntake i) {
            return new Summary(i.getId(),
                    i.getProcessingStatus(), i.getReviewStatus(), i.getImageKeys().size(),
                    i.getReportedPlate(), i.getReportedMobile(), i.getReportedCompany(),
                    i.getReportedBy(), i.getReportedDriverName(),
                    i.getReportedCompanyMobile(),
                    i.getLocation(),
                    i.getOcrPlate(), i.getOcrMobiles(), i.getOcrCompany(),
                    i.getOcrConfidence(),
                    i.getEditedPlate(), i.getEditedMobiles(), i.getEditedCompany(),
                    i.getEditedDriverName(), i.getEditedCompanyMobile(),
                    i.getEditedPlaces(), i.getEditedBy(),
                    i.plate(), i.mobiles(), i.company(), i.driverName(), i.companyMobile(),
                    i.getBodyTypeId(), i.getCapacityId(),
                    i.getAttempts(), i.getProcessingError(),
                    i.getVehicleId(), i.getReviewedBy(), i.getReviewNote(),
                    i.getCapturedAt(), i.getCreatedAt(), i.getProcessedAt());
        }
    }

    /** As {@link Summary}, plus the raw candidates — for when a read looks wrong. */
    public record Detail(Summary summary, Map<String, Object> ocrRaw) {

        public static Detail from(VehicleIntake i) {
            return new Detail(Summary.from(i), i.getOcrRaw());
        }
    }

    /** What the upload endpoint returns: an id and a status to poll, nothing more. */
    public record Accepted(Long id, ProcessingStatus processingStatus, int photosAccepted,
                           String message) {
    }

    /**
     * Completing an intake: the same fields as the manual intake form.
     *
     * <p>Deliberately the same shape as {@link VehicleDtos.IntakeRequest} — the CSR is filling
     * in the very same form, just with some boxes already populated from the photo.
     */
    public record CompleteRequest(/** Optional: the plate may not be known yet. */
                                  String registrationNumber,
                                  /** Optional: null means "not known yet". */
                                  Long bodyTypeId,
                                  @NotBlank String driverName,
                                  @NotBlank String driverMobile,
                                  /**
                                   * A second way to reach the same driver. A truck's side often
                                   * carries several numbers and OCR now returns all of them;
                                   * this is where the CSR puts the one that is also the
                                   * driver's. Optional, and never used to look anyone up.
                                   */
                                  String driverAltMobile,
                                  String companyName,
                                  /** The transport office's number, stored on the company. */
                                  String companyMobile,
                                  Short noOfAxles,
                                  Short noOfWheels,
                                  /** Free text. Ignored when {@code capacityId} is given. */
                                  String capacity,
                                  /** A pick-list entry; stored on the vehicle as its label. */
                                  Long capacityId,
                                  BigDecimal lengthFt,
                                  List<VehicleDtos.Place> places) {
    }

    /**
     * A correction from the worklist.
     *
     * <p>Every field is optional and <b>null means "not mentioned"</b>, leaving that value as it
     * was. An empty string or an empty list is a deliberate clearing. Without that distinction a
     * request that only fixes the company would silently wipe the plate.
     */
    public record CorrectRequest(String plate, List<String> mobiles, String company,
                                 /** The company's own number; "" clears it. Separate from mobiles. */
                                 String companyMobile,
                                 String driverName, Long bodyTypeId, Long capacityId,
                                 /** [{state_id, city_id|null}] — a null city is the whole state. */
                                 List<Map<String, Object>> places) {
    }

    public record DiscardRequest(String reason) {
    }

    /**
     * The tab counts the worklist shows, so a CSR can see what is waiting without clicking.
     *
     * <p>Named for the questions rather than the columns: {@code toCall} is the only one that is
     * a queue, and it is the pair (review PENDING, processing DONE).
     */
    public record Counts(long toCall, long waiting, long failed,
                         long completed, long discarded, long all) {
    }
}
