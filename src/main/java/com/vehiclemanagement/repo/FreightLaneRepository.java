package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.FreightLane;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface FreightLaneRepository extends JpaRepository<FreightLane, Long> {

    /**
     * The register, filtered.
     *
     * <p>Matched against the <b>snapshot</b> columns, as the receipt search is: somebody
     * looking for "Meerut" wants the lanes that say Meerut, including ones whose city row was
     * since retired, and including places that were never cities.
     *
     * <p>Every parameter is cast before its null test — Postgres cannot infer the type of a
     * bare parameter in {@code ? IS NULL}.
     */
    @Query(value = """
            SELECT l.* FROM freight_lanes l
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR l.from_place ILIKE CAST(:q AS TEXT)
                    OR l.to_place   ILIKE CAST(:q AS TEXT)
                    OR l.goods      ILIKE CAST(:q AS TEXT)
                    OR l.source       ILIKE CAST(:q AS TEXT)
                    OR l.company_name ILIKE CAST(:q AS TEXT)
                    OR l.season       ILIKE CAST(:q AS TEXT))
               AND (CAST(:active AS BOOLEAN) IS NULL OR l.is_active = CAST(:active AS BOOLEAN))
               AND (CAST(:fromCityId AS BIGINT) IS NULL
                    OR l.from_city_id = CAST(:fromCityId AS BIGINT))
               AND (CAST(:toCityId AS BIGINT) IS NULL
                    OR l.to_city_id = CAST(:toCityId AS BIGINT))
               AND (CAST(:goodsTypeId AS BIGINT) IS NULL
                    OR l.goods_type_id = CAST(:goodsTypeId AS BIGINT))
               AND (CAST(:seasonal AS BOOLEAN) IS NULL
                    OR l.is_seasonal = CAST(:seasonal AS BOOLEAN))
            """,
            countQuery = """
            SELECT count(*) FROM freight_lanes l
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR l.from_place ILIKE CAST(:q AS TEXT)
                    OR l.to_place   ILIKE CAST(:q AS TEXT)
                    OR l.goods      ILIKE CAST(:q AS TEXT)
                    OR l.source       ILIKE CAST(:q AS TEXT)
                    OR l.company_name ILIKE CAST(:q AS TEXT)
                    OR l.season       ILIKE CAST(:q AS TEXT))
               AND (CAST(:active AS BOOLEAN) IS NULL OR l.is_active = CAST(:active AS BOOLEAN))
               AND (CAST(:fromCityId AS BIGINT) IS NULL
                    OR l.from_city_id = CAST(:fromCityId AS BIGINT))
               AND (CAST(:toCityId AS BIGINT) IS NULL
                    OR l.to_city_id = CAST(:toCityId AS BIGINT))
               AND (CAST(:goodsTypeId AS BIGINT) IS NULL
                    OR l.goods_type_id = CAST(:goodsTypeId AS BIGINT))
               AND (CAST(:seasonal AS BOOLEAN) IS NULL
                    OR l.is_seasonal = CAST(:seasonal AS BOOLEAN))
            """,
            nativeQuery = true)
    Page<FreightLane> search(@Param("q") String q,
                             @Param("active") Boolean active,
                             @Param("fromCityId") Long fromCityId,
                             @Param("toCityId") Long toCityId,
                             @Param("goodsTypeId") Long goodsTypeId,
                             @Param("seasonal") Boolean seasonal,
                             Pageable page);

}
