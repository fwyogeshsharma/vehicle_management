package com.vehiclemanagement.domain;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * A person: a driver, an owner, office staff, an administrator.
 *
 * <p>One table for the directory and the login accounts both. A driver who never signs in is a
 * row with a null username and a null password hash; staff who do sign in have both. The
 * database enforces "both or neither" (ck_users_login_pair), because a username nobody can
 * authenticate — or a hash nobody can reach — is the failure mode of merging the two.
 *
 * <p>The mobile number is the natural key: one person, one number. That breaks the day someone
 * re-registers with a number already in the table, so a merge path is needed before the first
 * real import, not after.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    /** Normalised to 10 digits by Normalizer.mobile, and checked again by ck_users_mobile. */
    @Column(nullable = false, length = 10, unique = true)
    private String mobile;

    @Column(name = "alt_mobile", length = 10)
    private String altMobile;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false, length = 16)
    private UserType userType;

    @Column(length = 255)
    private String email;

    @Column(length = 64, unique = true)
    private String username;

    /**
      * What they may do here. Null for anyone without a login, which is most of this table.
      * Distinct from {@link #userType}, which is what they are.
      */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private UserRole role;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    /**
     * When the hash was last set. A bearer token issued before this moment is rejected — which
     * is the only way a stateless JWT gets revoked. Null means "never changed since created".
     */
    @Column(name = "password_changed_at")
    private OffsetDateTime passwordChangedAt;

    @Column(name = "city_id")
    private Long cityId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(length = 2000)
    private String notes;

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

    protected User() {
    }

    public User(String name, String mobile, UserType userType) {
        this.name = name;
        this.mobile = mobile;
        this.userType = userType;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public String getAltMobile() { return altMobile; }
    public void setAltMobile(String altMobile) { this.altMobile = altMobile; }
    public UserType getUserType() { return userType; }
    public void setUserType(UserType userType) { this.userType = userType; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public Long getCityId() { return cityId; }
    public void setCityId(Long cityId) { this.cityId = cityId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getPasswordChangedAt() { return passwordChangedAt; }

    /**
     * Set together or not at all — the database refuses half a login (ck_users_login_pair).
     *
     * <p>Stamps {@link #passwordChangedAt}, so every token issued before this call stops being
     * accepted. That is deliberate and is the whole revocation story: forget the stamp and a
     * stolen token outlives the password it was obtained with.
     */
    /**
      * Grant a login. A role is required, and the database agrees: {@code ck_users_role_pair}
      * makes "can sign in but has no role" unrepresentable, because such a row would reach the
      * authority converter with nothing to grant.
      */
    public void setLogin(String username, String passwordHash, UserRole role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.passwordChangedAt = OffsetDateTime.now();
    }

    /** Removes the login, and the role with it — the two exist only together. */
    public void clearLogin() {
        this.username = null;
        this.passwordHash = null;
        this.role = null;
        this.passwordChangedAt = OffsetDateTime.now();
    }

    /**
     * Change the role without touching the password.
     *
     * <p>Refused on somebody who cannot sign in: a role on a driver is the half of
     * {@code ck_users_role_pair} the database would reject anyway, and catching it here names
     * the reason rather than surfacing a constraint violation.
     */
    public void setRole(UserRole role) {
        if (!canSignIn()) {
            throw new IllegalStateException("Only someone with a login can have a role.");
        }
        this.role = role;
    }

    public UserRole getRole() { return role; }

    public boolean canSignIn() {
        return username != null && passwordHash != null;
    }
}
