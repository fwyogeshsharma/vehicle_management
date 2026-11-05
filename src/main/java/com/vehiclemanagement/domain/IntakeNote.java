package com.vehiclemanagement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One dated remark on an intake. Append-only: there is no setter and no update path, which is
 * what makes the log trustworthy as "what was said, and when".
 */
@Entity
@Table(name = "intake_notes")
public class IntakeNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "intake_id", nullable = false, updatable = false)
    private Long intakeId;

    @Column(name = "body", nullable = false, updatable = false, length = 1000)
    private String body;

    @Column(name = "created_by", updatable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    @org.hibernate.annotations.Generated(event = org.hibernate.generator.EventType.INSERT)
    private OffsetDateTime createdAt;

    protected IntakeNote() {
    }

    public IntakeNote(Long intakeId, String body, String createdBy) {
        this.intakeId = intakeId;
        this.body = body;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public Long getIntakeId() { return intakeId; }
    public String getBody() { return body; }
    public String getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
