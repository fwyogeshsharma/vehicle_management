package com.vehiclemanagement.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * A party that sends or receives goods.
 *
 * <p><b>Not a {@link Company}.</b> That is a transport company — it owns vehicles, employs
 * drivers, and every ownership rule in {@code VehicleService} is about it. A consignor owns
 * none of our trucks and employs none of our people; it is a customer. Keeping them apart is
 * what stops those rules having to ask which kind of company they are holding.
 *
 * <p>One row serves both ends of a consignment. The same firm sends a load on Monday and
 * receives one on Friday, and two tables would duplicate it the first time that happened.
 */
@Entity
@Table(name = "consignors")
public class Consignor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String name;

    /** Lower-cased {@link #name}; the key duplicate detection and find-or-create work on. */
    @Column(name = "name_key", nullable = false, length = 160, unique = true)
    private String nameKey;

    @Column(length = 10)
    private String mobile;

    @Column(length = 300)
    private String address;

    @Column(length = 15)
    private String gstin;

    /**
     * Retired rather than deleted. Lorry receipts point here ON DELETE SET NULL, so a delete
     * would quietly strip the link off every historic LR for this customer.
     */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected Consignor() {
    }

    public Consignor(String name, String mobile) {
        setName(name);
        this.mobile = mobile;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getNameKey() { return nameKey; }
    public String getMobile() { return mobile; }
    public void setMobile(String v) { this.mobile = v; }
    public String getAddress() { return address; }
    public void setAddress(String v) { this.address = v; }
    public String getGstin() { return gstin; }
    public void setGstin(String v) { this.gstin = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) {
        this.name = name;
        this.nameKey = name == null ? null : name.toLowerCase();
    }
}
