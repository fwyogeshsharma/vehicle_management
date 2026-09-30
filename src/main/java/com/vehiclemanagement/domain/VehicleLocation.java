package com.vehiclemanagement.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * Where <b>this vehicle</b> runs: a state, optionally narrowed to one city.
 *
 * <p><b>Additive to its company's, not an alternative.</b> A company-owned truck may hold these
 * alongside whatever {@code company_x_location} says, and
 * {@code vehicle_effective_locations} returns both, tagging each row COMPANY or VEHICLE. Until
 * changeset 009 these rows were forbidden on a company's truck — a CHECK, a discriminator column
 * and a composite foreign key all enforced it — but that made the ordinary case unsayable: a
 * fleet covering Maharashtra with one truck on a Nagpur shuttle could only be expressed by
 * rerouting the whole company.
 *
 * <p>They are still deleted on a sale rather than left dormant. Dormant rows come back to life
 * if the vehicle changes hands again, and a route from two owners ago silently matching loads is
 * worse than re-entering it.
 */
@Entity
@Table(name = "vehicle_x_location")
public class VehicleLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "state_id", nullable = false)
    private Long stateId;

    /** Null means the whole state. */
    @Column(name = "city_id")
    private Long cityId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected VehicleLocation() {
    }

    public VehicleLocation(Long vehicleId, Long stateId, Long cityId) {
        this.vehicleId = vehicleId;
        this.stateId = stateId;
        this.cityId = cityId;
    }

    public Long getId() { return id; }
    public Long getVehicleId() { return vehicleId; }
    public Long getStateId() { return stateId; }
    public Long getCityId() { return cityId; }
    public boolean isWholeState() { return cityId == null; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
