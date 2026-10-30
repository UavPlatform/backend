package com.uav.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** ADR-0013: audit stamps only; business event times must be recorded separately. */
@MappedSuperclass
@Getter
@Setter
public abstract class AuditedEntity {
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void initializeAuditTimes() {
        if (createdAt == null) createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if (updatedAt == null) updatedAt = createdAt;
    }

    @PreUpdate
    protected void updateAuditTime() {
        updatedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
