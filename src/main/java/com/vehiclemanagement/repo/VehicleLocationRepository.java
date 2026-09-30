package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.VehicleLocation;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VehicleLocationRepository extends JpaRepository<VehicleLocation, Long> {

    List<VehicleLocation> findByVehicleId(Long vehicleId);

    /**
     * Drop a vehicle's own preferred locations.
     *
     * <p>Must run before an UPDATE that hands the vehicle to a company: fk_vxl_vehicle is
     * ON UPDATE NO ACTION, so the sale is rejected outright while these rows exist.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM vehicle_x_location WHERE vehicle_id = :vehicleId", nativeQuery = true)
    int deleteByVehicleId(@Param("vehicleId") Long vehicleId);
}
