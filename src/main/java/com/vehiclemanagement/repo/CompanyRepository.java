package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.Company;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByNameKey(String nameKey);

    /**
     * Where each of these companies operates, summarised, one row per company.
     *
     * <p>Batched over a page of ids rather than fetched per row — the same reason
     * {@code VehicleRepository.contactsFor} is batched, and the same N+1 avoided.
     *
     * <p>A company's locations are what <b>every truck it owns</b> inherits, so this is not a
     * decorative column: it is the operating area of the whole fleet, and an empty one means
     * none of its vehicles match any city search.
     */
    @Query(value = """
            SELECT company_id                                            AS companyId,
                   count(*)                                              AS total,
                   string_agg(label, ', ' ORDER BY label)
                       FILTER (WHERE rn <= 3)                            AS summary
              FROM (
                    SELECT cl.company_id,
                           COALESCE(c.name, s.name || ' (all)')          AS label,
                           row_number() OVER (PARTITION BY cl.company_id
                                              ORDER BY COALESCE(c.name, s.name)) AS rn
                      FROM company_x_location cl
                      JOIN states s      ON s.id = cl.state_id
                      LEFT JOIN cities c ON c.id = cl.city_id
                     WHERE cl.company_id IN (:ids)
                   ) ranked
             GROUP BY company_id
            """, nativeQuery = true)
    List<LocationSummary> locationsFor(@Param("ids") java.util.Collection<Long> ids);

    /** Projection for {@link #locationsFor}. */
    interface LocationSummary {
        Long getCompanyId();

        long getTotal();

        /** The first three by name. A state with no city reads "Punjab (all)". */
        String getSummary();
    }

    boolean existsByNameKey(String nameKey);

    boolean existsByNameKeyAndIdNot(String nameKey, Long id);

    /** Casts before every null test -- see UserRepository.search for why they are not optional. */
    @Query(value = """
            SELECT c.* FROM companies c
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR c.name ILIKE CAST(:q AS TEXT) OR c.gstin ILIKE CAST(:q AS TEXT))
               AND (CAST(:active AS BOOLEAN) IS NULL OR c.is_active = CAST(:active AS BOOLEAN))
            """,
            countQuery = """
            SELECT count(*) FROM companies c
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR c.name ILIKE CAST(:q AS TEXT) OR c.gstin ILIKE CAST(:q AS TEXT))
               AND (CAST(:active AS BOOLEAN) IS NULL OR c.is_active = CAST(:active AS BOOLEAN))
            """, nativeQuery = true)
    Page<Company> search(@Param("q") String q,
                         @Param("active") Boolean active,
                         Pageable pageable);
}
