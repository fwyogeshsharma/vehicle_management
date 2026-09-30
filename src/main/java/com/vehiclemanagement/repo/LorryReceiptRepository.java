package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.LorryReceipt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface LorryReceiptRepository extends JpaRepository<LorryReceipt, Long> {

    boolean existsByLrNumber(String lrNumber);

    boolean existsByLrNumberAndIdNot(String lrNumber, Long id);

    /**
     * Take the next number in a series, holding it against every other writer.
     *
     * <p>Paired with {@link #bumpCounter}, inside the caller's transaction: this locks the
     * counter row, the bump advances it, and the lock is released at commit. A second
     * transaction wanting a number waits here rather than reading the same value.
     *
     * <p><b>Why not a sequence.</b> A sequence is race-free but not transactional — a rolled
     * back insert burns its number and leaves a hole. For a consignment series that hole is a
     * question an auditor asks, so the row lock is the right trade: LRs are typed by people,
     * and serialising them costs nothing worth measuring.
     */
    @Query(value = "SELECT next_value FROM lr_counters WHERE series = :series FOR UPDATE",
            nativeQuery = true)
    Integer lockNextNumber(@Param("series") String series);

    @Modifying
    @Query(value = "UPDATE lr_counters SET next_value = next_value + 1 WHERE series = :series",
            nativeQuery = true)
    void bumpCounter(@Param("series") String series);

    /** Reads the counter without locking, for the "what will the next one be" preview. */
    @Query(value = "SELECT next_value FROM lr_counters WHERE series = :series", nativeQuery = true)
    Integer peekNextNumber(@Param("series") String series);

    /**
     * The register, filtered.
     *
     * <p>{@code q} is matched against the LR number and the <b>snapshot</b> columns, not the
     * masters: someone searching "Kumar" wants the receipts that say Kumar on them, including
     * the ones whose consignor row was since renamed or retired.
     *
     * <p>Every parameter is cast before its null test — Postgres cannot infer the type of a
     * bare parameter in {@code ? IS NULL} and fails the whole statement, which is why the same
     * casts appear in {@code UserRepository.search}.
     */
    @Query(value = """
            SELECT l.* FROM lorry_receipts l
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR l.lr_number      ILIKE CAST(:q AS TEXT)
                    OR l.consignor_name ILIKE CAST(:q AS TEXT)
                    OR l.consignee_name ILIKE CAST(:q AS TEXT)
                    OR l.vehicle_number ILIKE CAST(:q AS TEXT)
                    OR l.driver_name    ILIKE CAST(:q AS TEXT)
                    OR l.from_place     ILIKE CAST(:q AS TEXT)
                    OR l.to_place       ILIKE CAST(:q AS TEXT))
               AND (CAST(:status AS TEXT) IS NULL OR l.status = CAST(:status AS TEXT))
               AND (CAST(:fromDate AS DATE) IS NULL OR l.lr_date >= CAST(:fromDate AS DATE))
               AND (CAST(:toDate   AS DATE) IS NULL OR l.lr_date <= CAST(:toDate   AS DATE))
               AND (CAST(:vehicleId AS BIGINT) IS NULL
                    OR l.vehicle_id = CAST(:vehicleId AS BIGINT))
               AND (CAST(:driverId  AS BIGINT) IS NULL
                    OR l.driver_user_id = CAST(:driverId AS BIGINT))
               AND (CAST(:partyId   AS BIGINT) IS NULL
                    OR l.consignor_id = CAST(:partyId AS BIGINT)
                    OR l.consignee_id = CAST(:partyId AS BIGINT))
               AND (CAST(:unpaid AS BOOLEAN) IS NOT TRUE
                    OR (l.balance > 0 AND l.status <> 'CANCELLED'))
            """,
            countQuery = """
            SELECT count(*) FROM lorry_receipts l
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR l.lr_number      ILIKE CAST(:q AS TEXT)
                    OR l.consignor_name ILIKE CAST(:q AS TEXT)
                    OR l.consignee_name ILIKE CAST(:q AS TEXT)
                    OR l.vehicle_number ILIKE CAST(:q AS TEXT)
                    OR l.driver_name    ILIKE CAST(:q AS TEXT)
                    OR l.from_place     ILIKE CAST(:q AS TEXT)
                    OR l.to_place       ILIKE CAST(:q AS TEXT))
               AND (CAST(:status AS TEXT) IS NULL OR l.status = CAST(:status AS TEXT))
               AND (CAST(:fromDate AS DATE) IS NULL OR l.lr_date >= CAST(:fromDate AS DATE))
               AND (CAST(:toDate   AS DATE) IS NULL OR l.lr_date <= CAST(:toDate   AS DATE))
               AND (CAST(:vehicleId AS BIGINT) IS NULL
                    OR l.vehicle_id = CAST(:vehicleId AS BIGINT))
               AND (CAST(:driverId  AS BIGINT) IS NULL
                    OR l.driver_user_id = CAST(:driverId AS BIGINT))
               AND (CAST(:partyId   AS BIGINT) IS NULL
                    OR l.consignor_id = CAST(:partyId AS BIGINT)
                    OR l.consignee_id = CAST(:partyId AS BIGINT))
               AND (CAST(:unpaid AS BOOLEAN) IS NOT TRUE
                    OR (l.balance > 0 AND l.status <> 'CANCELLED'))
            """,
            nativeQuery = true)
    Page<LorryReceipt> search(@Param("q") String q,
                              @Param("status") String status,
                              @Param("fromDate") LocalDate fromDate,
                              @Param("toDate") LocalDate toDate,
                              @Param("vehicleId") Long vehicleId,
                              @Param("driverId") Long driverId,
                              @Param("partyId") Long partyId,
                              @Param("unpaid") Boolean unpaid,
                              Pageable page);

    /**
     * The numbers behind the register's header: how many are open, and what is outstanding.
     *
     * <p>One query rather than four counts, because they are read together every time the page
     * loads and separately never.
     */
    @Query(value = """
            SELECT count(*)                                                   AS total,
                   count(*) FILTER (WHERE status = 'BOOKED')                  AS booked,
                   count(*) FILTER (WHERE status = 'IN_TRANSIT')              AS inTransit,
                   count(*) FILTER (WHERE status = 'DELIVERED')               AS delivered,
                   count(*) FILTER (WHERE status = 'CANCELLED')               AS cancelled,
                   -- Not scoped to open receipts: delivery is when payment falls due, so a
                   -- delivered-but-unpaid consignment is precisely the money being chased.
                   COALESCE(sum(balance) FILTER (
                       WHERE status <> 'CANCELLED' AND balance > 0), 0) AS outstanding
              FROM lorry_receipts
            """, nativeQuery = true)
    Totals totals();

    /** Projection for {@link #totals}. */
    interface Totals {
        long getTotal();

        long getBooked();

        long getInTransit();

        long getDelivered();

        long getCancelled();

        java.math.BigDecimal getOutstanding();
    }
}
