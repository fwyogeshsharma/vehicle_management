package com.vehiclemanagement.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Which companies a person belongs to. Many-to-many: a driver can be on two transporters'
 * books, and someone can own one company while working at another.
 *
 * <p>Plain membership — which company a person OWNS is read from {@link User#getUserType()},
 * not from here. That is the model's weakest joint and is documented on {@link UserType}.
 */
@Entity
@Table(name = "user_x_company")
@IdClass(UserCompany.Key.class)
public class UserCompany {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "company_id")
    private Long companyId;

    /** Free text: "Fleet manager", "Dispatcher". Not a permission and not an ownership claim. */
    @Column(length = 32)
    private String position;

    /** A person on several companies' books still has one main one (uq_uxc_primary). */
    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected UserCompany() {
    }

    public UserCompany(Long userId, Long companyId) {
        this.userId = userId;
        this.companyId = companyId;
    }

    public Long getUserId() { return userId; }
    public Long getCompanyId() { return companyId; }
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public static class Key implements Serializable {
        private Long userId;
        private Long companyId;

        public Key() {
        }

        public Key(Long userId, Long companyId) {
            this.userId = userId;
            this.companyId = companyId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(userId, k.userId) && Objects.equals(companyId, k.companyId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, companyId);
        }
    }
}
