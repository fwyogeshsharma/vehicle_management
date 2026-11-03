package com.vehiclemanagement.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * The hash of one user's public upload link. See changeset 033 for the reasoning.
 *
 * <p>The token itself is never stored and so never appears on this class.
 */
@Entity
@Table(name = "upload_links")
public class UploadLink {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "last_used_at", insertable = false, updatable = false)
    private OffsetDateTime lastUsedAt;

    protected UploadLink() {
    }

    public UploadLink(Long userId, String tokenHash, Long createdBy) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdBy = createdBy;
    }

    public Long getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public Long getCreatedBy() { return createdBy; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
}
