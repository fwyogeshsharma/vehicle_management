package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.ProcessingStatus;
import com.vehiclemanagement.domain.ReviewStatus;
import com.vehiclemanagement.domain.VehicleIntake;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
