package com.vehiclemanagement.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Who drives a vehicle. One row per person per vehicle.
 *
 * <p><b>This does not record ownership.</b> That is {@code vehicles.owner_company_id} and
 * {@code vehicles.owner_user_id}, where a single-row CHECK can enforce "exactly one owner"
 * against every writer. An owner-operator — the common case in Indian trucking, a driver who
 * owns his own truck — appears in both places, once for each fact: the vehicle row says he owns
 * it, a row here says he drives it.
 *
 * <p>The table used to carry a {@code role} of OWNER or DRIVER. The OWNER rows were a copy of
 * {@code vehicles.owner_user_id} held honest by a composite foreign key over a generated column;
 * 002-driver-only-links.sql removed them and everything that policed them. Nothing was lost —
 * every OWNER row was reconstructable from the vehicle it pointed at.
 */
@Entity
@Table(name = "vehicle_x_user")
@IdClass(VehicleUser.Key.class)
public class VehicleUser {

    @Id
    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    /** At most one per vehicle (uq_vxu_primary_driver). */
    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    /**
     * Mirrors {@code vehicles.owner_company_id}, and is how the database enforces that only a
     * company's own hired drivers ride its trucks: one foreign key pins it to the vehicle, a
     * second pins it to {@code user_x_company}. A row naming the wrong company, or a driver who
     * does not work there, therefore cannot exist.
     *
     * <p>Null for a person-owned vehicle, which is what makes both of those checks skip
     * themselves for an owner-operator. Read off the vehicle by VehicleService, never chosen.
     */
    @Column(name = "owner_company_id")
    private Long ownerCompanyId;

    /** Mirrors {@code vehicles.is_company_owned}; see {@link #ownerCompanyId}. */
    @Column(name = "is_company_owned", nullable = false)
    private boolean companyOwned = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected VehicleUser() {
    }

    /**
     * @param ownerCompanyId the owning company of {@code vehicleId}, or null when a person owns
     *                       it. Must be read off the vehicle, not chosen.
     */
    public VehicleUser(Long vehicleId, Long userId, Long ownerCompanyId) {
        this.vehicleId = vehicleId;
        this.userId = userId;
        this.ownerCompanyId = ownerCompanyId;
        this.companyOwned = ownerCompanyId != null;
    }

    public Long getVehicleId() { return vehicleId; }
    public Long getUserId() { return userId; }
    public Long getOwnerCompanyId() { return ownerCompanyId; }
    public boolean isCompanyOwned() { return companyOwned; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public static class Key implements Serializable {
        private Long vehicleId;
        private Long userId;

        public Key() {
        }

        public Key(Long vehicleId, Long userId) {
            this.vehicleId = vehicleId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(vehicleId, k.vehicleId) && Objects.equals(userId, k.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(vehicleId, userId);
        }
    }
}
