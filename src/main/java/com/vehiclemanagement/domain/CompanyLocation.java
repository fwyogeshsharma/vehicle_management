package com.vehiclemanagement.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * Where a company operates: a state, optionally narrowed to one city inside it.
 *
 * <p>A null {@link #cityId} means the whole state — "anywhere in Maharashtra". The composite
 * foreign key (city_id, state_id) to cities (id, state_id) makes "Nagpur, Gujarat" a
 * referential impossibility, and under MATCH SIMPLE it skips itself when the city is null,
 * which is exactly the state-only case.
 *
 * <p>The surrogate id exists because a natural primary key cannot contain a nullable column;
 * uniqueness is uq_cxl, declared NULLS NOT DISTINCT so "anywhere in Maharashtra" can be listed
 * once rather than unboundedly.
 */
@Entity
@Table(name = "company_x_location")
public class CompanyLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "state_id", nullable = false)
    private Long stateId;

    /** Null means the whole state. */
    @Column(name = "city_id")
    private Long cityId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected CompanyLocation() {
    }

    public CompanyLocation(Long companyId, Long stateId, Long cityId) {
        this.companyId = companyId;
        this.stateId = stateId;
        this.cityId = cityId;
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public Long getStateId() { return stateId; }
    public Long getCityId() { return cityId; }
    public boolean isWholeState() { return cityId == null; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
