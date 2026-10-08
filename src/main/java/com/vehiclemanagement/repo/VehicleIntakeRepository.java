package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.ProcessingStatus;
import com.vehiclemanagement.domain.ReviewStatus;
import com.vehiclemanagement.domain.VehicleIntake;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

/**
 * The CSR's half of {@code vehicle_intake}.
 *
 * <p><b>There is no claim query here, and that is the architecture, not an omission.</b> The OCR
 * worker polls this table over its own PostgreSQL connection; the {@code FOR UPDATE SKIP LOCKED}
 * claim, the staleness reclaim and the writes to {@code processing_status} and the {@code ocr_*}
 * columns all live in {@code vehicleManagementOcr/ocr/db.py}. Everything in this interface reads
 * or writes the <i>human</i> dimension.
 *
 * <p>The cost of that split is real and worth stating where someone will trip over it: the table
 * now has two writers in two languages, so a column added here must be added there too, and
 * {@code ddl-auto: validate} will not catch a drift on the Python side.
 *
 * <p><b>No {@code Sort} parameters.</b> Every ordering below is spelled into the method name.
 * Spring Data resolves a {@code Sort} on a derived query against <i>entity property</i> names
 * while our {@code Sorts} helper yields <i>column</i> names, so passing one here fails at
 * runtime rather than compile time. See the note in {@code Sorts}.
 */
public interface VehicleIntakeRepository extends JpaRepository<VehicleIntake, Long> {

    /**
     * The worklist, sliced by both dimensions at once.
     *
     * <p>Oldest first: a photo that has been waiting since this morning should be called before
     * one taken ten minutes ago.
     */
    Page<VehicleIntake> findByReviewStatusAndProcessingStatusInOrderByCreatedAtAsc(
            ReviewStatus reviewStatus, Collection<ProcessingStatus> processingStatuses,
            Pageable pageable);

    /** Everything a CSR has already dealt with. Newest first — this is a history, not a queue. */
    Page<VehicleIntake> findByReviewStatusOrderByCreatedAtDesc(
            ReviewStatus reviewStatus, Pageable pageable);

    Page<VehicleIntake> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByReviewStatusAndProcessingStatusIn(
            ReviewStatus reviewStatus, Collection<ProcessingStatus> processingStatuses);

    long countByReviewStatus(ReviewStatus reviewStatus);

