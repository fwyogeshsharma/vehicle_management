package com.vehiclemanagement.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A rung on the capacity pick list.
 *
 * <p><b>Not a foreign key from anywhere.</b> {@code vehicles.capacity} is free text and stays
 * that way: {@code capacity_tons} is generated from it by regex, and the first third-party
 * import will arrive saying "16T", "16 Ton" and "16.5 MT". This table decides what a dropdown
 * offers; it does not decide what may be stored.
 *
 * <p>{@link #tons} exists to sort the list. Alphabetically, "9 Ton" lands between "16 Ton" and
 * "25 Ton", which is the kind of list nobody trusts.
 */
@Entity
@Table(name = "capacities")
public class Capacity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Exactly what is written to {@code vehicles.capacity} when this is chosen. */
    @Column(nullable = false, length = 32)
    private String label;

    @Column(nullable = false)
    private BigDecimal tons;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected Capacity() {
    }

    public Capacity(String label, BigDecimal tons) {
        this.label = label;
        this.tons = tons;
    }

    public Long getId() { return id; }
    public String getLabel() { return label; }
    public BigDecimal getTons() { return tons; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
