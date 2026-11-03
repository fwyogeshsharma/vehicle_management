package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.LoadProviderContract;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoadProviderContractRepository extends JpaRepository<LoadProviderContract, Long> {

    /**
     * The register, filtered. {@code q} matches the company and the location as recorded, so a
     * contract still turns up under a place that was never in the city list. Parameters are cast
     * before their null test: Postgres cannot infer the type of a bare parameter in
     * {@code ? IS NULL}.
     */
    @Query(value = """
            SELECT c.* FROM load_provider_contracts c
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR c.company_name ILIKE CAST(:q AS TEXT)
                    OR c.location     ILIKE CAST(:q AS TEXT))
               AND (CAST(:active AS BOOLEAN) IS NULL OR c.is_active = CAST(:active AS BOOLEAN))
            """,
            countQuery = """
            SELECT count(*) FROM load_provider_contracts c
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR c.company_name ILIKE CAST(:q AS TEXT)
                    OR c.location     ILIKE CAST(:q AS TEXT))
               AND (CAST(:active AS BOOLEAN) IS NULL OR c.is_active = CAST(:active AS BOOLEAN))
            """,
            nativeQuery = true)
    Page<LoadProviderContract> search(@Param("q") String q,
                                      @Param("active") Boolean active,
                                      Pageable page);
}