    /**
     * The worklist, filtered by registration number and/or location.
     *
     * <p>Native, so the tab arrives as its two coordinates rather than as a method name:
     * {@code review} is a {@link ReviewStatus} name and {@code processing} a comma-separated list
     * of {@link ProcessingStatus} names, either null for "any". Every parameter is cast before
     * the null test; PostgreSQL cannot type a bare {@code :p IS NULL}.
     *
     * @param plate      a LIKE pattern over the plate reduced to {@code [A-Z0-9]}, matched
     *                   against the CSR's, the reporter's and OCR's plates and, once completed,
     *                   the vehicle's own registration. Spaces and dashes in either the search or
     *                   the stored value do not matter.
     * @param location   an ILIKE pattern over the address the app reported and the names of the
     *                   states and cities the CSR noted.
     * @param oldestFirst true for the queue tabs, false for the history tabs, matching the
     *                   derived queries above.
     */
    @Query(value = """
            SELECT i.* FROM vehicle_intake i
             WHERE (CAST(:review AS TEXT) IS NULL OR i.review_status = CAST(:review AS TEXT))
               AND (CAST(:processing AS TEXT) IS NULL
                    OR i.processing_status = ANY (string_to_array(CAST(:processing AS TEXT), ',')))
               AND (CAST(:plate AS TEXT) IS NULL
                    OR regexp_replace(upper(coalesce(i.edited_plate, '')), '[^A-Z0-9]', '', 'g')
                         LIKE CAST(:plate AS TEXT)
                    OR regexp_replace(upper(coalesce(i.reported_plate, '')), '[^A-Z0-9]', '', 'g')
                         LIKE CAST(:plate AS TEXT)
                    OR regexp_replace(upper(coalesce(i.ocr_plate, '')), '[^A-Z0-9]', '', 'g')
                         LIKE CAST(:plate AS TEXT)
                    OR i.vehicle_id IN (SELECT v.id FROM vehicles v
                                         WHERE v.registration_number LIKE CAST(:plate AS TEXT)))
               AND (CAST(:location AS TEXT) IS NULL
                    OR i.location ILIKE CAST(:location AS TEXT)
                    OR EXISTS (SELECT 1
                                 FROM jsonb_array_elements(coalesce(i.edited_places, CAST('[]' AS JSONB))) p
                                 LEFT JOIN states s ON s.id = CAST(p ->> 'state_id' AS BIGINT)
                                 LEFT JOIN cities c ON c.id = CAST(p ->> 'city_id' AS BIGINT)
                                WHERE s.name ILIKE CAST(:location AS TEXT)
                                   OR c.name ILIKE CAST(:location AS TEXT)))
             ORDER BY CASE WHEN CAST(:oldestFirst AS BOOLEAN) THEN i.created_at END ASC,
                      i.created_at DESC, i.id
            """,
            countQuery = """
            SELECT count(*) FROM vehicle_intake i
             WHERE (CAST(:review AS TEXT) IS NULL OR i.review_status = CAST(:review AS TEXT))
               AND (CAST(:processing AS TEXT) IS NULL
                    OR i.processing_status = ANY (string_to_array(CAST(:processing AS TEXT), ',')))
               AND (CAST(:plate AS TEXT) IS NULL
                    OR regexp_replace(upper(coalesce(i.edited_plate, '')), '[^A-Z0-9]', '', 'g')
                         LIKE CAST(:plate AS TEXT)
                    OR regexp_replace(upper(coalesce(i.reported_plate, '')), '[^A-Z0-9]', '', 'g')
                         LIKE CAST(:plate AS TEXT)
                    OR regexp_replace(upper(coalesce(i.ocr_plate, '')), '[^A-Z0-9]', '', 'g')
                         LIKE CAST(:plate AS TEXT)
                    OR i.vehicle_id IN (SELECT v.id FROM vehicles v
                                         WHERE v.registration_number LIKE CAST(:plate AS TEXT)))
               AND (CAST(:location AS TEXT) IS NULL
                    OR i.location ILIKE CAST(:location AS TEXT)
                    OR EXISTS (SELECT 1
                                 FROM jsonb_array_elements(coalesce(i.edited_places, CAST('[]' AS JSONB))) p
                                 LEFT JOIN states s ON s.id = CAST(p ->> 'state_id' AS BIGINT)
                                 LEFT JOIN cities c ON c.id = CAST(p ->> 'city_id' AS BIGINT)
                                WHERE s.name ILIKE CAST(:location AS TEXT)
                                   OR c.name ILIKE CAST(:location AS TEXT)))
            """, nativeQuery = true)
    Page<VehicleIntake> search(@Param("review") String review,
                               @Param("processing") String processing,
                               @Param("plate") String plate,
                               @Param("location") String location,
                               @Param("oldestFirst") boolean oldestFirst,
                               Pageable pageable);

    /** {@link #search}'s count alone, for the tab badges. */
    @Query(value = """
            SELECT count(*) FROM vehicle_intake i
             WHERE (CAST(:review AS TEXT) IS NULL OR i.review_status = CAST(:review AS TEXT))
               AND (CAST(:processing AS TEXT) IS NULL
                    OR i.processing_status = ANY (string_to_array(CAST(:processing AS TEXT), ',')))
               AND (CAST(:plate AS TEXT) IS NULL
                    OR regexp_replace(upper(coalesce(i.edited_plate, '')), '[^A-Z0-9]', '', 'g')
                         LIKE CAST(:plate AS TEXT)
                    OR regexp_replace(upper(coalesce(i.reported_plate, '')), '[^A-Z0-9]', '', 'g')
                         LIKE CAST(:plate AS TEXT)
                    OR regexp_replace(upper(coalesce(i.ocr_plate, '')), '[^A-Z0-9]', '', 'g')
                         LIKE CAST(:plate AS TEXT)
                    OR i.vehicle_id IN (SELECT v.id FROM vehicles v
                                         WHERE v.registration_number LIKE CAST(:plate AS TEXT)))
               AND (CAST(:location AS TEXT) IS NULL
                    OR i.location ILIKE CAST(:location AS TEXT)
                    OR EXISTS (SELECT 1
                                 FROM jsonb_array_elements(coalesce(i.edited_places, CAST('[]' AS JSONB))) p
                                 LEFT JOIN states s ON s.id = CAST(p ->> 'state_id' AS BIGINT)
                                 LEFT JOIN cities c ON c.id = CAST(p ->> 'city_id' AS BIGINT)
                                WHERE s.name ILIKE CAST(:location AS TEXT)
                                   OR c.name ILIKE CAST(:location AS TEXT)))
            """, nativeQuery = true)
    long countSearch(@Param("review") String review,
                     @Param("processing") String processing,
                     @Param("plate") String plate,
                     @Param("location") String location);
}
