package com.vehiclemanagement.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * What shape of body a vehicle has: open, container, tanker, tipper.
 *
 * <p>Retire one with {@code active = false} rather than deleting it — vehicles reference it
 * with ON DELETE RESTRICT, so a delete under a live fleet is refused.
 */
@Entity
@Table(name = "body_types")
public class BodyType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "name_key", nullable = false, length = 80, unique = true)
    private String nameKey;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected BodyType() {
    }

    public BodyType(String name) {
        setName(name);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getNameKey() { return nameKey; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) {
        this.name = name;
        this.nameKey = name == null ? null : name.toLowerCase();
    }
}
