package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.VehicleUser;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Driver assignments. Ownership is on the vehicle row — see {@link VehicleUser}. */
public interface VehicleUserRepository
        extends JpaRepository<VehicleUser, VehicleUser.Key> {

    List<VehicleUser> findByVehicleId(Long vehicleId);

    List<VehicleUser> findByUserId(Long userId);

    /**
     * Clear every driver on a vehicle, immediately.
     *
     * <p>{@code flushAutomatically} and {@code clearAutomatically} both matter: an ownership
     * change needs this DELETE to reach the database before the vehicle row is updated, and
     * Hibernate's own flush order is insert-before-delete.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM vehicle_x_user WHERE vehicle_id = :vehicleId", nativeQuery = true)
    int deleteByVehicleId(@Param("vehicleId") Long vehicleId);

    /**
     * How many of this company's trucks this person drives.
     *
     * <p>Counted BEFORE their employment ends, because fk_vxu_employed is ON DELETE CASCADE and
     * the rows are already gone by the time the delete returns. The API reports the number rather
     * than letting the assignments vanish silently.
     */
    long countByUserIdAndOwnerCompanyId(Long userId, Long ownerCompanyId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM vehicle_x_user WHERE vehicle_id = :vehicleId AND user_id = :userId",
           nativeQuery = true)
    int deleteByVehicleIdAndUserId(@Param("vehicleId") Long vehicleId,
                                   @Param("userId") Long userId);

    /**
     * Demote whoever is currently primary on this vehicle.
     *
     * <p>Has to reach the database before the new primary is written: uq_vxu_primary_driver is a
     * partial unique index over (vehicle_id) WHERE is_primary, so promoting a second driver
     * without this fails. Hibernate left alone would order the two the other way round.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "UPDATE vehicle_x_user SET is_primary = FALSE "
                 + "WHERE vehicle_id = :vehicleId AND is_primary", nativeQuery = true)
    int clearPrimary(@Param("vehicleId") Long vehicleId);
}
