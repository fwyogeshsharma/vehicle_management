package com.vehiclemanagement.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * A city, unique per state rather than globally — several states have a Sagar, which is why
 * resolving a place typed as free text has to refuse an ambiguous match instead of guessing.
 */
@Entity
@Table(name = "cities")
public class City {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "state_id", nullable = false)
    private Long stateId;

    @Column(nullable = false, length = 100)
    private String name;

    /** lower(name); the de-duplication and lookup key. Derived, never set by a caller. */
    @Column(name = "name_key", nullable = false, length = 100)
    private String nameKey;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Maintained by the trg_cities_updated trigger, so never written from here. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected City() {
    }

    public City(Long stateId, String name) {
        this.stateId = stateId;
        setName(name);
    }

    public Long getId() { return id; }
    public Long getStateId() { return stateId; }
    public String getName() { return name; }
    public String getNameKey() { return nameKey; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    /** nameKey is never set directly — deriving it here is what keeps the unique index honest. */
    public void setName(String name) {
        this.name = name;
        this.nameKey = name == null ? null : name.toLowerCase();
    }
}
