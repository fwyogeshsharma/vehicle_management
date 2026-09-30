package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.City;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CityRepository extends JpaRepository<City, Long> {

    Optional<City> findByStateIdAndNameKey(Long stateId, String nameKey);

    /**
     * Every city with this name, across all states.
     *
     * <p>Returns a list rather than an Optional on purpose: several states have a Sagar, and the
     * caller has to refuse an ambiguous match rather than pick one. See GeoService.
     */
    List<City> findByNameKey(String nameKey);

    List<City> findByStateIdOrderByNameAsc(Long stateId);

    /** Casts before every null test -- see UserRepository.search for why they are not optional. */
    @Query(value = """
            SELECT ci.* FROM cities ci
             WHERE ci.is_active
               AND (CAST(:q AS TEXT) IS NULL OR ci.name ILIKE CAST(:q AS TEXT))
               AND (CAST(:stateId AS BIGINT) IS NULL OR ci.state_id = CAST(:stateId AS BIGINT))
            """,
            countQuery = """
            SELECT count(*) FROM cities ci
             WHERE ci.is_active
               AND (CAST(:q AS TEXT) IS NULL OR ci.name ILIKE CAST(:q AS TEXT))
               AND (CAST(:stateId AS BIGINT) IS NULL OR ci.state_id = CAST(:stateId AS BIGINT))
            """, nativeQuery = true)
    Page<City> search(@Param("q") String q, @Param("stateId") Long stateId, Pageable pageable);

    /** The name_keys already present for a state — one query, so re-seeding does not N+1. */
    @Query(value = "SELECT name_key FROM cities WHERE state_id = :stateId", nativeQuery = true)
    List<String> findNameKeysByStateId(@Param("stateId") Long stateId);
}
