package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.LoadRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface LoadRequestRepository extends JpaRepository<LoadRequest, Long> {

    /**
     * The board, filtered. {@code q} matches the text as recorded -- company, places, goods and
     * the contact -- so a request still turns up under a place that was never in the city list.
     * The date range is inclusive at both ends. Every parameter is cast before its null test:
     * Postgres cannot infer the type of a bare parameter in {@code ? IS NULL}.
     */
    @Query(value = """
            SELECT r.* FROM load_requests r
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR r.company_name ILIKE CAST(:q AS TEXT)
                    OR r.from_place   ILIKE CAST(:q AS TEXT)
                    OR r.to_place     ILIKE CAST(:q AS TEXT)
                    OR r.goods        ILIKE CAST(:q AS TEXT)
                    OR r.contact_name ILIKE CAST(:q AS TEXT))
               AND (CAST(:status AS TEXT) IS NULL OR r.status = CAST(:status AS TEXT))
               AND (CAST(:consignorId AS BIGINT) IS NULL
                    OR r.consignor_id = CAST(:consignorId AS BIGINT))
               AND (CAST(:dateFrom AS DATE) IS NULL OR r.request_date >= CAST(:dateFrom AS DATE))
               AND (CAST(:dateTo AS DATE) IS NULL OR r.request_date <= CAST(:dateTo AS DATE))
            """,
            countQuery = """
            SELECT count(*) FROM load_requests r
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR r.company_name ILIKE CAST(:q AS TEXT)
                    OR r.from_place   ILIKE CAST(:q AS TEXT)
                    OR r.to_place     ILIKE CAST(:q AS TEXT)
                    OR r.goods        ILIKE CAST(:q AS TEXT)
                    OR r.contact_name ILIKE CAST(:q AS TEXT))
               AND (CAST(:status AS TEXT) IS NULL OR r.status = CAST(:status AS TEXT))
               AND (CAST(:consignorId AS BIGINT) IS NULL
                    OR r.consignor_id = CAST(:consignorId AS BIGINT))
               AND (CAST(:dateFrom AS DATE) IS NULL OR r.request_date >= CAST(:dateFrom AS DATE))
               AND (CAST(:dateTo AS DATE) IS NULL OR r.request_date <= CAST(:dateTo AS DATE))
            """,
            nativeQuery = true)
    Page<LoadRequest> search(@Param("q") String q,
                             @Param("status") String status,
                             @Param("consignorId") Long consignorId,
                             @Param("dateFrom") LocalDate dateFrom,
                             @Param("dateTo") LocalDate dateTo,
                             Pageable page);
}
