package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.CompanyLocation;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CompanyLocationRepository extends JpaRepository<CompanyLocation, Long> {

    List<CompanyLocation> findByCompanyId(Long companyId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM company_x_location WHERE company_id = :companyId", nativeQuery = true)
    int deleteByCompanyId(@Param("companyId") Long companyId);
}
