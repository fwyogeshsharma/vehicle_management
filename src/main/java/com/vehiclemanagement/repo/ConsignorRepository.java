package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.Consignor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConsignorRepository extends JpaRepository<Consignor, Long> {

    Optional<Consignor> findByNameKey(String nameKey);

    boolean existsByNameKeyAndIdNot(String nameKey, Long id);

    List<Consignor> findByActiveTrueOrderByNameAsc();

    /** Casts before every null test -- see UserRepository.search for why they are not optional. */
    @Query(value = """
            SELECT c.* FROM consignors c
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR c.name ILIKE CAST(:q AS TEXT) OR c.mobile ILIKE CAST(:q AS TEXT))
               AND (CAST(:active AS BOOLEAN) IS NULL OR c.is_active = CAST(:active AS BOOLEAN))
            """,
            countQuery = """
            SELECT count(*) FROM consignors c
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR c.name ILIKE CAST(:q AS TEXT) OR c.mobile ILIKE CAST(:q AS TEXT))
               AND (CAST(:active AS BOOLEAN) IS NULL OR c.is_active = CAST(:active AS BOOLEAN))
            """,
            nativeQuery = true)
    Page<Consignor> search(@Param("q") String q, @Param("active") Boolean active, Pageable page);
}
