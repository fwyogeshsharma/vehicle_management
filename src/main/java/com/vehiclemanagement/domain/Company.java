package com.vehiclemanagement.domain;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import jakarta.persistence.*;

import java.time.OffsetDateTime;

/** A transport company: owns vehicles, employs people, and operates in a set of locations. */
@Entity
@Table(name = "companies")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "name_key", nullable = false, length = 160, unique = true)
    private String nameKey;

    @Column(length = 10)
    private String mobile;

    @Column(length = 255)
    private String email;

    /** Nullable, but unique when present. Format checked by ck_companies_gstin. */
    @Column(length = 15, unique = true)
    private String gstin;

    @Column(length = 300)
    private String address;

    /**
     * Where the company is registered — NOT where it operates. Its preferred locations live in
     * company_x_location. The long name is deliberate: the two get confused otherwise.
     */
    @Column(name = "head_office_city_id")
    private Long headOfficeCityId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Maintained by a database trigger, and read back after every write.
     *
     * <p>Without {@code @Generated} the trigger updates the row and the object keeps the old
     * value, so a PUT response reports a timestamp from before the edit it just made.
     */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected Company() {
    }

    public Company(String name) {
        setName(name);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getNameKey() { return nameKey; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getGstin() { return gstin; }
    public void setGstin(String gstin) { this.gstin = gstin; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public Long getHeadOfficeCityId() { return headOfficeCityId; }
    public void setHeadOfficeCityId(Long cityId) { this.headOfficeCityId = cityId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) {
        this.name = name;
        this.nameKey = name == null ? null : name.toLowerCase();
    }
}
