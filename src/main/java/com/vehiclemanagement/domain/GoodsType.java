package com.vehiclemanagement.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * What a consignment is made of: cement, steel, cotton.
 *
 * <p><b>A pick list, not a foreign-key vocabulary</b> — the same call {@link Capacity} makes.
 * {@code lorry_receipts.goods_description} stays free text and nothing forces it to match a row
 * here. The first load of something nobody has carried before must not wait for an
 * administrator.
 */
@Entity
@Table(name = "goods_types")
public class GoodsType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "name_key", nullable = false, length = 160, unique = true)
    private String nameKey;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected GoodsType() {
    }

    public GoodsType(String name) {
        setName(name);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getNameKey() { return nameKey; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) {
        this.name = name;
        this.nameKey = name == null ? null : name.toLowerCase();
    }
}
